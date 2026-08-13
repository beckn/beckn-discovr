# Decentralized Catalog Discovery — Publisher & DeDi Specification

**Status:** Draft for review
**Audience:** Catalog publishers (providers) and DeDi registry operators
**Purpose:** Describe how a provider publishes a catalog so that any consumer node on the
network can discover it, verify it, pull it securely, and index it — without a central
catalog host and without the consumer ever trusting data it cannot cryptographically verify.

This is a **protocol and contract** document. It defines the files a publisher hosts, the
record a publisher registers in DeDi, the keys and signatures involved, and the pull
handshake for restricted catalogs. It intentionally says nothing about how any particular
consumer implements its crawler or ingestion component.

---

## 1. The idea in one paragraph

There is **no central catalog server**. Each **provider** publishes its catalog as a set of
static, content-addressed files on its own infrastructure, and **registers a pointer** to
those files in **DeDi**, a decentralized directory. Each **consumer node** on the network
(for example, a mobility app such as Namma Yatri) runs its own **crawler + ingestion pipeline**.
The crawler asks DeDi "who is publishing catalogs?", follows the pointers, and **verifies the
signed digest chain rooted in the provider's public key**. It then hands each catalog file — as a
**reference** (its URL + expected digest), obtained via a signed, time-boxed handshake when
restricted — to its **ingestion component**, which fetches the bytes, checks them against the
digest, and indexes. When a provider updates a catalog, the digest changes, the crawler notices,
and only the changed parts are re-fetched and re-indexed.

Trust flows **top-down through digests**; data flows **provider → consumer**; and the consumer
**never indexes anything it has not verified**.

---

## 2. Actors and responsibilities

| Actor | Runs where | Responsibility |
|-------|-----------|----------------|
| **Provider / Publisher** | Provider's own infra | Hosts the manifest, the catalog index, and the catalog part files. Registers a record in DeDi. Signs its published documents. Honors authenticated pull requests for restricted files. |
| **DeDi** (Decentralized Directory) | Network infrastructure | Holds one record per participant: identity, public key(s), and a pointer to that participant's entry document. Exposes a lookup/search API so anyone can enumerate participants and resolve their pointers. |
| **Consumer node** | Consumer's own infra | Runs a **crawler** (discovers, verifies the digest chain, obtains fetchable URLs) and an **ingestion component** (fetches the bytes, verifies them, parses, indexes). Also holds its **own key pair**, registered in DeDi, used to authenticate itself to providers. |

> **Separation of concerns:** the crawler **discovers and verifies the trust chain**, then hands
> a verified **reference** (URL + digest) onward — it does not fetch or parse catalog contents.
> The **ingestion component** fetches and processes. This keeps the crawler's trust boundary thin.

---

## 3. Trust model — two keys, two directions

There are two independent signing relationships. Do not conflate them.

### 3.1 Provider key — proves the catalog is authentic (provider → consumer)

The **provider's public key** lives in DeDi and in the provider's own manifest. It is used by
the consumer to verify that:

1. the provider's published documents (manifest, index) are signed by the provider, and
2. every file in the chain matches its announced digest.

This answers: *"Is this catalog really from this provider, and is it exactly what they
published — unmodified in transit or at rest?"*

### 3.2 Consumer key — proves who is asking (consumer → provider)

The **consumer node's public key** is also registered in DeDi. When a consumer pulls a
**restricted** catalog file, its crawler **signs the pull request** with its private key. The
provider looks the consumer up in DeDi, verifies the signature, and only then releases the
file (as a time-boxed link — see §7).

This answers: *"Who is this consumer, are they entitled to this catalog, and did they really
send this request?"*

```
   Provider key  ─ signs ─►  manifest + index + digests   ─ verified by ─►  Consumer
   Consumer key  ─ signs ─►  pull request                 ─ verified by ─►  Provider
```

---

## 4. What a provider publishes

A provider hosts three layers of static files. Each layer points to the next and **announces
the digest of what it points to**, forming a verifiable chain.

```
DeDi record ──► Manifest ──► Catalog Index ──► Catalog Part files
 (pointer +      (identity,    (records:         (the actual
  public key)     keys,         catalogs →         catalog data)
                  index URL      parts + digests)
                  + digest)
```

