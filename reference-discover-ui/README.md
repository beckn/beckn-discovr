# NFH Reference Discover

A small reference UI over the Beckn Discovr **discover** API. It renders published
catalogs and their resources as cards and exposes a text search over them.

## How it works

```
Browser ──POST /api/discover──▶ Vite middleware ──GET /beckn/discover (body)──▶ Discovr discover job
   ▲                                                                                    │
   └───────────────────────── on_discover JSON (catalogs + resources) ◀────────────────┘
```

- The discover job's **synchronous** endpoint is `GET /beckn/discover` and it carries a
  JSON body. A browser's `fetch()` cannot put a body on a GET, so a tiny middleware
  (in `vite.config.ts`) relays the browser's `POST /api/discover` to that GET server-side.
- Text search is expressed as a SQL/JSON-path filter over `descriptor.name`,
  `descriptor.shortDesc` and `descriptor.longDesc` (case-insensitive `like_regex`).
  An empty box browses everything.

## Run

```bash
npm install
npm run dev        # http://localhost:5173
```

Point at a different discover job with an env var:

```bash
DISCOVER_URL=http://localhost:8082/beckn/discover npm run dev
```

(Default is `http://localhost:8082/beckn/discover` — the local Docker stack.)

## Notes

- The stack's Elasticsearch text path (`intent.textSearch`) is only populated when
  published resources carry a schema type (`resourceAttributes.@context` + `@type`).
  This UI intentionally drives the JSONPath path so it works against the data in
  PostgreSQL regardless of ES indexing.
- Auth/signatures are disabled in the POC stack, so no signing is performed here.
