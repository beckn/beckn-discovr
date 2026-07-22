// Environment label: explicit VITE_APP_ENV wins; otherwise the Vite dev server
// reports "demo" and a production build shows nothing. So the badge reflects how
// the app is actually running, rather than being hardcoded.
const envLabel = import.meta.env.VITE_APP_ENV ?? (import.meta.env.DEV ? 'demo' : undefined)

export default function Header() {
  return (
    <header className="site-header">
      <div className="header-inner">
        <div className="brand-left">
          <img className="nfh-logo" src="/nfh-logo.svg" alt="NFH" />
        </div>
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
