export type View = 'discover' | 'crawler'

interface Props {
  view: View
  onView: (v: View) => void
}

// Environment label: explicit VITE_APP_ENV wins; otherwise the Vite dev server
// reports "demo" and a production build shows nothing.
const envLabel = import.meta.env.VITE_APP_ENV ?? (import.meta.env.DEV ? 'demo' : undefined)

export default function Header({ view, onView }: Props) {
  const onCrawler = view === 'crawler'
  return (
    <header className="site-header">
      <div className="header-inner">
        <button className="brand-left" onClick={() => onView('discover')} title="Go to Discover">
          <img className="nfh-logo" src="/nfh-logo.svg" alt="NFH" />
        </button>

        <div className="header-right">
          <div className="brand-right">
            <div className="brand-lockup">
              <span className="brand-eyebrow">Reference</span>
              <span className="brand-title">Discover</span>
              {envLabel && <span className="env-badge">{envLabel}</span>}
            </div>
            <span className="brand-tagline">Decentralized catalog discovery</span>
          </div>

          <button
            className={`register-trigger ${onCrawler ? 'is-open' : ''}`}
            onClick={() => onView(onCrawler ? 'discover' : 'crawler')}
          >
            {onCrawler ? (
              'Close'
            ) : (
              <>
                <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
                  <path
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    d="M12 5v14M5 12h14"
                  />
                </svg>
                Register
              </>
            )}
          </button>
        </div>
      </div>
    </header>
  )
}
