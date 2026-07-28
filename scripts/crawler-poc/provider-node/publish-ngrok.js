#!/usr/bin/env node
/**
 * publish-ngrok.js — point the catalog index at your ngrok (or any) host, then recompute
 * each file entry's digest + size. Baseline + change-files model, INDEX-ONLY (no DeDi
 * manifest / pointer file): the onix crawler is pointed straight at the catalog index URL.
 *
 * Run from this folder, after editing any catalog/change JSON (or when the ngrok URL changes):
 *   NGROK_URL=https://your-id.ngrok-free.dev node publish-ngrok.js
 *   node publish-ngrok.js                         # uses BASE_URL default below if NGROK_URL unset
 *
 * What it does, every run:
 *   1. Rewrites every INTERNAL url in catalog/catalog-index.json — the catalog's `baseline.url`
 *      and each `changes[].url` that resolves to a local file under catalog/ — to
 *      <BASE_URL>/<path>. External URLs (schema.*, image CDNs) are left untouched.
 *   2. Recomputes, over the ACTUAL file bytes each static server serves, per entry:
 *        digest = "sha-256:<hex>"   (baseline + every change file)
 *        size   = byte length       (drives the crawler's baseline-vs-changes cutover)
 *
 * It does NOT touch git. Local edits only. The crawler verifies each file by its own digest,
 * so there is no index-level or manifest digest to maintain here.
 */
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const ROOT = __dirname;
const INDEX = path.join(ROOT, "catalog", "catalog-index.json");

// Your ngrok host. Env var wins; edit this default to your reserved domain if you have one.
const BASE_URL = (process.env.NGROK_URL || "https://magician-aspirin-sympathy.ngrok-free.dev").replace(/\/+$/, "");

// sha-256 of the exact file bytes (whatever the static server serves).
function sha256Of(file) {
  return "sha-256:" + crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}
function sizeOf(file) {
  return fs.statSync(file).size;
}

// If a URL resolves to a file we host locally (its path has a `catalog` segment and that
// file exists under ROOT), return its repo-relative segments; else null.
function internalRel(url) {
  let segs;
  try {
    segs = new URL(url).pathname.replace(/^\/+/, "").split("/").map(decodeURIComponent);
  } catch {
    return null;
  }
  const i = segs.indexOf("catalog");
  if (i === -1) return null;
  const rel = segs.slice(i);
  return fs.existsSync(path.join(ROOT, ...rel)) ? rel : null;
}
function urlToLocal(url) {
  const rel = internalRel(url);
  return rel ? path.join(ROOT, ...rel) : null;
}
function toBaseUrl(rel) {
  return `${BASE_URL}/${rel.join("/")}`;
}

const index = JSON.parse(fs.readFileSync(INDEX, "utf8"));

// ---- Pass 1: rewrite internal urls (baseline + changes) to the current BASE_URL ----
let rewrites = 0;
function rewriteEntry(entry) {
  if (!entry || !entry.url) return;
  const rel = internalRel(entry.url);
  if (!rel) return; // external (schema, CDN) — leave it
  const next = toBaseUrl(rel);
  if (next !== entry.url) {
    entry.url = next;
    rewrites++;
  }
}
for (const cat of index.catalogs || []) {
  rewriteEntry(cat.baseline);
  for (const ch of cat.changes || []) rewriteEntry(ch);
}

// ---- Pass 2: recompute digest + size over the actual (rewritten-target) local bytes ----
let updated = 0;
function refreshEntry(entry, label) {
  if (!entry || !entry.url) return;
  const local = urlToLocal(entry.url);
  if (!local) {
    console.error(`ERROR: entry url is not local: ${entry.url}`);
    process.exit(1);
  }
  const d = sha256Of(local);
  const s = sizeOf(local);
  if (entry.digest !== d || entry.size !== s) {
    entry.digest = d;
    entry.size = s;
    updated++;
    console.log(`  ${label} [${path.basename(local)}] -> ${d} (${s} bytes)`);
  }
}
for (const cat of index.catalogs || []) {
  refreshEntry(cat.baseline, `baseline v${cat.baseline && cat.baseline.version}`);
  for (const ch of cat.changes || []) refreshEntry(ch, `change v${ch.version}`);
}

fs.writeFileSync(INDEX, JSON.stringify(index, null, 2) + "\n");

console.log(`URL rewrite: ${rewrites} reference(s) pointed at ${BASE_URL}`);
console.log(`digest/size: ${updated} entry(ies) refreshed`);
console.log(`serve this folder; crawler index URL = ${BASE_URL}/catalog/catalog-index.json`);
