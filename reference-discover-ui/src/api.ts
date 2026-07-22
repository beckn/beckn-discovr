import type { Catalog, DiscoverResult } from './types'

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
