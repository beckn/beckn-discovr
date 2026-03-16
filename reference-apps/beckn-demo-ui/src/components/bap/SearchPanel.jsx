import React, { useState } from 'react'
import { discoverByText, discoverSpatialText } from '../../api/client'
import ItemCard from './ItemCard'

const DEFAULT_TARGETS = '$.catalogs[*].beckn:items[*].beckn:availableAt[*].geo'

export default function SearchPanel() {
  const [textSearch, setTextSearch]   = useState('')
  const [spatialOpen, setSpatialOpen] = useState(false)
  const [lat, setLat]                 = useState('')
  const [lon, setLon]                 = useState('')
  const [radius, setRadius]           = useState('5000')
  const [targets, setTargets]         = useState(DEFAULT_TARGETS)
  const [loading, setLoading]         = useState(false)
  const [error, setError]             = useState(null)
  const [results, setResults]         = useState(null)

  async function handleSearch(e) {
    e.preventDefault()
    setLoading(true)
    setError(null)
    setResults(null)
    try {
      let res
      if (spatialOpen && lat && lon) {
        res = await discoverSpatialText({ lat, lon, radiusMeters: radius, textSearch, targets })
      } else {
        res = await discoverByText(textSearch)
      }
      setResults(res)
    } catch (err) {
      if (err.message.includes('Failed to fetch') || err.message.includes('NetworkError')) {
        setError('Cannot reach the discover service. Make sure catalog-discover-job is running on port 8082.')
      } else {
        setError(err.message)
      }
    } finally {
      setLoading(false)
    }
  }

  const items = extractItems(results)

  return (
    <div style={styles.wrap}>
      {/* Left — search form */}
      <div style={styles.formCol}>
      <div style={styles.card}>
        <h3 style={styles.cardTitle}>Discover Catalog</h3>

        <form onSubmit={handleSearch} style={styles.form}>

          {/* Text search — always visible */}
          <div style={styles.field}>
            <label style={styles.label}>Text Search</label>
            <input
              style={styles.input}
              placeholder="e.g. coffee powder"
              value={textSearch}
              onChange={e => setTextSearch(e.target.value)}
              required={!spatialOpen}
            />
          </div>

          {/* Spatial — collapsible */}
          <div style={styles.spatialSection}>
            <button
              type="button"
              style={styles.collapseToggle}
              onClick={() => setSpatialOpen(o => !o)}
            >
              <span style={styles.collapseIcon}>{spatialOpen ? '▾' : '▸'}</span>
              <span>Spatial Filter</span>
              {spatialOpen && <span style={styles.activePill}>active</span>}
            </button>

            {spatialOpen && (
              <div style={styles.spatialBody}>
                <div style={styles.row}>
                  <div style={styles.field}>
                    <label style={styles.label}>Longitude</label>
                    <input
                      style={styles.input}
                      placeholder="e.g. 76.6460"
                      value={lon}
                      onChange={e => setLon(e.target.value)}
                      required={spatialOpen}
                      type="number"
                      step="any"
                    />
                  </div>
                  <div style={styles.field}>
                    <label style={styles.label}>Latitude</label>
                    <input
                      style={styles.input}
                      placeholder="e.g. 12.3037"
                      value={lat}
                      onChange={e => setLat(e.target.value)}
                      required={spatialOpen}
                      type="number"
                      step="any"
                    />
                  </div>
                </div>
                <div style={styles.field}>
                  <label style={styles.label}>Radius (meters)</label>
                  <input
                    style={styles.input}
                    placeholder="e.g. 5000"
                    value={radius}
                    onChange={e => setRadius(e.target.value)}
                    required={spatialOpen}
                    type="number"
                    min="100"
                  />
                </div>
                <div style={styles.field}>
                  <label style={styles.label}>Targets Path</label>
                  <input
                    style={styles.inputMono}
                    value={targets}
                    onChange={e => setTargets(e.target.value)}
                    required={spatialOpen}
                  />
                </div>
              </div>
            )}
          </div>

          <button type="submit" disabled={loading} style={styles.btn}>
            {loading ? 'Searching…' : spatialOpen ? 'Search (Spatial + Text)' : 'Search'}
          </button>
        </form>

        {error && <div style={styles.alertError}>{error}</div>}
      </div>
      </div>

      {/* Right — results */}
      <div style={styles.resultsCol}>
        {results === null ? (
          <div style={styles.emptyState}>
            <span style={styles.emptyIcon}>🔍</span>
            <p style={styles.emptyText}>Results will appear here</p>
          </div>
        ) : items.length === 0 ? (
          <div style={styles.emptyState}>
            <span style={styles.emptyIcon}>😔</span>
            <p style={styles.emptyText}>No items found</p>
          </div>
        ) : (
          <>
            <div style={styles.resultsHeader}>
              <span style={styles.resultsTitle}>Results</span>
              <span style={styles.resultCount}>{items.length} item{items.length !== 1 ? 's' : ''}</span>
            </div>
            <div style={styles.grid}>
              {items.map(({ item, catalogId, bppId }) => (
                <ItemCard key={item.id || Math.random()} item={item} catalogId={catalogId} bppId={bppId} />
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function extractItems(response) {
  if (!response) return []
  const catalogs = response.message?.catalogs || response.catalogs || []
  const items = []
  for (const catalog of catalogs) {
    const catalogId = catalog.id
    const bppId = catalog.bppId
    const itemList = catalog['beckn:items'] || catalog.items || []
    for (const item of itemList) {
      items.push({ item, catalogId, bppId })
    }
  }
  return items
}

const styles = {
  wrap: { display: 'flex', gap: 24, alignItems: 'flex-start' },
  formCol: { width: '50%', flexShrink: 0 },
  resultsCol: { flex: 1, minWidth: 0 },
  card: { background: '#fff', borderRadius: 12, padding: 24, boxShadow: '0 1px 4px rgba(0,0,0,0.08)' },
  cardTitle: { fontSize: 16, fontWeight: 600, marginBottom: 20, color: '#0f172a' },
  form: { display: 'flex', flexDirection: 'column', gap: 16 },

  field: { display: 'flex', flexDirection: 'column', gap: 4 },
  label: { fontSize: 12, fontWeight: 500, color: '#6b7280' },
  input: { padding: '9px 12px', border: '1px solid #e5e7eb', borderRadius: 8, fontSize: 13, outline: 'none' },
  inputMono: { padding: '9px 12px', border: '1px solid #e5e7eb', borderRadius: 8, fontSize: 12, outline: 'none', fontFamily: 'monospace', color: '#374151' },
  row: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 },

  spatialSection: { border: '1px solid #e5e7eb', borderRadius: 10, overflow: 'hidden' },
  collapseToggle: {
    width: '100%', display: 'flex', alignItems: 'center', gap: 8,
    padding: '10px 14px', background: '#f9fafb', border: 'none',
    fontSize: 13, fontWeight: 500, color: '#374151', cursor: 'pointer', textAlign: 'left'
  },
  collapseIcon: { fontSize: 11, color: '#6b7280' },
  activePill: {
    marginLeft: 'auto', fontSize: 10, fontWeight: 600,
    background: '#f0f9ff', color: '#0284C7',
    padding: '2px 8px', borderRadius: 20
  },
  spatialBody: { padding: '16px 14px', display: 'flex', flexDirection: 'column', gap: 12, background: '#fff' },

  btn: { padding: '11px 20px', background: '#0284C7', color: '#fff', border: 'none', borderRadius: 8, fontWeight: 600, fontSize: 14 },

  alertError: { marginTop: 4, padding: '10px 14px', background: '#fee2e2', color: '#991b1b', borderRadius: 8, fontSize: 13 },

  emptyState: { display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: 240, gap: 12, background: '#fff', borderRadius: 12, boxShadow: '0 1px 4px rgba(0,0,0,0.06)' },
  emptyIcon: { fontSize: 36 },
  emptyText: { fontSize: 14, color: '#9ca3af' },
  resultsHeader: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 },
  resultsTitle: { fontSize: 16, fontWeight: 600, color: '#0f172a' },
  resultCount: { fontSize: 13, color: '#6b7280', background: '#f3f4f6', padding: '3px 10px', borderRadius: 20 },
  grid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 16 }
}
