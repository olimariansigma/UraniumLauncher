/**
 * Uranium Launcher share-code service.
 *
 *   POST /packs          body: pack manifest (JSON)  ->  201 { code, expiresAt }
 *   GET  /packs/<code>                               ->  200 manifest | 404
 *
 * Manifests are stored in a KV namespace bound as PACKS and expire on their own. There is no
 * listing endpoint and nothing here is tied to a user: a pack is reachable only by its code.
 */

// No 0/O or 1/I/L, so a code read aloud or typed from a screenshot cannot be mistyped.
const ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
const CODE_LENGTH = 6;
const MAX_BODY_BYTES = 512 * 1024;
const MAX_ITEMS = 1000;
const MAX_CONFIG_FILES = 400;
const PACK_TTL_SECONDS = 60 * 60 * 24 * 90;
const UPLOADS_PER_HOUR = 30;

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (error) {
      return json(500, { error: "Internal error" });
    }
  },
};

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";

  if (path === "/" && request.method === "GET") {
    return json(200, { service: "uranium-share", ok: true });
  }
  if (path === "/packs" && request.method === "POST") {
    return createPack(request, env);
  }
  const match = /^\/packs\/([^/]+)$/.exec(path);
  if (match && request.method === "GET") {
    return readPack(match[1], env);
  }
  return json(404, { error: "Not found" });
}

async function createPack(request, env) {
  // Reject oversized uploads before reading them when the client says how big they are.
  const declared = Number(request.headers.get("content-length") || "0");
  if (declared > MAX_BODY_BYTES) return json(413, { error: "Pack is too large" });

  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  if (!(await underRateLimit(env, ip))) {
    return json(429, { error: "Too many packs shared from this network. Try again in an hour." });
  }

  const text = await request.text();
  if (new TextEncoder().encode(text).length > MAX_BODY_BYTES) return json(413, { error: "Pack is too large" });

  let pack;
  try {
    pack = JSON.parse(text);
  } catch {
    return json(400, { error: "Body is not valid JSON" });
  }
  const problem = validatePack(pack);
  if (problem) return json(400, { error: problem });

  // Collisions are vanishingly rare (31^6 codes) but a silent overwrite would be nasty, so check.
  let code = null;
  for (let attempt = 0; attempt < 6 && code === null; attempt++) {
    const candidate = randomCode();
    if ((await env.PACKS.get(packKey(candidate))) === null) code = candidate;
  }
  if (code === null) return json(503, { error: "Could not allocate a code, try again" });

  await env.PACKS.put(packKey(code), JSON.stringify(pack), { expirationTtl: PACK_TTL_SECONDS });
  return json(201, {
    code: formatCode(code),
    expiresAt: new Date(Date.now() + PACK_TTL_SECONDS * 1000).toISOString(),
  });
}

async function readPack(rawCode, env) {
  const code = normalizeCode(rawCode);
  if (code === null) return json(400, { error: "That is not a valid share code" });
  const stored = await env.PACKS.get(packKey(code));
  if (stored === null) return json(404, { error: "No pack with that code. It may have expired." });
  return new Response(stored, { status: 200, headers: JSON_HEADERS });
}

/** Returns a description of what is wrong with a manifest, or null when it is acceptable. */
export function validatePack(pack) {
  if (pack === null || typeof pack !== "object" || Array.isArray(pack)) return "Pack must be a JSON object";
  if (pack.format !== 1) return "Unsupported pack format";
  if (!isText(pack.name, 80)) return "Pack needs a name";
  if (!isText(pack.mcVersion, 40)) return "Pack needs a Minecraft version";
  if (pack.loader !== undefined && !isText(pack.loader, 20)) return "Invalid loader";
  if (pack.loaderVersion !== undefined && pack.loaderVersion !== null && !isText(pack.loaderVersion, 60)) {
    return "Invalid loader version";
  }

  const content = pack.content ?? [];
  if (!Array.isArray(content) || content.length > MAX_ITEMS) return "Invalid content list";
  for (const item of content) {
    if (item === null || typeof item !== "object") return "Invalid content entry";
    if (!isText(item.provider, 20) || !isText(item.projectId, 64) || !isText(item.fileId, 64)) {
      return "Content entries need a provider, project id and file id";
    }
    if (!isText(item.fileName, 200)) return "Content entries need a file name";
  }

  const configs = pack.configs ?? {};
  if (configs === null || typeof configs !== "object" || Array.isArray(configs)) return "Invalid config map";
  const paths = Object.keys(configs);
  if (paths.length > MAX_CONFIG_FILES) return "Too many config files";
  for (const path of paths) {
    if (!isSafeRelativePath(path) || typeof configs[path] !== "string") return "Invalid config entry";
  }
  return null;
}

/** Config paths are written to disk by whoever redeems the code, so they must stay relative. */
export function isSafeRelativePath(path) {
  if (typeof path !== "string" || path.length === 0 || path.length > 240) return false;
  if (path.startsWith("/") || path.includes("\\") || path.includes(":") || path.includes("\0")) return false;
  return path.split("/").every((segment) => segment !== "" && segment !== "." && segment !== "..");
}

/** "k7x-9qp", "K7X9QP" and " k7x 9qp " all name the same pack. Returns null when malformed. */
export function normalizeCode(raw) {
  const code = decodeURIComponent(String(raw)).replace(/[\s-]/g, "").toUpperCase();
  if (code.length !== CODE_LENGTH) return null;
  for (const char of code) if (!ALPHABET.includes(char)) return null;
  return code;
}

export function formatCode(code) {
  return `${code.slice(0, 3)}-${code.slice(3)}`;
}

export function randomCode() {
  let code = "";
  while (code.length < CODE_LENGTH) {
    // Rejection sampling keeps every symbol equally likely (256 is not a multiple of 31).
    const byte = crypto.getRandomValues(new Uint8Array(1))[0];
    if (byte < ALPHABET.length * 8) code += ALPHABET[byte % ALPHABET.length];
  }
  return code;
}

/**
 * A simple per-address hourly cap. KV is eventually consistent, so a burst can slip a few extra
 * uploads through; that is fine for keeping a hobby service from being used as free storage.
 */
async function underRateLimit(env, ip) {
  const bucket = Math.floor(Date.now() / 3_600_000);
  const key = `rate:${ip}:${bucket}`;
  const used = Number((await env.PACKS.get(key)) || "0");
  if (used >= UPLOADS_PER_HOUR) return false;
  await env.PACKS.put(key, String(used + 1), { expirationTtl: 7200 });
  return true;
}

function packKey(code) {
  return `pack:${code}`;
}

function isText(value, maxLength) {
  return typeof value === "string" && value.length > 0 && value.length <= maxLength;
}

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
};

function json(status, body) {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}
