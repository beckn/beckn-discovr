// Shapes mirrored from the on_discover response (only the fields the UI renders).

export interface Descriptor {
  name?: string
  shortDesc?: string
  longDesc?: string
  mediaFile?: { uri: string; label?: string; mimeType?: string }[]
}

export interface Address {
  streetAddress?: string
  addressLocality?: string
  addressRegion?: string
  postalCode?: string
  addressCountry?: string
}

export interface Location {
  geo?: { type: string; coordinates: number[] }
  address?: Address
}

export interface Rating {
  ratingValue?: number
  ratingCount?: number
  bestRating?: number
  worstRating?: number
}

export interface Provider {
  id?: string
  descriptor?: Descriptor
  availableAt?: Location[]
}

/**
 * Price is not present in the current dataset, so we accept the shapes a publisher
 * is most likely to use and render defensively when one turns up.
 */
export type Price =
  | number
  | string
  | {
      value?: number | string
      amount?: number | string
      currency?: string
      currencyCode?: string
    }

export interface Resource {
  id: string
  descriptor?: Descriptor
  availableAt?: Location[]
  rating?: Rating
  provider?: Provider
  price?: Price
}

export interface Offer {
  id: string
  descriptor?: Descriptor
  validity?: { startDate?: string; endDate?: string }
  provider?: Provider
  resourceIds?: string[]
  price?: Price
}

export interface Catalog {
  id: string
  descriptor?: Descriptor
  provider?: Provider
  validity?: { startDate?: string; endDate?: string }
  isActive?: boolean
  resources?: Resource[]
  offers?: Offer[]
}

export interface DiscoverResult {
  message?: { catalogs?: Catalog[] }
  error?: string
}
