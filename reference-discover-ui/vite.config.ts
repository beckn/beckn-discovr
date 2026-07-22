import { defineConfig, type Connect } from 'vite'
import react from '@vitejs/plugin-react'
import http from 'node:http'
import { randomUUID } from 'node:crypto'
import pg from 'pg'

/**
 * Where the Discovr discover job listens. The synchronous discover endpoint is a
 * GET that carries a JSON body — a browser's fetch() cannot send a body on GET,
 * so this middleware relays the browser's POST /api/discover to that GET server-side.
 */
const DISCOVER_URL = process.env.DISCOVER_URL || 'http://localhost:8082/beckn/discover'

/** Escape regex metacharacters so a user's query is matched literally by like_regex. */
function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** Build the SQL/JSON-path filter that drives the text search over descriptor fields. */
function buildExpression(q: string): string {
  const term = escapeRegex(q.trim())
  if (!term) {
    // Empty query → browse everything.
    return '$.catalogs[*].resources[*] ? (@.id != "")'
  }
  const fields = ['descriptor.name', 'descriptor.shortDesc', 'descriptor.longDesc']
  const clauses = fields.map((f) => `@.${f} like_regex "${term}" flag "i"`)
  return `$.catalogs[*].resources[*] ? (${clauses.join(' || ')})`
}

/** Perform the GET-with-body call to the discover job. Node http allows a body on GET. */
function callDiscover(payload: string): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const u = new URL(DISCOVER_URL)
    const req = http.request(
      {
        hostname: u.hostname,
        port: u.port || 80,
        path: u.pathname + u.search,
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(payload),
        },
      },
      (res) => {
        let data = ''
        res.on('data', (c) => (data += c))
        res.on('end', () => resolve({ status: res.statusCode || 0, body: data }))
      },
    )
    req.on('error', reject)
    req.write(payload)
    req.end()
  })
}

/** Vite plugin: serves POST /api/discover in both `dev` and `preview`. */
function discoverApi() {
  const handler: Connect.NextHandleFunction = (rawReq, res, next) => {
    const req = rawReq as http.IncomingMessage
    if (req.url !== '/api/discover' || req.method !== 'POST') return next()

    let body = ''
    req.on('data', (c) => (body += c))
    req.on('end', async () => {
      const send = (status: number, obj: unknown) => {
        res.statusCode = status
        res.setHeader('Content-Type', 'application/json')
        res.end(JSON.stringify(obj))
      }
      try {
        const { q } = body ? JSON.parse(body) : { q: '' }
        const envelope = {
          context: {
            action: 'discover',
            version: '2.0.0',
            transactionId: randomUUID(),
            messageId: randomUUID(),
            timestamp: new Date().toISOString(),
            ttl: 'PT30S',
          },
          message: {
            intent: { filters: { type: 'jsonpath', expression: buildExpression(String(q ?? '')) } },
          },
        }
        const { status, body: respBody } = await callDiscover(JSON.stringify(envelope))
        let parsed: any
        try {
          parsed = JSON.parse(respBody)
        } catch {
          return send(502, { error: 'Discover returned non-JSON', raw: respBody.slice(0, 500) })
        }
        // Surface NACKs (schema/auth errors) as a clean error the UI can show.
        if (parsed?.message?.status === 'NACK') {
          return send(status || 400, { error: parsed.message?.error?.message || 'NACK', nack: parsed })
        }
        return send(200, parsed)
      } catch (e: any) {
        return send(500, { error: e?.message || String(e) })
      }
    })
  }
  return {
    name: 'discover-api',
    configureServer(server: any) {
      server.middlewares.use(handler)
    },
    configurePreviewServer(server: any) {
      server.middlewares.use(handler)
    },
  }
}

// ── Crawler admin API ──────────────────────────────────────────────────────
// Reads/writes the crawler's Postgres directly (server-side only; the browser never
// sees credentials). Submitting a source = one INSERT into crawler_source; the crawler
// picks it up on its next poll. "Last synced" is derived from the crawl-state tables.
const pool = new pg.Pool({
  host: process.env.CRAWLER_DB_HOST || 'localhost',
  port: Number(process.env.CRAWLER_DB_PORT || 5434),
  database: process.env.CRAWLER_DB_NAME || 'catalog_db',
  user: process.env.CRAWLER_DB_USER || 'catalog_user',
  password: process.env.CRAWLER_DB_PASSWORD || 'catalog123',
  max: 4,
})

