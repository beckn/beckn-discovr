const DEV = import.meta.env.DEV
const CATALOG_API = DEV ? '/proxy/catalog' : (import.meta.env.VITE_CATALOG_API_URL || 'http://localhost:3000')
const DISCOVER_API = DEV ? '/proxy/discover' : (import.meta.env.VITE_DISCOVER_API_URL || 'http://localhost:8082')

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16)
  })
}

function buildContext(action = 'discover') {
  return {
    version: '2.0.0',
    action,
    timestamp: new Date().toISOString(),
    transaction_id: uuid(),
    message_id: uuid(),
    bap_id: 'bap.demo.beckn',
    bap_uri: 'http://localhost:5173',
    ttl: 'PT10M'
  }
}

export async function subscribe(payload) {
  const res = await fetch(`${CATALOG_API}/beckn/catalog/subscriptions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!res.ok) throw new Error(`Subscription failed: ${res.status} ${await res.text()}`)
  return res.json()
}

export async function publishCatalog(catalogPayload) {
  const res = await fetch(`${CATALOG_API}/beckn/catalog/publish`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(catalogPayload)
  })
  if (!res.ok) throw new Error(`Publish failed: ${res.status} ${await res.text()}`)
  return res.json()
}

export async function discoverByText(textSearch) {
  const body = {
    context: buildContext('discover'),
    message: {
      text_search: textSearch
    }
  }
  const res = await fetch(`${DISCOVER_API}/beckn/discover`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`Search failed (${res.status}): ${text}`)
  }
  return res.json()
}

export async function discoverSpatialText({ lat, lon, radiusMeters, textSearch, targets }) {
  const message = {
    spatial: [
      {
        op: 's_dwithin',
        targets,
        geometry: {
          type: 'Point',
          coordinates: [parseFloat(lon), parseFloat(lat)]
        },
        distanceMeters: parseInt(radiusMeters)
      }
    ]
  }
  if (textSearch && textSearch.trim()) {
    message.text_search = textSearch.trim()
  }
  const body = { context: buildContext('discover'), message }
  const res = await fetch(`${DISCOVER_API}/beckn/discover`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  if (!res.ok) throw new Error(`Discover failed: ${res.status} ${await res.text()}`)
  return res.json()
}
