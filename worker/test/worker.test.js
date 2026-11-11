import test from "node:test";
import assert from "node:assert/strict";
import worker, { formatCode, isSafeRelativePath, normalizeCode, randomCode, validatePack } from "../src/index.js";

/** An in-memory stand-in for a KV namespace. */
function fakeKv() {
  const store = new Map();
  return {
    store,
    async get(key) {
      return store.has(key) ? store.get(key) : null;
    },
    async put(key, value) {
      store.set(key, value);
    },
  };
}

const pack = {
  format: 1,
  name: "Test pack",
  mcVersion: "1.21.1",
  loader: "FABRIC",
  loaderVersion: "0.16.14",
  content: [{ provider: "MODRINTH", projectId: "AANobbMI", fileId: "abc", type: "MOD", title: "Sodium", fileName: "sodium.jar" }],
  configs: { "options.txt": "fov:0.5", "config/sodium-options.json": "{}" },
};

function post(body, headers = {}) {
  return new Request("https://share.example/packs", {
    method: "POST",
    body: typeof body === "string" ? body : JSON.stringify(body),
    headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.7", ...headers },
  });
}

test("a pack round-trips through its code", async () => {
  const env = { PACKS: fakeKv() };
  const created = await worker.fetch(post(pack), env);
  assert.equal(created.status, 201);
  const { code, expiresAt } = await created.json();
  assert.match(code, /^[2-9A-Z]{3}-[2-9A-Z]{3}$/);
  assert.ok(Date.parse(expiresAt) > Date.now());

  // Lower case, no dash and stray spaces are all accepted when redeeming.
  for (const typed of [code, code.toLowerCase(), code.replace("-", ""), ` ${code.replace("-", " ")} `]) {
    const fetched = await worker.fetch(new Request(`https://share.example/packs/${encodeURIComponent(typed)}`), env);
    assert.equal(fetched.status, 200);
    assert.deepEqual(await fetched.json(), pack);
  }
});

test("unknown and malformed codes are rejected", async () => {
  const env = { PACKS: fakeKv() };
  assert.equal((await worker.fetch(new Request("https://share.example/packs/ABC-DEF"), env)).status, 404);
  assert.equal((await worker.fetch(new Request("https://share.example/packs/nope"), env)).status, 400);
  assert.equal((await worker.fetch(new Request("https://share.example/packs/O0O-1I1"), env)).status, 400);
  assert.equal((await worker.fetch(new Request("https://share.example/other"), env)).status, 404);
  // Rate-limit counters live in the same namespace but can never be read back as a pack.
  env.PACKS.store.set("rate:1.2.3.4:1", "5");
  assert.equal((await worker.fetch(new Request("https://share.example/packs/rate:1"), env)).status, 400);
});

test("bad uploads are refused", async () => {
  const env = { PACKS: fakeKv() };
  assert.equal((await worker.fetch(post("{not json"), env)).status, 400);
  assert.equal((await worker.fetch(post({ ...pack, format: 2 }), env)).status, 400);
  assert.equal((await worker.fetch(post({ ...pack, configs: { "../../evil": "x" } }), env)).status, 400);
  assert.equal((await worker.fetch(post({ ...pack, content: [{ provider: "MODRINTH" }] }), env)).status, 400);
  const huge = { ...pack, configs: { "config/big.txt": "x".repeat(600 * 1024) } };
  assert.equal((await worker.fetch(post(huge), env)).status, 413);
  assert.equal([...env.PACKS.store.keys()].filter((key) => key.startsWith("pack:")).length, 0);
});

test("uploads are rate limited per address", async () => {
  const env = { PACKS: fakeKv() };
  let last;
  for (let i = 0; i < 31; i++) last = await worker.fetch(post(pack), env);
  assert.equal(last.status, 429);
  // A different address is unaffected.
  assert.equal((await worker.fetch(post(pack, { "cf-connecting-ip": "198.51.100.2" }), env)).status, 201);
});

test("helpers", () => {
  assert.equal(normalizeCode("k7x-9qp"), "K7X9QP");
  assert.equal(normalizeCode("K7X9QPZ"), null);
  assert.equal(formatCode("K7X9QP"), "K7X-9QP");
  for (let i = 0; i < 200; i++) assert.notEqual(normalizeCode(randomCode()), null);
  assert.equal(validatePack(pack), null);
  assert.ok(validatePack([]));
  assert.ok(isSafeRelativePath("config/a/b.toml"));
  for (const bad of ["/etc/passwd", "config/../x", "C:/x", "a\\b", "", "config//x", "./x"]) {
    assert.equal(isSafeRelativePath(bad), false, bad);
  }
});
