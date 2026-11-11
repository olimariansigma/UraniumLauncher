# Uranium share server

A tiny Cloudflare Worker that stores share codes. "Share" in the launcher uploads a small JSON
list (Minecraft version, loader, which mods, optional config files) and gets back a code such as
`K7X-9QP`. Entering the code in another copy of the launcher fetches that list again.

- No accounts, no listing: a pack can only be read by someone who has its code.
- Packs expire after 90 days. Uploads are capped at 512 KB and 30 per hour per network address.
- It fits comfortably in Cloudflare's free plan.

## Deploying your own

You need a free [Cloudflare account](https://dash.cloudflare.com/sign-up) and
[Node.js](https://nodejs.org) installed. Then, in this folder:

```
npx wrangler login      # opens the browser once to connect your Cloudflare account
npx wrangler deploy     # creates the KV storage and uploads the worker
```

`deploy` prints the worker's address, which looks like
`https://uranium-share.<your-name>.workers.dev`. That address is all the launcher needs:

- paste it under **Settings > Share codes** in the launcher, or
- add it to the GitHub repository as an Actions variable named `URANIUM_SHARE_URL` so every build
  you publish already knows it.

Everyone who should be able to swap codes has to use the same address.

The address is not a secret. The first deploy also writes the id of the new KV namespace into
`wrangler.jsonc`; that id is not a secret either and can be committed.

## Developing

```
npm test                # unit tests, no Cloudflare account needed
npx wrangler dev        # run locally on http://localhost:8787
```
