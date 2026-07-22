import { defineConfig, type Connect } from 'vite'
import react from '@vitejs/plugin-react'
import http from 'node:http'
import { randomUUID } from 'node:crypto'

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

export default defineConfig({
  plugins: [react(), discoverApi()],
  server: { port: 5173, host: true },
  preview: { port: 4173, host: true },
})
