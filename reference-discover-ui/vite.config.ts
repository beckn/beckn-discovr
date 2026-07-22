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

/** Assemble the providers list: each crawler_source row + last-synced/counts by host match. */
async function listSources() {
  const [sources, indexes, parts] = await Promise.all([
    pool.query(
      `SELECT id, dedi_url, display_name, created_at
         FROM crawler_source WHERE status = true ORDER BY created_at`,
    ),
    pool.query(`SELECT index_url, last_seen_at FROM index_crawl_state`),
    pool.query(`SELECT part_url, catalog_id, last_seen_at FROM catalog_part_state`),
  ])

  return sources.rows.map((s: any) => {
    const host = hostOf(s.dedi_url)
    const idxHits = indexes.rows.filter((r: any) => hostOf(r.index_url) === host)
    const partHits = parts.rows.filter((r: any) => hostOf(r.part_url) === host)
    const times = [...idxHits, ...partHits]
      .map((r: any) => r.last_seen_at)
      .filter(Boolean)
      .map((t: any) => new Date(t).getTime())
    const lastSynced = times.length ? new Date(Math.max(...times)).toISOString() : null
    const catalogs = new Set(partHits.map((r: any) => r.catalog_id)).size
    return {
      id: s.id,
      dediUrl: s.dedi_url,
      displayName: s.display_name,
      createdAt: s.created_at,
      catalogs,
      lastSynced,
    }
  })
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
