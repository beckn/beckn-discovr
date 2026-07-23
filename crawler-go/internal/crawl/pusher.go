package crawl

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"time"

	"github.com/beckn/beckn-discovr/crawler-go/internal/httpclient"
)

// PushResult is the outcome of one push call: ack (HTTP 200), the status, and a detail string
// ("HTTP <status> <body>") carrying the endpoint's response.
type PushResult struct {
	Ack    bool
	Status int
	Detail string
}

// Pusher wraps a verified catalog part in a Beckn catalog/publish envelope and POSTs it to
// /catalog/push. No publishDirectives is sent, so the publish pipeline uses its default MERGE mode
// and merges parts by catalog id. Re-pushing is safe — the pipeline upserts.
type Pusher struct {
	http     *httpclient.Client
	endpoint string
}

// NewPusher builds a Pusher targeting the configured push endpoint.
func NewPusher(http *httpclient.Client, endpoint string) *Pusher {
	return &Pusher{http: http, endpoint: endpoint}
}

// envelope is the catalog/publish request body.
type envelope struct {
	Context struct {
		Action        string `json:"action"`
		BppID         string `json:"bppId"`
		BppURI        string `json:"bppUri,omitempty"`
		MessageID     string `json:"messageId"`
		TransactionID string `json:"transactionId"`
		Timestamp     string `json:"timestamp"`
		Version       string `json:"version"`
	} `json:"context"`
	Message struct {
		Catalogs []json.RawMessage `json:"catalogs"`
	} `json:"message"`
}

// Push wraps the given verified part bodies (each a Beckn catalog document) in one envelope and
// POSTs it. domain is the provider domain (= bppId, from the manifest).
func (p *Pusher) Push(ctx context.Context, domain string, partBodies [][]byte) (PushResult, error) {
	var env envelope
	env.Context.Action = "catalog/publish"
	env.Context.BppID = domain
	env.Context.BppURI = firstBppURI(partBodies)
	env.Context.MessageID = newUUID()
	env.Context.TransactionID = newUUID()
	env.Context.Timestamp = time.Now().UTC().Format(time.RFC3339)
	env.Context.Version = "2.0.0"
	for _, body := range partBodies {
		env.Message.Catalogs = append(env.Message.Catalogs, json.RawMessage(body)) // each part is one catalog doc
	}

	payload, err := json.Marshal(env)
	if err != nil {
		return PushResult{}, err
	}
	resp, err := p.http.PostJSON(ctx, p.endpoint, string(payload))
	if err != nil {
		return PushResult{}, err
	}
	detail := fmt.Sprintf("HTTP %d", resp.Status)
	if len(resp.Body) > 0 {
		detail += " " + string(resp.Body)
	}
	return PushResult{Ack: resp.Status == 200, Status: resp.Status, Detail: detail}, nil
}

// firstBppURI pulls bppUri from the catalog file itself when present (design doc §5.8); else "".
func firstBppURI(partBodies [][]byte) string {
	for _, body := range partBodies {
		var node struct {
			BppURI string `json:"bppUri"`
		}
		if err := json.Unmarshal(body, &node); err == nil && node.BppURI != "" {
			return node.BppURI
		}
	}
	return ""
}

// newUUID returns a random RFC 4122 v4 UUID string.
func newUUID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40 // version 4
	b[8] = (b[8] & 0x3f) | 0x80 // variant 10
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