### 4.1 The Manifest — `/.well-known/dedi.json`

The provider's entry point, always at this **fixed well-known URL**. It declares the provider's
identity, its public key(s), and points to one or more registry index files, each with a digest.
Its *location* is stable; its *content* is re-published whenever an index digest below it
changes (because the manifest carries that digest).

```json
{
  "dedi_version": "0.1",
  "type": "dedi-manifest",
  "domain": "techmart.example",
  "name": "TechMart Provider Node",
  "keys": [
    { "kid": "key-001", "kty": "OKP", "crv": "Ed25519",
      "x": "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo" }
  ],
  "updated_at": "2026-07-17T09:00:00Z",
  "next_update": "2026-07-24T09:00:00Z",
  "files": [
    {
      "registry": "beckn-catalogs",
      "networkId": "ondc-retail",
      "url": "https://techmart.example/dedi/beckn-catalogs.dedi.json",
      "digest": "sha-256:be40742e5ffa2c8c948d28c78115b718c1913cd753fc4773d4870fa5b26d0595",
      "schema": "https://schema.nfh.global/dedi/BecknCatalogIndexRecord/1.0.0/schema.json",
      "state": "live"
    }
  ],
  "proof": {
    "verification_method": "key-001",
    "canonicalization": "JCS",
    "jws": "<detached-jws-over-canonicalized-manifest>"
  }
}
```

Notable fields:

- **`keys[]`** — the provider's public key(s). Ed25519 recommended. `kid` is referenced by
  every `proof` block so keys can be rotated without ambiguity.
- **`files[].digest`** — the SHA-256 of the index file this points to. A consumer that has the
  manifest can detect any change to the index without downloading it, and can reject an index
  whose bytes don't match.
