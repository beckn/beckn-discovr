package crawl

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/beckn/beckn-discovr/crawler-go/internal/digest"
	"github.com/beckn/beckn-discovr/crawler-go/internal/httpclient"
	"github.com/beckn/beckn-discovr/crawler-go/internal/model"
)

// IntegrityError is raised when the index fails integrity checks — the caller logs feedback and
// skips the provider. Ported from the Java IndexIntegrityException.
type IntegrityError struct{ msg string }

func (e *IntegrityError) Error() string { return e.msg }

// IndexResult is the parsed index plus the sha-256 of the exact bytes fetched (the change signal).
type IndexResult struct {
	Index  model.Index
	Digest string
}

// IndexPoller fetches the index and parses it, returning its parsed form plus the digest of the
// bytes fetched. Polled on its own short cadence, independently of the manifest. Change detection
// compares this freshly-computed digest against the crawler's stored digest (the caller decides).
// We still check publisher.domain matches the manifest's provider; full index-signature verification
// is deferred (§2 non-goals).
type IndexPoller struct {
	http *httpclient.Client
}

// NewIndexPoller builds a poller over the shared HTTP client.
func NewIndexPoller(http *httpclient.Client) *IndexPoller {
	return &IndexPoller{http: http}
}

// Fetch GETs the index, computes its digest, parses it, and verifies publisher.domain.
func (p *IndexPoller) Fetch(ctx context.Context, reg Resolved) (IndexResult, error) {
	resp, err := p.http.Get(ctx, reg.IndexURL)
	if err != nil {
		return IndexResult{}, err
	}
	if resp.Status != 200 {
		return IndexResult{}, fmt.Errorf("index GET %s returned HTTP %d", reg.IndexURL, resp.Status)
	}
	d := digest.SHA256(resp.Body)
	var idx model.Index
	if err := json.Unmarshal(resp.Body, &idx); err != nil {
		return IndexResult{}, fmt.Errorf("index %s parse: %w", reg.IndexURL, err)
	}
	pub := idx.Publisher.Domain
	if pub == "" || pub != reg.Domain {
		return IndexResult{}, &IntegrityError{
			msg: fmt.Sprintf("index publisher.domain '%s' != manifest domain '%s'", pub, reg.Domain),
		}
	}
	return IndexResult{Index: idx, Digest: d}, nil
}
