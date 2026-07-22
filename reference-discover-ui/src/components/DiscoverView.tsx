import { useMemo, useState } from 'react'
import SearchBar from './SearchBar'
import ResourceCard from './ResourceCard'
import CatalogFilter from './CatalogFilter'
import ErrorBoundary from './ErrorBoundary'
import { search, type SearchOutcome } from '../api'
import { catalogFacets, flatten } from '../format'

export default function DiscoverView() {
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<SearchOutcome | null>(null)
  const [lastQuery, setLastQuery] = useState('')
  const [searched, setSearched] = useState(false)
  const [activeCatalog, setActiveCatalog] = useState<string>('all')

  async function run(q: string) {
    setLoading(true)
    setLastQuery(q)
    setSearched(true)
    setActiveCatalog('all')
    try {
      setResult(await search(q))
    } finally {
      setLoading(false)
    }
  }

  const catalogs = result?.catalogs ?? []
  const items = useMemo(() => flatten(catalogs), [catalogs])
  const facets = useMemo(() => catalogFacets(catalogs), [catalogs])
  const visible = activeCatalog === 'all' ? items : items.filter((i) => i.catalog.id === activeCatalog)

  return (
    <>
      <div className="hero">
        <div className="hero-inner">
          <h1 className="hero-title">What are you looking for?</h1>
          <p className="hero-sub">Search products and offers from every store in the network.</p>
          <SearchBar onSearch={run} loading={loading} />
        </div>
      </div>

      <main className="results">
        <div className="results-inner">
          {!searched && (
            <div className="empty-state">
              <p>Search to discover catalogs and resources.</p>
              <p className="empty-hint">
                Try “coffee” or “gold” — or leave the box blank and hit Search to browse all.
              </p>
            </div>
          )}

          {searched && result && !result.error && (
            <div className="results-summary">
              {loading ? (
                'Searching…'
              ) : (
                <>
                  <strong>{items.length}</strong> {items.length === 1 ? 'resource' : 'resources'}{' '}
                  across <strong>{facets.length}</strong>{' '}
                  {facets.length === 1 ? 'catalog' : 'catalogs'}
                  {lastQuery ? (
                    <>
                      {' '}
                      for “<em>{lastQuery}</em>”
                    </>
                  ) : (
                    ' (browsing all)'
                  )}
                </>
              )}
            </div>
          )}

          {searched && !loading && !result?.error && facets.length > 1 && (
            <CatalogFilter
              facets={facets}
              total={items.length}
              active={activeCatalog}
              onChange={setActiveCatalog}
            />
          )}

          {result?.error && (
            <div className="banner banner--error">
              <strong>Discover error:</strong> {result.error}
            </div>
          )}

          {searched && !loading && !result?.error && visible.length === 0 && (
            <div className="empty-state">
              <p>No results{lastQuery ? ` for “${lastQuery}”` : ''}.</p>
              <p className="empty-hint">Try a different term, or clear the box to browse all.</p>
            </div>
          )}

          {visible.length > 0 && (
            <ErrorBoundary>
              <div className="resource-grid">
                {visible.map(({ resource, catalog, offers }, i) => (
                  <ResourceCard
                    key={`${catalog?.id ?? 'catalog'}:${resource?.id ?? i}`}
                    resource={resource}
                    catalog={catalog}
                    offers={offers}
                  />
                ))}
              </div>
            </ErrorBoundary>
          )}
        </div>
      </main>
    </>
  )
}
