import React, { useState } from 'react'
import SubscriptionForm from './components/bap/SubscriptionForm'
import SearchPanel from './components/bap/SearchPanel'
import PublishForm from './components/bpp/PublishForm'

const NAV = [
  { id: 'bap-subscribe', label: 'Subscribe', group: 'BAP', icon: '🔔' },
  { id: 'bap-search',    label: 'Discover',  group: 'BAP', icon: '🔍' },
  { id: 'bpp-publish',   label: 'Publish',   group: 'BPP', icon: '📦' }
]

export default function App() {
  const [active, setActive] = useState('bap-search')

  return (
    <div style={styles.root}>
      {/* Sidebar */}
      <aside style={styles.sidebar}>
        <div style={styles.logo}>
          <span style={styles.logoIcon}>⬡</span>
          <span style={styles.logoText}>Beckn Demo</span>
        </div>

        <nav style={styles.nav}>
          {['BAP', 'BPP'].map(group => (
            <div key={group} style={styles.navGroup}>
              <div style={styles.navGroupLabel}>{group}</div>
              {NAV.filter(n => n.group === group).map(item => (
                <button
                  key={item.id}
                  style={active === item.id ? styles.navItemActive : styles.navItem}
                  onClick={() => setActive(item.id)}
                >
                  <span style={styles.navIcon}>{item.icon}</span>
                  {item.label}
                </button>
              ))}
            </div>
          ))}
        </nav>

        <div style={styles.sidebarFooter}>
          <div style={styles.envBadge}>
            <span style={styles.dot} />
            localhost
          </div>
        </div>
      </aside>

      {/* Main content */}
      <main style={styles.main}>
        <div style={styles.header}>
          <div>
            <h1 style={styles.pageTitle}>{NAV.find(n => n.id === active)?.label}</h1>
            <p style={styles.pageSub}>{subtitle(active)}</p>
          </div>
        </div>
        <div style={styles.content}>
          {active === 'bap-subscribe' && <SubscriptionForm />}
          {active === 'bap-search'    && <SearchPanel />}
          {active === 'bpp-publish'   && <PublishForm />}
        </div>
      </main>
    </div>
  )
}

function subtitle(id) {
  switch (id) {
    case 'bap-subscribe': return 'Register as a BAP subscriber to receive catalog updates'
    case 'bap-search':    return 'Search the catalog by text, location, or both'
    case 'bpp-publish':   return 'Upload a catalog JSON file to publish items to the network'
    default: return ''
  }
}

const styles = {
  root: { display: 'flex', minHeight: '100vh' },

  sidebar: {
    width: 230, background: '#0f172a', color: '#fff',
    display: 'flex', flexDirection: 'column',
    position: 'fixed', top: 0, left: 0, bottom: 0, zIndex: 10
  },
  logo: {
    display: 'flex', alignItems: 'center', gap: 10,
    padding: '24px 20px 20px', borderBottom: '1px solid rgba(255,255,255,0.1)'
  },
  logoIcon: { fontSize: 22 },
  logoText: { fontSize: 17, fontWeight: 800, letterSpacing: -0.3, color: '#0284C7' },

  nav: { flex: 1, padding: '20px 12px', display: 'flex', flexDirection: 'column', gap: 28 },
  navGroup: { display: 'flex', flexDirection: 'column', gap: 2 },
  navGroupLabel: {
    fontSize: 11, fontWeight: 700, letterSpacing: 1.4,
    color: '#94a3b8', padding: '0 10px', marginBottom: 6, textTransform: 'uppercase'
  },
  navItem: {
    display: 'flex', alignItems: 'center', gap: 10,
    padding: '10px 12px', borderRadius: 8, border: 'none',
    background: 'transparent', color: '#cbd5e1',
    fontSize: 14, fontWeight: 500, textAlign: 'left', cursor: 'pointer'
  },
  navItemActive: {
    display: 'flex', alignItems: 'center', gap: 10,
    padding: '10px 12px', borderRadius: 8, border: 'none',
    background: '#0284C7', color: '#fff',
    fontSize: 14, fontWeight: 600, textAlign: 'left', cursor: 'pointer'
  },
  navIcon: { fontSize: 16 },

  sidebarFooter: {
    padding: '16px 20px', borderTop: '1px solid rgba(255,255,255,0.1)'
  },
  envBadge: {
    display: 'flex', alignItems: 'center', gap: 6,
    fontSize: 12, color: '#94a3b8'
  },
  dot: {
    width: 7, height: 7, borderRadius: '50%', background: '#10b981'
  },

  main: { marginLeft: 230, flex: 1, display: 'flex', flexDirection: 'column' },
  header: {
    padding: '28px 32px 20px', background: '#fff',
    borderBottom: '1px solid #e5e7eb'
  },
  pageTitle: { fontSize: 22, fontWeight: 700, color: '#0f172a' },
  pageSub: { fontSize: 13, color: '#64748b', marginTop: 4 },
  content: { padding: '28px 32px', flex: 1 }
}
