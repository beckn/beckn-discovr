package crawl

import (
	"context"
	"fmt"

	"github.com/beckn/beckn-discovr/crawler-go/internal/digest"
	"github.com/beckn/beckn-discovr/crawler-go/internal/httpclient"
)

// DigestMismatchError is raised when a part's bytes don't match its announced digest — a hard
// reject (never index unverified bytes, design doc §5.7). Ported from Fetcher.DigestMismatchException.
type DigestMismatchError struct{ msg string }

func (e *DigestMismatchError) Error() string { return e.msg }

// Fetcher GETs a catalog part and verifies its bytes against the digest the index published.
type Fetcher struct {
	http *httpclient.Client
}

// NewFetcher builds a Fetcher over the shared HTTP client.
func NewFetcher(http *httpclient.Client) *Fetcher { return &Fetcher{http: http} }

// FetchVerified returns the verified raw bytes of the part (safe to push).
func (f *Fetcher) FetchVerified(ctx context.Context, partURL, expectedDigest string) ([]byte, error) {
	resp, err := f.http.Get(ctx, partURL)
	if err != nil {
		return nil, err
	}
	if resp.Status != 200 {
		return nil, fmt.Errorf("part GET %s returned HTTP %d", partURL, resp.Status)
	}
	if !digest.Matches(resp.Body, expectedDigest) {
		return nil, &DigestMismatchError{
			msg: fmt.Sprintf("expected %s got %s", expectedDigest, digest.SHA256(resp.Body)),
		}
	}
	return resp.Body, nil
}
