import { useState } from 'react'
import type { Catalog, Offer, Resource } from '../types'
import { coordsOf, fmtAddress, fmtPlace, fmtValidity, imageOf, resourceNames } from '../format'

interface Props {
  resource: Resource
  catalog: Catalog
  offers?: Offer[]
}

export default function ResourceCard({ resource, catalog, offers = [] }: Props) {
  const d = resource.descriptor
  const title = d?.name || resource.id || 'Untitled resource'
  const img = imageOf(d)
  const loc = Array.isArray(resource.availableAt) ? resource.availableAt[0] : undefined
  const place = fmtPlace(loc?.address)
  const address = fmtAddress(loc?.address)
  const coords = coordsOf(loc)
  const rating = resource.rating
  const seller = resource.provider?.descriptor?.name

  const catalogName = catalog.descriptor?.name || catalog.id
  const catalogProvider = catalog.provider?.descriptor?.name
  const catalogProviderPlace = fmtPlace(catalog.provider?.availableAt?.[0]?.address)
  const catalogValidity = fmtValidity(catalog.validity)

  const [open, setOpen] = useState(false)
  const [imgOk, setImgOk] = useState(true)

  return (
    <article className="resource-card">
      <div className="resource-media">
        {img && imgOk ? (
          <img src={img} alt={title} loading="lazy" onError={() => setImgOk(false)} />
        ) : (
          <div className="resource-media--empty">
            <svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true">
              <path
                fill="none"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M3 5.5h18v13H3zM3 16l5-5 4 4 3-3 6 6"
              />
              <circle cx="8.5" cy="9" r="1.4" fill="currentColor" />
            </svg>
            <span>No image</span>
          </div>
        )}
        {offers.length > 0 && (
          <span className="media-badge">
            {offers.length} {offers.length === 1 ? 'offer' : 'offers'}
          </span>
        )}
      </div>

      <div className="resource-body">
        <div className="resource-head">
          <h4 className="resource-name">{title}</h4>
          {rating?.ratingValue != null && (
            <span className="rating-pill">
              ★ {rating.ratingValue}
              {rating.bestRating != null && <span className="rating-out">/{rating.bestRating}</span>}
            </span>
          )}
        </div>

        {/* Always rendered so every collapsed card reserves the same space → uniform rows. */}
        <p className="resource-short">{d?.shortDesc || ''}</p>

        <div className="resource-tags">
          <span className="chip chip--catalog" title={catalogName}>
            <span className="chip-ico" aria-hidden="true">
              ▤
            </span>
            <span className="chip-label">{catalogName}</span>
          </span>
        </div>

        {/* One fixed line so every collapsed card is the same height regardless of content. */}
        <div className="resource-sub">{[seller, place].filter(Boolean).join(' · ') || ' '}</div>

        <button className="details-toggle" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
          {open ? 'Hide details' : 'Details'}
          <span className={`caret ${open ? 'up' : ''}`} aria-hidden="true">
            ▾
          </span>
        </button>

        {open && (
          <div className="resource-details">
            {d?.longDesc && <p className="detail-desc">{d.longDesc}</p>}

            <dl className="fact-list">
              {rating?.ratingCount != null && (
                <div className="fact">
                  <dt>Ratings</dt>
                  <dd>{rating.ratingCount.toLocaleString()}</dd>
                </div>
              )}
              {address && (
                <div className="fact">
                  <dt>Available at</dt>
                  <dd>{address}</dd>
                </div>
              )}
              {coords && (
                <div className="fact">
                  <dt>Geo</dt>
                  <dd className="mono">{coords}</dd>
                </div>
              )}
              <div className="fact">
                <dt>Resource ID</dt>
                <dd className="mono">{resource.id}</dd>
              </div>
            </dl>

            <div className="detail-group">
              <div className="detail-group-title">Catalog</div>
              <dl className="fact-list">
                <div className="fact">
                  <dt>Name</dt>
                  <dd>{catalogName}</dd>
                </div>
                {catalogProvider && (
                  <div className="fact">
                    <dt>Provider</dt>
                    <dd>
                      {catalogProvider}
                      {catalogProviderPlace ? ` · ${catalogProviderPlace}` : ''}
                    </dd>
                  </div>
                )}
                {catalogValidity && (
                  <div className="fact">
                    <dt>Valid</dt>
                    <dd>{catalogValidity}</dd>
                  </div>
                )}
                <div className="fact">
                  <dt>Catalog ID</dt>
                  <dd className="mono">{catalog.id}</dd>
                </div>
              </dl>
            </div>

            {offers.length > 0 && (
              <div className="detail-group">
                <div className="detail-group-title">
                  Offers ({offers.length})
                </div>
                <div className="resource-offers">
                  {offers.map((o) => {
                    // A bundle/combo offer covers more than one resource.
                    const applies = resourceNames(catalog, o.resourceIds)
                    const isBundle = applies.length > 1
                    return (
                      <div className="mini-offer" key={o.id}>
                        <span className="mini-offer-tag">%</span>
                        <div>
                          <div className="mini-offer-name">{o.descriptor?.name || o.id}</div>
                          {o.descriptor?.shortDesc && (
                            <div className="mini-offer-desc">{o.descriptor.shortDesc}</div>
                          )}
                          {isBundle && (
                            <div className="mini-offer-applies">
                              Bundle · applies to: {applies.join(', ')}
                            </div>
                          )}
                          {fmtValidity(o.validity) && (
                            <div className="mini-offer-valid">Valid {fmtValidity(o.validity)}</div>
                          )}
                        </div>
                      </div>
                    )
                  })}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </article>
  )
}