function hostOf(u?: string): string | null {
  if (!u) return null
  try {
    return new URL(u).host
  } catch {
    return null
  }
}

/**
 * Assemble the providers list. Joins crawl state to each source by the provider's DeDi identity
 * (crawler_source.provider_domain, stamped by the crawler after the first crawl). Exact — no host
 * guessing. provider_domain is null until the first successful crawl, so such a source shows as
 * "pending" with no counts.
 */
async function listSources() {
  const { rows } = await pool.query(`
    SELECT s.id,
           s.dedi_url,
           COALESCE(NULLIF(s.provider_name, ''), NULLIF(s.display_name, '')) AS name,
           s.provider_domain,
           s.created_at,
           GREATEST(MAX(i.last_seen_at), MAX(c.last_seen_at))                AS last_synced,
           MAX(c.source_updated_at)                                          AS source_updated,
           COUNT(DISTINCT c.catalog_id)                                     AS catalogs
      FROM crawler_source s
      LEFT JOIN index_crawl_state  i
             ON s.provider_domain IS NOT NULL AND i.provider_domain = s.provider_domain
      LEFT JOIN catalog_part_state c
             ON s.provider_domain IS NOT NULL AND c.provider_domain = s.provider_domain
     WHERE s.status = true
     GROUP BY s.id, s.dedi_url, name, s.provider_domain, s.created_at
     ORDER BY s.created_at
  `)

  return rows.map((r: any) => ({
    id: r.id,
    dediUrl: r.dedi_url,
    displayName: r.name,
    providerDomain: r.provider_domain,
    createdAt: r.created_at,
    catalogs: Number(r.catalogs) || 0,
    lastSynced: r.last_synced ? new Date(r.last_synced).toISOString() : null,
    sourceUpdated: r.source_updated ? new Date(r.source_updated).toISOString() : null,
  }))
}

function crawlerApi() {
  const handler: Connect.NextHandleFunction = (rawReq, res, next) => {
    const req = rawReq as http.IncomingMessage
    const url = req.url || ''
    if (!url.startsWith('/api/crawler/sources')) return next()

    const send = (status: number, obj: unknown) => {
      res.statusCode = status
      res.setHeader('Content-Type', 'application/json')
      res.end(JSON.stringify(obj))
    }

    // GET /api/crawler/sources — list providers with last-synced + counts.
    if (req.method === 'GET') {
      listSources()
        .then((sources) => send(200, { sources }))
        .catch((e) => send(500, { error: e?.message || String(e) }))
      return
    }

    // POST /api/crawler/sources — register a dedi.json (INSERT; re-enable if it existed).
    if (req.method === 'POST') {
      let body = ''
      req.on('data', (c) => (body += c))
      req.on('end', async () => {
        try {
          const { dediUrl, displayName } = body ? JSON.parse(body) : {}
          const trimmed = String(dediUrl ?? '').trim()
          if (!trimmed || !hostOf(trimmed)) {
            return send(400, { error: 'Enter a valid dedi.json URL (including https://).' })
          }
          const r = await pool.query(
            `INSERT INTO crawler_source (dedi_url, display_name, status)
               VALUES ($1, $2, true)
             ON CONFLICT (dedi_url)
               DO UPDATE SET status = true, display_name = EXCLUDED.display_name
             RETURNING id, dedi_url, display_name, created_at`,
            [trimmed, (displayName ?? '').trim() || null],
          )
          send(201, r.rows[0])
        } catch (e: any) {
          send(500, { error: e?.message || String(e) })
        }
      })
      return
    }

    // DELETE /api/crawler/sources/{id} — stop crawling (status=false).
    if (req.method === 'DELETE') {
      const id = url.split('/').pop()?.split('?')[0]
      pool
        .query(`UPDATE crawler_source SET status = false WHERE id = $1`, [id])
        .then(() => send(200, { id, removed: true }))
        .catch((e) => send(500, { error: e?.message || String(e) }))
      return
    }

    return next()
  }
  return {
    name: 'crawler-api',
    configureServer(server: any) {
      server.middlewares.use(handler)
    },
    configurePreviewServer(server: any) {
      server.middlewares.use(handler)
    },
  }
}

export default defineConfig({
  plugins: [react(), discoverApi(), crawlerApi()],
  server: { port: 5173, host: true },
  preview: { port: 4173, host: true },
})
