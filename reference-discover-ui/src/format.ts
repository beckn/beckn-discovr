import type { Address, Catalog, Offer, Price, Resource } from './types'

const CURRENCY_SYMBOL: Record<string, string> = { INR: '₹', USD: '$', EUR: '€', GBP: '£' }

/** Render a price defensively across the shapes a publisher might use. Undefined if absent. */
export function fmtPrice(p?: Price): string | undefined {
  if (p == null) return undefined
  if (typeof p === 'number') return p.toLocaleString()
  if (typeof p === 'string') return p.trim() || undefined
  const raw = p.value ?? p.amount
  if (raw == null || raw === '') return undefined
  const num = typeof raw === 'number' ? raw.toLocaleString() : String(raw)
  const code = p.currency ?? p.currencyCode
  if (!code) return num
  const sym = CURRENCY_SYMBOL[code.toUpperCase()]
  return sym ? `${sym}${num}` : `${code} ${num}`
}

/** "2026-03-04T00:00:00Z" → "4 Mar 2026" (undefined-safe, always returns a string or undefined). */
export function fmtDate(iso?: string): string | undefined {
  if (iso == null) return undefined
  const s = String(iso)
  if (!s.trim()) return undefined
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
}

/** A validity window like "4 Mar 2026 – 31 Mar 2026" / "until 31 Mar 2026". */
export function fmtValidity(v?: { startDate?: string; endDate?: string }): string | undefined {
  if (!v) return undefined
  const s = fmtDate(v.startDate)
  const e = fmtDate(v.endDate)
  if (s && e) return `${s} – ${e}`
  if (s) return `from ${s}`
  if (e) return `until ${e}`
  return undefined
}

/** Full single-line address. */
export function fmtAddress(a?: Address): string | undefined {
  if (!a) return undefined
  return (
    [a.streetAddress, a.addressLocality, a.addressRegion, a.postalCode, a.addressCountry]
      .filter(Boolean)
      .join(', ') || undefined
  )
}

/** Short "City, Region" for compact chips. */
export function fmtPlace(a?: Address): string | undefined {
  if (!a) return undefined
  return [a.addressLocality, a.addressRegion].filter(Boolean).join(', ') || undefined
}

export function coordsOf(loc?: { geo?: { coordinates?: number[] } }): string | undefined {
  const c = loc?.geo?.coordinates
  if (!Array.isArray(c) || c.length < 2) return undefined
  const lon = Number(c[0])
  const lat = Number(c[1])
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return undefined
  // GeoJSON is [lon, lat]; show as lat, lon for readability.
  return `${lat.toFixed(4)}, ${lon.toFixed(4)}`
}

/** Offers in a catalog that reference the given resource id. */
export function offersForResource(catalog: Catalog, resourceId: string): Offer[] {
  if (!Array.isArray(catalog?.offers)) return []
  return catalog.offers.filter(
    (o) => o && Array.isArray(o.resourceIds) && o.resourceIds.includes(resourceId),
  )
}

/** Resolve an offer's resourceIds to human names using the catalog's resources. */
export function resourceNames(catalog: Catalog, ids?: string[]): string[] {
  if (!Array.isArray(ids) || ids.length === 0) return []
  const resources = Array.isArray(catalog?.resources) ? catalog.resources : []
  const byId = new Map<string, Resource>(resources.filter((r) => r?.id).map((r) => [r.id, r]))
  return ids.map((id) => byId.get(id)?.descriptor?.name || id)
}

/** One resource lifted out of its catalog, with its catalog context and matching offers. */
export interface FlatItem {
  resource: Resource
  catalog: Catalog
  offers: Offer[]
}

/** Flatten every catalog's resources into a single result list (result-centric view). */
export function flatten(catalogs: Catalog[]): FlatItem[] {
  const items: FlatItem[] = []
  if (!Array.isArray(catalogs)) return items
  for (const c of catalogs) {
    if (!c) continue
    const resources = Array.isArray(c.resources) ? c.resources : []
    for (const r of resources) {
      if (!r) continue
      items.push({ resource: r, catalog: c, offers: offersForResource(c, r.id) })
    }
  }
  return items
}

export interface CatalogFacet {
  id: string
  name: string
  count: number
}

/** Catalog filter facets: one per catalog, with its resource count. */
export function catalogFacets(catalogs: Catalog[]): CatalogFacet[] {
  if (!Array.isArray(catalogs)) return []
  return catalogs
    .filter(Boolean)
    .map((c) => ({
      id: c.id,
      name: c.descriptor?.name || c.id || 'Untitled catalog',
      count: Array.isArray(c.resources) ? c.resources.length : 0,
    }))
}
