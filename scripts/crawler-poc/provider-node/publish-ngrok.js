#!/usr/bin/env node
/**
 * publish-ngrok.js — point the DeDi chain at your ngrok (or any) host, then recompute digests.
 *
 * Use this instead of publish.js when you want to serve the whole catalog from your local
 * machine through an ngrok tunnel, so the crawler stays on your host instead of jumping to
 * GitHub raw.
 *
 * Run from the repo root, after editing any catalog JSON (or after your ngrok URL changes):
 *   NGROK_URL=https://your-id.ngrok-free.app node publish-ngrok.js
 *   node publish-ngrok.js                         # uses BASE_URL below if NGROK_URL is unset
 *
 * What it does, every run:
 *   1. Rewrites every INTERNAL file reference (anything that resolves to a local file under
 *      bucket/ or .well-known/) to  <BASE_URL>/<path>  — leaves external URLs (schema.*,
 *      image CDNs, the github.com identity/namespace) untouched.
 *   2. Recomputes the whole digest chain over the ACTUAL file bytes:
 *        catalog file  -> index  parts[].digest
 *        index file    -> manifest files[].digest
 *      (hashing the real bytes, trailing newline included — exactly what a static server serves).
 *
 * It does NOT touch git. Nothing is committed or pushed. Local edits only.
 */
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const ROOT = __dirname;
const MANIFEST = path.join(ROOT, ".well-known", "dedi.json");

// Your ngrok host. Env var wins; edit this default to your reserved domain if you have one.
const BASE_URL = (process.env.NGROK_URL || "https://magician-aspirin-sympathy.ngrok-free.dev").replace(/\/+$/, "");

// sha-256 of the exact file bytes (trailing newline included).
function sha256Of(file) {
  return "sha-256:" + crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

// If a URL points at a file we host locally (path contains a `bucket` or `.well-known`
// segment and that file exists under ROOT), return its repo-relative segments; else null.
function internalRel(url) {
  let segs;
  try {
    segs = new URL(url).pathname.replace(/^\/+/, "").split("/").map(decodeURIComponent);
  } catch {
    return null;
  }
  const i = segs.findIndex((s) => s === "bucket" || s === ".well-known");
  if (i === -1) return null;
  const rel = segs.slice(i);
  return fs.existsSync(path.join(ROOT, ...rel)) ? rel : null;
}

// Resolve any internal URL to its local file path (null if not internal).
function urlToLocal(url) {
  const rel = internalRel(url);
  return rel ? path.join(ROOT, ...rel) : null;
}

// Normalize an internal URL to the current BASE_URL.
function toBaseUrl(rel) {
  return `${BASE_URL}/${rel.join("/")}`;
}

// ---- Pass 1: rewrite internal URLs in every JSON file we host ----
function jsonFiles() {
  const out = [MANIFEST];
  const bucket = path.join(ROOT, "bucket");
  if (fs.existsSync(bucket)) {
    for (const f of fs.readdirSync(bucket)) {
      if (f.endsWith(".json")) out.push(path.join(bucket, f));
    }
  }
  return out;
}

let rewrites = 0;
for (const file of jsonFiles()) {
  const before = fs.readFileSync(file, "utf8");
  const after = before.replace(/https?:\/\/[^\s"'\\<>]+/g, (url) => {
    const rel = internalRel(url);
    if (!rel) return url;
    const next = toBaseUrl(rel);
    if (next !== url) rewrites++;
    return next;
  });
  if (after !== before) {
    fs.writeFileSync(file, after);
    console.log(`  rewrote URLs in ${path.relative(ROOT, file)}`);
  }
}
console.log(`URL rewrite: ${rewrites} reference(s) pointed at ${BASE_URL}`);

// ---- Pass 2: recompute the digest chain (bottom-up), over the rewritten bytes ----
// Replace one occurrence of `oldD` with `newD` in `file`. Returns true if it changed.
function swapDigest(file, oldD, newD) {
  if (oldD === newD) return false;
  const txt = fs.readFileSync(file, "utf8");
  if (!txt.includes(oldD)) {
    console.error(`ERROR: digest ${oldD} not found in ${path.relative(ROOT, file)}`);
    process.exit(1);
  }
  fs.writeFileSync(file, txt.replace(oldD, newD)); // first match only
  return true;
}

const manifest = JSON.parse(fs.readFileSync(MANIFEST, "utf8"));
let changed = false;

for (const fref of manifest.files || []) {
  const indexPath = urlToLocal(fref.url);
  if (!indexPath) {
    console.error(`ERROR: manifest file url is not local: ${fref.url}`);
    process.exit(1);
  }
  const index = JSON.parse(fs.readFileSync(indexPath, "utf8"));

  // catalog file bytes -> index parts[].digest
  for (const rec of index.records || []) {
    for (const part of rec.details.parts || []) {
      const catPath = urlToLocal(part.url);
      if (!catPath) {
        console.error(`ERROR: part url is not local: ${part.url}`);
        process.exit(1);
      }
      const next = sha256Of(catPath);
      if (swapDigest(indexPath, part.digest, next)) {
        changed = true;
        console.log(`  index part [${path.basename(catPath)}] -> ${next}`);
      }
    }
  }

  // index file bytes (now updated) -> manifest files[].digest
  const nextIndex = sha256Of(indexPath);
  if (swapDigest(MANIFEST, fref.digest, nextIndex)) {
    changed = true;
    console.log(`  manifest [${path.basename(indexPath)}] -> ${nextIndex}`);
  }
}

console.log("digest chain " + (changed ? "updated." : "already up to date."));
console.log(`serve the repo root and expose it: ngrok http <port>  (base = ${BASE_URL})`);
