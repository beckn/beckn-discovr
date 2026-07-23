// Package model holds JSON views of the three DeDi files (manifest, index, catalog part).
// Only the fields the crawler reads are declared; unknown JSON keys are ignored by encoding/json.
// Mirrors the Java FeedModels: the DeDi wrapper uses snake_case (record_name, next_update); the
// inner catalog details use camelCase (catalogId, lastModified) — tagged explicitly either way.
package model

import "strings"

// Manifest — /.well-known/dedi.json (type: dedi-manifest).
type Manifest struct {
	Type   string    `json:"type"`
	Domain string    `json:"domain"`
	Name   string    `json:"name"`
	Files  []FileRef `json:"files"`
}

// FileRef — one registry the manifest advertises (each becomes an independently-crawled index).
type FileRef struct {
	Registry string `json:"registry"`
	URL      string `json:"url"`
	Digest   string `json:"digest"`
	State    string `json:"state"`
}

// IsLive reports whether this registry is live (DeDi state vocabulary).
func (f FileRef) IsLive() bool { return strings.EqualFold(f.State, "live") }

// Index — the registry index file (type: dedi-file).
type Index struct {
	Type       string    `json:"type"`
	Publisher  Publisher `json:"publisher"`
	Namespace  string    `json:"namespace"`
	NextUpdate string    `json:"next_update"`
	Records    []Record  `json:"records"`
}

// Publisher identifies who published the index; domain must match the manifest.
type Publisher struct {
	Domain string `json:"domain"`
}

// Record — one catalog entry in the index.
type Record struct {
	RecordName string  `json:"record_name"`
	Details    Details `json:"details"`
}

// Details — the catalog's metadata plus the list of part files that make it up.
type Details struct {
	CatalogID   string `json:"catalogId"`
	Version     int64  `json:"version"`
	CatalogType string `json:"catalogType"`
	Status      string `json:"status"`     // ACTIVE | RETIRED
	Visibility  any    `json:"visibility"` // "public" (string) OR { "networks": [...] } (object)
	UpdatedAt   string `json:"updatedAt"`
	Parts       []Part `json:"parts"`
}

// IsPublic is true only when visibility is exactly the string "public".
func (d Details) IsPublic() bool {
	s, ok := d.Visibility.(string)
	return ok && s == "public"
}

// IsRetired is true when status is RETIRED (case-insensitive).
func (d Details) IsRetired() bool { return strings.EqualFold(d.Status, "RETIRED") }

// IsActive is true only when status is exactly ACTIVE (case-insensitive).
func (d Details) IsActive() bool { return strings.EqualFold(d.Status, "ACTIVE") }

// Part — one catalog part file: its URL and the sha-256 the index declares for it.
type Part struct {
	URL          string `json:"url"`
	Digest       string `json:"digest"`
	LastModified string `json:"lastModified"`
}
