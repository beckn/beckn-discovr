import { useState } from 'react'
import Header, { type View } from './components/Header'
import DiscoverView from './components/DiscoverView'
import CrawlerView from './components/CrawlerView'

export default function App() {
  const [view, setView] = useState<View>('discover')

  return (
    <div className="app">
      <Header view={view} onView={setView} />

      {view === 'discover' ? <DiscoverView /> : <CrawlerView />}

      <footer className="site-footer">
        NFH Reference Discover · powered by the Beckn Discovr discover API
      </footer>
    </div>
  )
}
