export type View = 'discover' | 'crawler'

interface Props {
  view: View
  onView: (v: View) => void
}

// Environment label: explicit VITE_APP_ENV wins; otherwise the Vite dev server
// reports "demo" and a production build shows nothing.
const envLabel = import.meta.env.VITE_APP_ENV ?? (import.meta.env.DEV ? 'demo' : undefined)

export default function Header({ view, onView }: Props) {
  return (
    <header className="site-header">
      <div className="header-inner">
        <div className="brand-left">
          <img className="nfh-logo" src="/nfh-logo.svg" alt="NFH" />
        </div>

        <nav className="site-nav">
          <button
            className={`nav-link ${view === 'discover' ? 'is-active' : ''}`}
            onClick={() => onView('discover')}
          >
            Discover
          </button>
          <button
            className={`nav-link ${view === 'crawler' ? 'is-active' : ''}`}
            onClick={() => onView('crawler')}
          >
            Crawler
          </button>
        </nav>

        <div className="brand-right">
          <div className="brand-lockup">
            <span className="brand-eyebrow">Reference</span>
            <span className="brand-title">Discover</span>
            {envLabel && <span className="env-badge">{envLabel}</span>}
          </div>
          <span className="brand-tagline">Decentralized catalog discovery</span>
        </div>
      </div>
    </header>
  )
}
