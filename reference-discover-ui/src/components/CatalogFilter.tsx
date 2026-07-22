import type { CatalogFacet } from '../format'

interface Props {
  facets: CatalogFacet[]
  total: number
  active: string
  onChange: (id: string) => void
}

export default function CatalogFilter({ facets, total, active, onChange }: Props) {
  return (
    <div className="catalog-filter" role="tablist" aria-label="Filter by catalog">
      <button
        className={`filter-chip ${active === 'all' ? 'is-active' : ''}`}
        role="tab"
        aria-selected={active === 'all'}
        onClick={() => onChange('all')}
      >
        All <span className="filter-count">{total}</span>
      </button>
      {facets.map((f) => (
        <button
          key={f.id}
          className={`filter-chip ${active === f.id ? 'is-active' : ''}`}
          role="tab"
          aria-selected={active === f.id}
          onClick={() => onChange(f.id)}
          title={f.id}
        >
          {f.name} <span className="filter-count">{f.count}</span>
        </button>
      ))}
    </div>
  )
}
