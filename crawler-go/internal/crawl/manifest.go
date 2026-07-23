package crawl

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/beckn/beckn-discovr/crawler-go/internal/httpclient"
	"github.com/beckn/beckn-discovr/crawler-go/internal/model"
)

// Resolved is one registry the manifest advertises: who the provider is + where/what that index is.
type Resolved struct {
	Domain      string
	Name        string
	Registry    string
	IndexURL    string
	IndexDigest string
	State       string
}

// IsLive reports whether the index registry is live (DeDi state vocabulary).
func (r Resolved) IsLive() bool { return strings.EqualFold(r.State, "live") }

// ManifestResolver fetches a manifest and exposes every registry it advertises in files[].
// The manifest is tiny and re-read on the long (manifest-refresh) cadence. A provider may list
// several registries; each is crawled as an independent index, keyed by its own url in the state store.
type ManifestResolver struct {
	http *httpclient.Client
}

// NewManifestResolver builds a resolver over the shared HTTP client.
func NewManifestResolver(http *httpclient.Client) *ManifestResolver {
	return &ManifestResolver{http: http}
}

// Resolve fetches + parses the manifest at manifestURL; one Resolved per files[] registry.
func (m *ManifestResolver) Resolve(ctx context.Context, manifestURL string) ([]Resolved, error) {
	resp, err := m.http.Get(ctx, manifestURL)
	if err != nil {
		return nil, err
	}
	if resp.Status != 200 {
		return nil, fmt.Errorf("manifest GET %s returned HTTP %d", manifestURL, resp.Status)
	}
	var man model.Manifest
	if err := json.Unmarshal(resp.Body, &man); err != nil {
		return nil, fmt.Errorf("manifest %s parse: %w", manifestURL, err)
	}
	if len(man.Files) == 0 {
		return nil, fmt.Errorf("manifest %s has no files[] entry", manifestURL)
	}
	name := man.Name
	if strings.TrimSpace(name) == "" {
		name = man.Domain
	}
	resolved := make([]Resolved, 0, len(man.Files))
	for _, f := range man.Files {
		resolved = append(resolved, Resolved{
			Domain: man.Domain, Name: name, Registry: f.Registry,
			IndexURL: f.URL, IndexDigest: f.Digest, State: f.State,
		})
	}
	return resolved, nil
}
