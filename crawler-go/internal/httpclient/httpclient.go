// Package httpclient is a thin GET/POST wrapper over net/http, mirroring the Java CrawlerHttpClient.
//
// It enforces a per-request timeout and a byte cap on fetched bodies, and optionally appends a
// unique ?cb= to GETs to defeat a CDN cache in front of the bucket. No caching-header logic — the
// digest chain is the authoritative change signal (design doc §5.6). The dialer forces IPv4 to
// match the Java run (some container networks have broken IPv6 egress → hangs on AAAA), and idle
// keep-alive sockets are evicted after 30s so a CDN/NAT-dropped connection is never reused.
package httpclient

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"time"
)

// Response is a fetched body plus its ETag (may be empty — some hosts send none).
type Response struct {
	Status int
	Body   []byte
	ETag   string
}

// Client wraps net/http with the crawler's fetch policy.
type Client struct {
	http      *http.Client
	maxBytes  int64
	cacheBust bool
	cbSeq     atomic.Uint64
}

// New builds a Client. timeout bounds each request, maxBytes caps a fetched body, and cacheBust
// toggles the ?cb= query param on GETs.
func New(timeout time.Duration, maxBytes int64, cacheBust bool) *Client {
	transport := &http.Transport{
		DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
			// Force IPv4 — mirrors -Djava.net.preferIPv4Stack=true.
			return (&net.Dialer{Timeout: timeout}).DialContext(ctx, "tcp4", addr)
		},
		IdleConnTimeout:   30 * time.Second, // mirrors -Djdk.httpclient.keepalive.timeout=30
		ForceAttemptHTTP2: true,
	}
	return &Client{
		http:      &http.Client{Timeout: timeout, Transport: transport},
		maxBytes:  maxBytes,
		cacheBust: cacheBust,
	}
}

// Get fetches the URL as raw bytes, rejecting a body larger than the configured cap.
func (c *Client) Get(ctx context.Context, url string) (Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.withCacheBuster(url), nil)
	if err != nil {
		return Response{}, err
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return Response{}, err
	}
	defer resp.Body.Close()

	// Read at most maxBytes+1 so an over-cap body is detected without buffering it all.
	body, err := io.ReadAll(io.LimitReader(resp.Body, c.maxBytes+1))
	if err != nil {
		return Response{}, err
	}
	if int64(len(body)) > c.maxBytes {
		return Response{}, fmt.Errorf("response body exceeds cap %d bytes for %s", c.maxBytes, url)
	}
	return Response{Status: resp.StatusCode, Body: body, ETag: resp.Header.Get("ETag")}, nil
}

// PostJSON posts a JSON body and returns the status + response bytes (used for /catalog/push).
// No cache-buster — the push endpoint is our own service.
func (c *Client) PostJSON(ctx context.Context, url, jsonBody string) (Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, strings.NewReader(jsonBody))
	if err != nil {
		return Response{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return Response{}, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return Response{}, err
	}
	return Response{Status: resp.StatusCode, Body: body}, nil
}

// withCacheBuster appends a unique cb= param when cache-busting is on. Digests are computed over
// the body (not the URL), so this is safe.
func (c *Client) withCacheBuster(url string) string {
	if !c.cacheBust {
		return url
	}
	token := fmt.Sprintf("%d-%d", time.Now().UnixNano(), c.cbSeq.Add(1))
	sep := "?"
	if strings.Contains(url, "?") {
		sep = "&"
	}
	return url + sep + "cb=" + token
}