- **`files[].networkId`** — the network this catalog registry is published under (mirrors the
  registry's `networkId` in DeDi and in the index). Lets a crawler skip an entire registry that
  is not on its network before even fetching the index.
- **`updated_at` / `next_update`** — when the document was last published, and the provider's
  hint for when it will publish next. The crawler uses `next_update` only to **pace polling**
  ("don't re-check before this"); set it to your real change cadence — minutes if catalogs change
  often, longer if rarely. It's advisory: the **digest**, not this timestamp, decides whether
  anything actually changed.
- **`proof`** — a detached signature over the canonicalized document (JCS canonicalization,
  JWS signature), produced with the key named by `verification_method`.

### 4.2 The Catalog Index — one DeDi file per registry

Pointed to by the manifest. Lists every catalog the provider offers as a **record**, and each
record lists its **parts** (the actual data files) with **digests** and modification times.

```json
{
  "dedi_version": "0.1",
  "type": "dedi-file",
  "source_url": "https://techmart.example/dedi/beckn-catalogs.dedi.json",
  "next_update": "2026-07-24T09:00:00Z",
  "publisher": {
    "domain": "techmart.example",
    "key": { "kid": "key-001", "kty": "OKP", "crv": "Ed25519",
             "x": "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo" }
  },
  "namespace": "techmart.example",
  "registry": {
    "name": "beckn-catalogs",
    "networkId": "ondc-retail",
    "schema": "https://schema.nfh.global/dedi/BecknCatalogIndexRecord/1.0.0/schema.json",
    "state": "live",
    "updated_at": "2026-07-17T09:00:00Z"
  },
  "records": [
    {
      "record_name": "CAT-ELECTRONICS-2026",
      "details": {
        "catalogId": "CAT-ELECTRONICS-2026",
        "version": 42,
        "catalogType": "REGULAR",
        "status": "ACTIVE",
        "visibility": { "scope": "public", "networks": ["ondc-retail", "eon-retail"] },
        "updatedAt": "2026-07-17T09:00:00Z",
        "schemaTypes": ["https://schema.beckn.org/retail/schema/1.1.0/context.jsonld"],
        "parts": [
          {
            "url": "https://techmart.example/catalogs/CAT-ELECTRONICS-2026-000.json",
            "digest": "sha-256:c0d680701b672e7875b9b287eb363e9fe108e01c3c476517f1f4adc2ebad2189",
            "lastModified": "2026-07-17T09:00:00Z"
          },
          {
            "url": "https://techmart.example/catalogs/CAT-ELECTRONICS-2026-001.json",
            "digest": "sha-256:dc44fd9261046b18cbc0c88762572264554340774020b01fbbf12d8dbe51c906",
            "lastModified": "2026-06-12T11:30:00Z"
          }
        ]
      }
    },
    {
      "record_name": "CAT-EON-EXCLUSIVE-2026",
      "details": {
        "catalogId": "CAT-EON-EXCLUSIVE-2026",
        "version": 12,
        "status": "ACTIVE",
        "visibility": { "scope": "restricted", "networks": ["eon-retail"] },
        "updatedAt": "2026-07-14T08:00:00Z",
        "schemaTypes": ["https://schema.beckn.org/retail/schema/1.1.0/context.jsonld"],
        "parts": [
          {
            "url": "https://techmart.example/catalogs/CAT-EON-EXCLUSIVE-2026.json",
            "digest": "sha-256:c35db743c55396e65d8daa24a8bb7418c3bc8c073ffffcaf21869c9e371f43ad",
            "lastModified": "2026-07-14T08:00:00Z"
          }
        ]
      }
    },
    {
      "record_name": "CAT-ELECTRONICS-2025",
      "details": {
        "catalogId": "CAT-ELECTRONICS-2025",
        "version": 30,
        "status": "RETIRED",
        "updatedAt": "2026-01-31T00:00:00Z",
        "retiredAt": "2026-01-31T00:00:00Z"
      }
    }
  ],
  "proof": {
    "verification_method": "key-001",
    "canonicalization": "JCS",
    "jws": "<detached-jws-over-canonicalized-index>"
  }
}
```

Notable fields:

- **`registry.networkId`** — the network this catalog registry is published under in DeDi. It is
  the coarsest network filter: a crawler serving only `foo-mobility` ignores a registry whose
  `networkId` is `ondc-retail`.
- **`records[]`** — one per catalog. A catalog is split into one or more **parts** so large
  catalogs can be published, changed, and pulled incrementally.
- **`details.version`** — a monotonically increasing catalog version. A consumer must **never
  accept a lower version than it has already indexed** (rollback protection).
- **`details.status`** — `ACTIVE` / `RETIRED` etc. A retired record signals the consumer to
  drop that catalog. Parts may be omitted for retired records.
- **`details.visibility`** — an object `{ "scope", "networks" }`:
  - **`scope`** — the *access* axis. `"public"` = the part files may be fetched directly;
    `"restricted"` = the part files require the authenticated pull in §7.
  - **`networks`** — the *membership* axis: the networks this catalog belongs to. For
    `restricted`, this doubles as the **allow-list** — only consumers who are members of one of
    these networks may pull it. For `public`, it is affiliation only (anyone may fetch, but a
    crawler can still use it to decide relevance).
- **`parts[].digest`** — the SHA-256 of the catalog part file. This is the leaf of the digest
  chain; it is what the consumer checks the downloaded bytes against.

> **Restricted records stay listed.** A `restricted` record still appears in the public index,
> so its *existence and metadata* (name, version, schema types) are visible to everyone — only
> the *part-file bytes* are access-controlled. If existence itself must be hidden from
> non-members, omit restricted records from the public index (or publish a per-network index).

### 4.3 The Catalog Part files

The actual catalog payload (Beckn v2.0 catalog structure — providers, resources, offers).
These are ordinary static JSON files at the URLs named in the index. Their **only**
requirement in this spec is: **the bytes served must hash to the digest announced in the
index.** They may be public, or restricted (§7).

### 4.4 Where network identity appears — and why in three places

Network shows up at three levels, each answering a different question. They are not redundant:

| Level | Field | Answers | Used by |
|-------|-------|---------|---------|
| **DeDi registry / namespace** | `registry.networkId` (also mirrored in `manifest.files[].networkId`) | *Which network is this whole catalog registry published under?* | Crawler — coarse filter: skip entire registries not on my network, before fetching the index. |
| **Index catalog record** | `details.visibility.networks` (with `scope`) | *Which networks does this specific catalog belong to, and is it public or restricted?* | Crawler — per-catalog relevance; Provider — the allow-list for restricted pulls. |
| **Consumer-node DeDi record** | `details.networkIds` | *Which networks does this consumer belong to?* | Provider — entitlement check when a consumer requests a restricted catalog (§7). |

**How the levels combine (worked example).** `registry.networkId` is the network the registry
is *published under* in DeDi — a crawler processes a registry when that `networkId` is one of
its **own** `networkIds`. `visibility.networks` is then an independent per-catalog scope; it
need **not** be a subset of the registry's network. In the running example the registry is homed
on `ondc-retail`, so a consumer node like Namma Yatri (`networkIds: ["ondc-retail",
"eon-retail"]`) processes it, and inside it finds:

- **`CAT-ELECTRONICS-2026`** — `scope: public`, affiliated to `ondc-retail` + `eon-retail` →
  fetched directly by anyone.
- **`CAT-EON-EXCLUSIVE-2026`** — `scope: restricted` to `eon-retail` → **visible in the index to
  everyone**, but pullable only by `eon-retail` members. Namma Yatri qualifies (it is an
  `eon-retail` member); an `ondc-retail`-only consumer would see the record but be refused the
  pull (§7).

---

## 5. How a provider registers in DeDi

Registration binds three things together in a directory that consumers can query:

1. the provider's **identity** (domain / name),
2. the provider's **public key(s)**, and
3. a **pointer to the provider's entry document** (its manifest, and through it, its index).

A DeDi record for a catalog publisher looks like this (illustrative):

```json
{
  "namespace": "beckn-catalogs",
  "record_name": "techmart.example",
  "details": {
    "domain": "techmart.example",
    "name": "TechMart Provider Node",
    "keys": [
      { "kid": "key-001", "kty": "OKP", "crv": "Ed25519",
        "x": "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo" }
    ],
    "manifest_url": "https://techmart.example/.well-known/dedi.json",
    "state": "live",
    "updated_at": "2026-07-17T09:00:00Z"
  }
}
```

A **consumer node registers the same way** — its own public key plus the **`networkIds` it
belongs to**. A provider reads this record to (a) verify a consumer's signed pull request and
(b) decide entitlement to restricted catalogs (§7). The `kid` here is the one referenced by the
`keyId` in the §7 pull request. Provider and consumer records are told apart by **shape** — a
provider record carries `manifest_url`, a consumer record carries `networkIds` — so no explicit
role field is needed.

```json
{
  "namespace": "beckn-catalogs",
  "record_name": "namma-yatri.example",
  "details": {
    "domain": "namma-yatri.example",
    "name": "Namma Yatri Consumer Node",
    "networkIds": ["ondc-retail", "eon-retail"],
    "keys": [
      { "kid": "key-001", "kty": "OKP", "crv": "Ed25519",
        "x": "3sMB0mFhZ2t8p1n9c2fXqRZ0kd7vJmS5aQwYbN4uHkE" }
    ],
    "state": "live",
    "updated_at": "2026-07-20T09:00:00Z"
  }
}
```

Because this consumer's `networkIds` includes `eon-retail`, it **is** entitled to pull the
restricted `CAT-EON-EXCLUSIVE-2026` catalog; a consumer without `eon-retail` would be refused.

> **Why register a pointer instead of the catalog itself?** DeDi stays small and stable — it
> holds identity + key + a URL, not catalog data. Catalog data lives with the provider, who
> can update it freely. DeDi only changes when identity, keys, or the entry pointer change.

### 5.1 What DeDi must expose

For this model to work, DeDi must offer, at minimum, a **read/lookup API**:

| Capability | Purpose |
|-----------|---------|
| **List / search records in a namespace** (e.g. `beckn-catalogs`) | So a crawler can enumerate every catalog provider without knowing them in advance. |
| **Filter by `networkId`** | So a crawler serving one network fetches only registries published under that network, ignoring the rest at lookup time. |
| **Resolve a single record by name/domain** | So a provider can look up a *consumer's* public key **and `networkIds`** to verify a pull request and check entitlement, and vice versa. |
| **Return the record's public key(s), `networkId(s)`, and pointer** | The three things the verification and entitlement steps need. |

Write operations (register, update key, retire) are performed by the record owner and are out
of scope for this document beyond noting they must be authenticated to the record's key.

---

## 6. Discovery — how a crawler finds and verifies a catalog

This is the read path, from a consumer node's crawler. Every step that ingests a document
**verifies before trusting**.

```
1. LOOKUP     Query DeDi namespace "beckn-catalogs" → list of providers + manifest URLs + keys.

2. MANIFEST   For each provider: fetch /.well-known/dedi.json.
              Verify manifest.proof against the provider's DeDi public key.

3. INDEX      Follow manifest.files[].url. Before trusting, hash the downloaded index and
              check it equals manifest.files[].digest. Then verify index.proof.

4. PARTS      For each record's parts[]: if the part's digest is unchanged since last time,
              skip it. Otherwise get a fetchable URL — the public part URL, or a presigned URL
              via the §7 handshake if restricted.

5. HAND OFF   Pass the reference (URL + parts[].digest + catalog metadata) to the ingestion
              component (§8). It fetches, checks the bytes against the digest, and indexes.
              Bytes whose digest does not match are never indexed.
```

**Rejection is silent and safe.** If any digest fails, any signature fails, or a version goes
backward, the crawler rejects that document and keeps the last known-good state. A tampered or
mis-published file simply never gets indexed.

---

## 7. Authenticated pull — signed request + time-boxed challenge

Public catalogs (`visibility.scope: "public"`) can be fetched directly. **Restricted catalogs**
(`visibility.scope: "restricted"`) require the consumer to prove who it is, and the provider to
release the file only briefly. The recommended handshake:

### 7.1 Step 1 — the crawler makes a signed pull request

The consumer's crawler sends a request to the provider's catalog endpoint and **signs it with
the consumer node's private key**. The signature identifies the consumer via a `keyId` that
resolves to its DeDi record.

```http
GET /catalogs/CAT-EON-EXCLUSIVE-2026.json HTTP/1.1
Host: techmart.example
Signature: keyId="namma-yatri.example#key-001",
           algorithm="ed25519",
           created=1784714400,
           expires=1784714460,
           headers="(request-target) (created) (expires) host",
           signature="<base64-signature>"
```

`created` / `expires` are UNIX epoch seconds — here `1784714400` = `2026-07-22T10:00:00Z`, with
a 60-second freshness window (`1784714460` = `2026-07-22T10:01:00Z`). The covered `headers`
list omits `digest` because a GET pull carries no body. `signature` is the base64 Ed25519
signature the provider recomputes over those covered components to verify the request.

### 7.2 Step 2 — the provider verifies and issues a time-boxed link

The provider:

1. reads the `keyId`, looks the consumer up in **DeDi**, and fetches its public key **and its
   `networkIds`**;
2. verifies the request signature and that it is fresh (within `created`/`expires`);
3. **entitlement:** confirms the consumer's `networkIds` intersects the catalog's
   `visibility.networks` (here, the consumer must be a member of `eon-retail`);
4. if all pass, returns a **presigned, expiring URL** — a link valid only for a short window
   (e.g. **1–2 hours**) that carries its own embedded signature and expiry (the "challenge").

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "url": "https://cdn.techmart.example/catalogs/CAT-EON-EXCLUSIVE-2026.json?X-Expires=1784721600&X-Signature=<sig>",
  "expiresAt": "2026-07-22T12:00:00Z",
  "digest": "sha-256:c35db743c55396e65d8daa24a8bb7418c3bc8c073ffffcaf21869c9e371f43ad"
}
```

Here `X-Expires=1784721600` = `2026-07-22T12:00:00Z` — two hours after the request — matching
`expiresAt`. The `digest` equals the part digest for `CAT-EON-EXCLUSIVE-2026.json` in the index
(§4.2), so whoever fetches the bytes can verify them.

> **Publisher requirement — presigned URLs must be bearer-usable.** The component that
> *authenticates* (the crawler, which holds the consumer key) may not be the same component that
> ultimately *fetches* the bytes (see §8). The presigned URL must therefore be usable by **any
> bearer of the link within its validity window** — it carries its authorization in the link
> itself (embedded signature + expiry) and must **not** be bound to the requester's IP or client
> identity.

### 7.3 Step 3 — the bytes are fetched within the window and verified

The URL must be fetched **before it expires**; the fetched bytes are hashed and checked against
the `digest` from the index (§4.2). Because the presigned URL is self-authenticating (it carries
its own signature + expiry), this fetch is a plain GET and **need not be done by the crawler
itself** — the crawler can hand the **reference** (URL + digest + metadata) to the consumer
node's ingestion component, which performs the fetch, the digest check, and the indexing (§8).
If the window lapses before the fetch, the crawler repeats Step 1 for a fresh link.

```
Consumer node                             Provider
  crawler │  signed pull request (consumer key)   │
  (key)   │ ─────────────────────────────────────►│  verify signer via DeDi
          │                                        │  check entitlement
          │      presigned URL + expiry + digest   │
          │ ◄─────────────────────────────────────│
          │  hand reference (URL + digest + metadata) ──► ingestion component
