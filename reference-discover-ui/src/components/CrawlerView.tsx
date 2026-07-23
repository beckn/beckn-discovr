import { Fragment, useEffect, useState, type FormEvent } from 'react'
import type { SourceRow, SyncFailure } from '../types'
import { listSources, registerSource, removeSource } from '../api'
import { fmtDate, relativeTime } from '../format'

// Map a sync status to its badge label + modifier class. null = never crawled yet.
const STATUS_META: Record<string, { label: string; cls: string }> = {
  success: { label: 'Synced', cls: 'success' },
  partial: { label: 'Partial', cls: 'partial' },
  failed: { label: 'Failed', cls: 'failed' },
}

/** Parse index_crawl_state.error_detail (a JSON array) defensively; [] on anything unexpected. */
function parseFailures(errorDetail?: string | null): SyncFailure[] {
  if (!errorDetail) return []
  try {
    const arr = JSON.parse(errorDetail)
    return Array.isArray(arr) ? (arr as SyncFailure[]) : []
  } catch {
    return []
  }
}

/** Just the coloured status badge. A null status = never crawled yet → Pending. */
function StatusBadge({ status }: { status?: string | null }) {
  const meta = status ? STATUS_META[status] : undefined
  if (!meta) return <span className="status-badge pending">Pending</span>
  return <span className={`status-badge ${meta.cls}`}>{meta.label}</span>
}

export default function CrawlerView() {
  const [sources, setSources] = useState<SourceRow[]>([])
  const [loadError, setLoadError] = useState<string | undefined>()
  const [url, setUrl] = useState('')
  const [name, setName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [formError, setFormError] = useState<string | undefined>()
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

  const toggle = (id: string) =>
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  async function refresh() {
    const { sources, error } = await listSources()
    setSources(sources)
    setLoadError(error)
  }

  // Load once, then poll so "Last synced" updates on its own as the crawler runs.
  useEffect(() => {
    refresh()
    const t = setInterval(refresh, 5000)
    return () => clearInterval(t)
  }, [])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setFormError(undefined)
    setSubmitting(true)
    try {
      const { ok, error } = await registerSource(url, name)
      if (!ok) {
        setFormError(error)
        return
      }
      setUrl('')
      setName('')
      await refresh()
    } finally {
      setSubmitting(false)
    }
  }

  async function remove(id: string) {
    await removeSource(id)
    await refresh()
  }

  return (
    <main className="crawler">
      <div className="crawler-inner">
        <h1 className="crawler-title">Register a provider</h1>
        <p className="crawler-sub">
          Add a provider’s <code>dedi.json</code>. The crawler checks it every minute and syncs any
          changed catalogs into Discover automatically.
        </p>

        <form className="register" onSubmit={submit}>
          <div className="register-fields">
            <input
              className="register-input register-input--url"
              type="text"
              placeholder="https://…/dedi.json"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              required
            />
            <input
              className="register-input register-input--name"
              type="text"
              placeholder="Provider name (optional)"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
            <button className="register-btn" type="submit" disabled={submitting}>
              {submitting ? 'Registering…' : 'Register'}
            </button>
          </div>
          {formError && <div className="register-error">{formError}</div>}
        </form>

        {loadError && (
          <div className="banner banner--error">
            <strong>Crawler unavailable:</strong> {loadError}
          </div>
        )}

        {sources.length === 0 && !loadError ? (
          <div className="empty-state">
            <p>No providers registered yet.</p>
            <p className="empty-hint">Add a dedi.json above to start syncing.</p>
          </div>
        ) : (
          <div className="providers-wrap">
            <table className="providers">
              <thead>
                <tr>
                  <th>Provider</th>
                  <th>dedi.json</th>
                  <th className="num">Catalogs</th>
                  <th>Source updated</th>
                  <th>Last synced</th>
                  <th>Status</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {sources.map((s) => {
                  const failures = parseFailures(s.errorDetail)
                  const open = expanded.has(s.id)
                  return (
                    <Fragment key={s.id}>
                      <tr>
                        <td className="prov-name">
                          {s.displayName || '—'}
                          {s.providerDomain && <span className="prov-domain">{s.providerDomain}</span>}
                        </td>
                        <td className="prov-url">
                          <a href={s.dediUrl} target="_blank" rel="noreferrer" title={s.dediUrl}>
                            {s.dediUrl}
                          </a>
                        </td>
                        <td className="num">{s.catalogs}</td>
                        <td>{s.sourceUpdated ? fmtDate(s.sourceUpdated) : '—'}</td>
                        <td>
                          <span className={`sync-dot ${s.lastSynced ? 'ok' : 'pending'}`} />
                          {s.lastSynced ? relativeTime(s.lastSynced) : 'pending…'}
                        </td>
                        <td className="prov-status">
                          <StatusBadge status={s.syncStatus} />
                          {failures.length > 0 && (
                            <button
                              className="issues-toggle"
                              onClick={() => toggle(s.id)}
                              aria-expanded={open}
                            >
                              {failures.length} issue{failures.length === 1 ? '' : 's'} {open ? '▴' : '▾'}
                            </button>
                          )}
                        </td>
                        <td className="prov-actions">
                          <button className="linkbtn-danger" onClick={() => remove(s.id)}>
                            Remove
                          </button>
                        </td>
                      </tr>
                      {open && failures.length > 0 && (
                        <tr className="prov-error-row">
                          <td colSpan={7}>
                            <ul className="prov-errors">
                              {failures.map((f, i) => (
                                <li key={i}>
                                  <code>{f.catalogId || '—'}</code>
                                  {f.httpStatus ? ` · HTTP ${f.httpStatus}` : ''} —{' '}
                                  {f.detail || 'unknown error'}
                                </li>
                              ))}
                            </ul>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </main>
  )
}
