// Types mirror the Beckn v2.0 schema (protocol-specifications-v2/api/v2.0.0/beckn.yaml):
// Catalog / Resource / Offer / Provider / Descriptor / Location / MediaFile / TimePeriod.
//
// The domain-specific *Attributes bags (resourceAttributes, offerAttributes,
// providerAttributes, considerationAttributes / PriceSpecification) are DYNAMIC per domain
// and are intentionally NOT modelled or rendered here — the UI stays generic across domains.

export interface MediaFile {
  uri?: string
  label?: string
  mimeType?: string
}

// beckn.yaml Descriptor
export interface Descriptor {
  code?: string
  name?: string
  shortDesc?: string
  longDesc?: string
  thumbnailImage?: string
  mediaFile?: MediaFile[]
}

// beckn.yaml Address
export interface Address {
  streetAddress?: string
  addressLocality?: string
  addressRegion?: string
  postalCode?: string
  addressCountry?: string
}

// beckn.yaml GeoJSONGeometry
export interface GeoJSONGeometry {
  type?: string
  coordinates?: number[]
}

// beckn.yaml Location = geo (+ optional address)
export interface Location {
  geo?: GeoJSONGeometry
  address?: Address
}

// beckn.yaml TimePeriod (catalog / offer validity)
export interface TimePeriod {
  startDate?: string
  endDate?: string
}

// beckn.yaml Provider (providerAttributes omitted — dynamic)
export interface Provider {
  id?: string
  descriptor?: Descriptor
  availableAt?: Location[]
}

// beckn.yaml Rating is not a core Resource field; Discovr surfaces it as an extension.
export interface Rating {
  ratingValue?: number
  ratingCount?: number
  bestRating?: number
  worstRating?: number
}

// beckn.yaml Resource = id + descriptor (+ resourceAttributes, dynamic — not modelled).
// provider / availableAt / rating / category are Discovr's extended resource fields, present
// in on_discover data; rendered only when available.
export interface Resource {
  id: string
  descriptor?: Descriptor
  provider?: Provider
  availableAt?: Location[]
  rating?: Rating
  category?: string
}

// beckn.yaml Offer (addOns / considerations / offerAttributes omitted — dynamic).
export interface Offer {
  id: string
  descriptor?: Descriptor
  provider?: Provider
  resourceIds?: string[]
  validity?: TimePeriod
}

// beckn.yaml Catalog
export interface Catalog {
  id: string
  descriptor?: Descriptor
  provider?: Provider
  resources?: Resource[]
  offers?: Offer[]
  validity?: TimePeriod
  isActive?: boolean
  bppId?: string
  bppUri?: string
}

export interface DiscoverResult {
  message?: { catalogs?: Catalog[] }
  error?: string
}

// A registered crawler source, as returned by /api/crawler/sources (not a Beckn schema).
export interface SourceRow {
  id: string
  dediUrl: string
  displayName?: string | null
  providerDomain?: string | null
  createdAt?: string
  catalogs: number
  lastSynced: string | null
  sourceUpdated: string | null
}