ingestion │  GET presigned URL (within 1–2h)       │
          │ ─────────────────────────────────────►│
          │            catalog part bytes          │
          │ ◄─────────────────────────────────────│
          │  hash == index digest?  ── yes ──► parse + index
```

> **Why presign instead of streaming the file on the signed request?** It decouples
> authorization from delivery. The provider authorizes once (cheap, at the API tier) and lets
> a CDN/object store serve the bytes (scalable), while the short expiry bounds the blast radius
> if a link leaks. The consumer's ability to re-request a fresh link makes expiry safe to keep
> short.

Direct authenticated download (serving the bytes on the signed request itself) is a valid
simpler alternative for providers who don't need the CDN decoupling; the presigned flow is the
recommended default for scale.

---

## 8. Handing off to the ingestion component — the crawler doesn't process

The crawler's job ends at **trust and discovery**. Once it has verified the chain and (for
restricted catalogs) obtained the presigned URL, it hands a **verified reference** to the
consumer node's ingestion component:

- the **URL** — public part URL, or the presigned URL;
- the **`digest`** from the verified index — the integrity anchor;
- the catalog **metadata** — `catalogId`, `version`, `networkId`, `schemaTypes`.

The ingestion component **fetches the URL, verifies the bytes against that `digest`, then parses
and indexes**. The crawler never downloads or parses catalog content.

Why the split:

- **Thin, auditable trust boundary.** The crawler only discovers, verifies the chain, and passes
  a digest it has already checked. It holds the signing key; it never touches catalog structure.
- **Format independence.** Providers can evolve catalog structure (within the agreed schema)
  without changing the crawler.
- **No double-fetch.** Bytes flow provider → ingestion component directly, not provider →
  crawler → ingestion.

Integrity holds end-to-end: the ingestion component indexes bytes only if they hash to the
`digest` the crawler verified against the provider's signed index.

---

## 9. Updates and re-processing

Everything hangs off **digests**, so updates need no notifications or webhooks:

1. The crawler periodically re-fetches the **manifest** (paced by `next_update`).
2. It compares `files[].digest` to the digest it last saw for the **index**.
   - **Unchanged** → nothing to do; stop.
   - **Changed** → fetch and verify the new index.
3. Within the new index, it compares each part's `digest` to what it last indexed.
   - **Unchanged parts** → skipped (no fetch, no re-index).
   - **Changed or new parts** → a fresh reference (URL + digest) is handed to the ingestion
     component (§7–§8), which fetches, verifies, and re-indexes.
4. **Version guard:** a record whose `version` is not greater than the indexed version is
   ignored, even if bytes differ — this prevents rollback/replay of stale catalogs.
5. **Retirement:** a record marked `RETIRED` (or dropped from the index) tells the ingestion
   component to remove that catalog.

Because change detection is "does the announced digest differ from what I hold," a provider
that republishes identical content (same bytes → same digest) triggers no work, and a provider
that changes one part triggers re-processing of only that part.

> **Publisher obligation:** when you change a catalog part, you **must** update its digest in
> the index, and you **must** update the index's digest in the manifest. If a digest doesn't
> match the file it points to, consumers will (correctly) reject the update and keep serving
> the old version. Keeping the chain consistent is the publisher's responsibility.

---

## 10. Publisher checklist

To participate, a provider must:

- [ ] Host a **manifest** at `/.well-known/dedi.json` with identity, public key(s), and a
      pointer to each catalog index (with the index's digest).
- [ ] Host one **catalog index** per registry, listing every catalog as a record with its
      parts, part URLs, and **part digests**.
- [ ] Host the **catalog part files** so that the served bytes hash to the announced digests.
- [ ] **Sign** the manifest and index (JCS canonicalization + JWS) with the key declared in the
      document, referenced by `kid`.
- [ ] **Register a DeDi record** binding domain + public key + manifest URL.
- [ ] For restricted catalogs: **verify signed pull requests** (resolving the caller's key via
      DeDi) and **issue short-lived, bearer-usable presigned links** (not IP/identity-bound).
- [ ] On every change: bump `version`, recompute the affected **part digest**, propagate it up
      to the **index digest** in the manifest, and update `updated_at` / `next_update`.

---

## 11. Security considerations

- **Verify before trust, at every hop.** Manifest signature → index digest + signature → part
  digest. A consumer never indexes a byte it hasn't checked against the chain.
- **Key rotation.** Publish the new key in the manifest and DeDi record (new `kid`), sign new
  documents with it, and retire the old `kid` once no live document references it.
- **Rollback / replay protection.** The monotonic `version` per record prevents an attacker (or
  a stale mirror) from replacing a catalog with an older signed copy.
- **Short presigned windows.** Keep presigned-link expiry short (1–2h). A leaked link dies
  quickly, and consumers can always re-request one.
- **Least disclosure in DeDi.** DeDi holds only identity, keys, and a pointer — never catalog
  data or private keys.
- **Freshness on pull requests.** Include and enforce `created`/`expires` on the consumer's
  request signature so a captured request can't be replayed.

---

## 12. Glossary

| Term | Meaning |
|------|---------|
| **DeDi** | Decentralized directory/registry. Holds one record per participant: identity, public key(s), and a pointer to their entry document. Exposes a lookup/search API. |
| **Manifest** | A provider's well-known entry document (`/.well-known/dedi.json`): identity, keys, and pointers (with digests) to catalog indexes. |
| **Catalog index** | A per-registry file listing catalogs as records, each with parts and part digests. |
| **Catalog part** | An actual catalog data file, referenced (with a digest) by the index. |
| **Digest** | A `sha-256:<hex>` content hash. Each layer announces the digest of the layer below it, forming a verifiable chain. |
| **`networkId` (registry)** | The network a whole catalog registry is published under, in DeDi and mirrored in the manifest. Coarse discovery filter. |
| **`visibility` `{ scope, networks }`** | Per-catalog access + membership. `scope` = `public` \| `restricted` (access); `networks` = the networks the catalog belongs to (and, when restricted, the allow-list). |
| **`networkIds` (consumer)** | The networks a consumer node belongs to, on its DeDi record. Providers use it to authorize restricted pulls. |
| **Consumer node** | A network participant that runs a crawler + ingestion component and holds its own registered key (e.g. a rider app). |
| **Crawler** | The consumer-side component that discovers, verifies the digest chain, and (for restricted catalogs) authenticates to obtain the presigned URL. It hands a verified reference (URL + digest) to the ingestion component; it does not fetch or parse catalog content. |
| **Ingestion component** | The consumer-side component that fetches the referenced URL, verifies the bytes against the digest, then parses and indexes the catalog. |
| **Reference** | What the crawler hands to the ingestion component: a URL (public or presigned) + the expected `digest` + catalog metadata. |
| **Presigned link** | A short-lived, self-authenticating URL a provider issues after verifying a signed pull request. Must be bearer-usable within its window (not IP/identity-bound). |
| **Proof** | A detached signature (JWS over a JCS-canonicalized document) proving a document was signed by the declared key. |
