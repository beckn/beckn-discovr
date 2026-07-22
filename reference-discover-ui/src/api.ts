import type { Catalog, DiscoverResult, SourceRow } from './types'

export interface SearchOutcome {
  catalogs: Catalog[]
  resourceCount: number
  error?: string
}

const EMPTY = (error: string): SearchOutcome => ({ catalogs: [], resourceCount: 0, error })

/** Ask the backend middleware to run a discover query. Never throws — always resolves. */
export async function search(q: string): Promise<SearchOutcome> {
  let res: Response
  try {
    res = await fetch('/api/discover', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ q }),
    })
  } catch {
    return EMPTY('Could not reach the discover service. Is the stack running on the configured port?')
  }

  let data: DiscoverResult
  try {
    data = await res.json()
  } catch {
    return EMPTY(`Discover returned an unreadable response (HTTP ${res.status}).`)
  }

  if (!res.ok || data?.error) {
    return EMPTY(data?.error || `Discover request failed (HTTP ${res.status}).`)
  }

  const catalogs = Array.isArray(data?.message?.catalogs) ? (data.message!.catalogs as Catalog[]) : []
  const resourceCount = catalogs.reduce(
    (n, c) => n + (Array.isArray(c?.resources) ? c.resources.length : 0),
    0,
  )
  return { catalogs, resourceCount }
}

// ── Crawler sources ─────────────────────────────────────────────────────────

export async function listSources(): Promise<{ sources: SourceRow[]; error?: string }> {
  try {
    const res = await fetch('/api/crawler/sources')
    const data = await res.json()
    if (!res.ok || data?.error) return { sources: [], error: data?.error || `HTTP ${res.status}` }
    return { sources: Array.isArray(data?.sources) ? data.sources : [] }
  } catch {
    return { sources: [], error: 'Could not reach the crawler service.' }
  }
}

export async function registerSource(
  dediUrl: string,
  displayName: string,
): Promise<{ ok: boolean; error?: string }> {
  try {
    const res = await fetch('/api/crawler/sources', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dediUrl, displayName }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) return { ok: false, error: data?.error || `HTTP ${res.status}` }
    return { ok: true }
  } catch {
    return { ok: false, error: 'Could not reach the crawler service.' }
  }
}

export async function removeSource(id: string): Promise<void> {
  try {
    await fetch(`/api/crawler/sources/${id}`, { method: 'DELETE' })
  } catch {
    /* best-effort */
  }
}
