import React, { useState } from 'react'

const FALLBACK_IMAGE = 'https://images.unsplash.com/photo-1461023058943-07fcbe16d735?w=400&q=80'

// Maps well-known item names to public product images
const IMAGE_MAP = {
  'bru': 'https://www.jiomart.com/images/product/original/599173064/599173064.jpg',
  'nescafe': 'https://m.media-amazon.com/images/S/aplus-media/vc/bb6a0196-cad0-4395-b85e-134ef725c0f7._CR0,0,1251,1251_PT0_SX300__.png',
  'continental': 'https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?w=400&q=80',
  'davidoff': 'https://images.unsplash.com/photo-1447933601403-0c6688de566e?w=400&q=80',
  'wagh bakri': 'https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?w=400&q=80',
  'lipton': 'https://images.unsplash.com/photo-1564890369478-c89ca6d9cde9?w=400&q=80',
  'tata tea': 'https://images.unsplash.com/photo-1576092768241-dec231879fc3?w=400&q=80',
  'green tea': 'https://images.unsplash.com/photo-1564890369478-c89ca6d9cde9?w=400&q=80',
  'tea': 'https://images.unsplash.com/photo-1576092768241-dec231879fc3?w=400&q=80',
  'coffee': 'https://images.unsplash.com/photo-1461023058943-07fcbe16d735?w=400&q=80',
}

function resolveImage(item) {
  const descriptor = item['beckn:descriptor'] || item.descriptor || {}
  const images = descriptor['schema:image'] || descriptor['beckn:images'] || descriptor.images
  if (images?.[0]) return typeof images[0] === 'string' ? images[0] : images[0].url || FALLBACK_IMAGE
  const name = (descriptor['schema:name'] || descriptor.name || '').toLowerCase()
  for (const [key, url] of Object.entries(IMAGE_MAP)) {
    if (name.includes(key)) return url
  }
  const attrs = item['beckn:itemAttributes'] || item.itemAttributes || {}
  const category = (attrs.category || '').toLowerCase()
  return IMAGE_MAP[category] || FALLBACK_IMAGE
}

function StarRating({ value }) {
  const stars = Math.round(value || 0)
  return (
    <span style={styles.stars}>
      {'★'.repeat(stars)}{'☆'.repeat(5 - stars)}
      <span style={styles.ratingVal}> {value?.toFixed(1)}</span>
    </span>
  )
}

export default function ItemCard({ item, catalogId, bppId }) {
  const [imgError, setImgError] = useState(false)
  const imageUrl = imgError ? FALLBACK_IMAGE : resolveImage(item)

  const descriptor  = item['beckn:descriptor']  || item.descriptor  || {}
  const rating      = item['beckn:rating']       || item.rating
  const provider    = item['beckn:provider']     || item.provider
  const attrs       = item['beckn:itemAttributes'] || item.itemAttributes
  const availableAt = item['beckn:availableAt']  || item.availableAt || []
  const category    = item['beckn:category']     || item.category
  const itemId      = item['beckn:id']           || item.id

  const name      = descriptor['schema:name']    || descriptor.name
  const shortDesc = descriptor['beckn:shortDesc'] || descriptor.shortDesc
  const categoryCode = category?.code

  const location = availableAt[0]
  const address  = location?.address
  const locationText = address?.addressLocality
    ? `${address.addressLocality}, ${address.addressRegion}`
    : location?.geo?.coordinates
      ? `${location.geo.coordinates[1].toFixed(4)}, ${location.geo.coordinates[0].toFixed(4)}`
      : null

  const providerName = provider?.['beckn:descriptor']?.['schema:name']
    || provider?.descriptor?.name
    || bppId

  const ratingValue = rating?.['beckn:ratingValue'] || rating?.ratingValue
  const qty = attrs?.netQuantityOrMeasureInPackage
  const diet = attrs?.dietaryClassification

  return (
    <div style={styles.card}>
      <div style={styles.imageWrap}>
        <img src={imageUrl} alt={name} style={styles.image} onError={() => setImgError(true)} />
        {categoryCode && <span style={styles.badge}>{categoryCode}</span>}
      </div>
      <div style={styles.body}>
        <div style={styles.provider}>{providerName}</div>
        <h4 style={styles.name}>{name || itemId}</h4>
        <p style={styles.desc}>{shortDesc}</p>
        {ratingValue != null && <StarRating value={ratingValue} />}
        {locationText && (
          <div style={styles.location}>
            <span style={styles.pin}>📍</span> {locationText}
          </div>
        )}
        {(qty || diet) && (
          <div style={styles.attrs}>
            {qty && <Tag label={`${qty.unitQuantity}${qty.unitText}`} />}
            {diet && <Tag label={diet} color="#d1fae5" text="#065f46" />}
          </div>
        )}
      </div>
    </div>
  )
}

function Tag({ label, color = '#f0f9ff', text = '#0284C7' }) {
  return (
    <span style={{ ...styles.tag, background: color, color: text }}>{label}</span>
  )
}

const styles = {
  card: {
    background: '#fff', borderRadius: 12, overflow: 'hidden',
    boxShadow: '0 1px 4px rgba(0,0,0,0.08)', transition: 'transform 0.15s',
    display: 'flex', flexDirection: 'column'
  },
  imageWrap: { position: 'relative', height: 180, background: '#f9fafb', overflow: 'hidden' },
  image: { width: '100%', height: '100%', objectFit: 'cover' },
  badge: {
    position: 'absolute', top: 8, right: 8,
    background: 'rgba(79,70,229,0.9)', color: '#fff',
    fontSize: 10, fontWeight: 600, padding: '3px 8px', borderRadius: 20
  },
  body: { padding: 16, display: 'flex', flexDirection: 'column', gap: 6, flex: 1 },
  provider: { fontSize: 11, color: '#6b7280', fontWeight: 500, textTransform: 'uppercase', letterSpacing: 0.5 },
  name: { fontSize: 15, fontWeight: 600, color: '#0f172a', lineHeight: 1.3 },
  desc: { fontSize: 12, color: '#6b7280', lineHeight: 1.5 },
  stars: { fontSize: 13, color: '#f59e0b' },
  ratingVal: { fontSize: 12, color: '#6b7280' },
  location: { fontSize: 12, color: '#0284C7', display: 'flex', alignItems: 'center', gap: 4 },
  pin: { fontSize: 12 },
  attrs: { display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 4 },
  tag: { fontSize: 11, fontWeight: 500, padding: '2px 8px', borderRadius: 20 }
}
