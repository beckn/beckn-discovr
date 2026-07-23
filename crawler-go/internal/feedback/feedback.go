// Package feedback is the append-only structured reject/skip log (design doc §5.10) — one JSON
// object per line. stage ∈ {resolve, poll, validate, fetch, verify, push}; reason is a short code.
// Faithful port of the Java FeedbackLog: it both logs a WARN event and persists a line to a file.
package feedback

import (
	"encoding/json"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/beckn/beckn-discovr/crawler-go/internal/logging"
)

// Log writes feedback entries to a file (and mirrors them to the console as WARN events).
type Log struct {
	path string
	mu   sync.Mutex // the manifest and index cadences run on separate goroutines
}

// New builds a feedback Log that appends to the given path.
func New(path string) *Log { return &Log{path: path} }

// entry is one line of the feedback log; field order matches the Java LinkedHashMap. Domain and
// CatalogID are pointers so an absent value marshals to JSON null (as Jackson does for a null map
// value), not "".
type entry struct {
	Ts        string  `json:"ts"`
	Domain    *string `json:"domain"`
	CatalogID *string `json:"catalogId"`
	Stage     string  `json:"stage"`
	Reason    string  `json:"reason"`
	Detail    string  `json:"detail"`
}

// nullify maps "" → nil so an absent field logs/serializes as null, matching the Java service.
func nullify(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// Record surfaces the event on the console and appends it to the feedback file.
func (l *Log) Record(domain, catalogID, stage, reason, detail string) {
	slog.Warn(logging.Feedback,
		"domain", nullify(domain), "catalogId", nullify(catalogID),
		"stage", stage, "reason", reason, "detail", detail)

	line, err := json.Marshal(entry{
		Ts: time.Now().UTC().Format(time.RFC3339Nano), Domain: nullify(domain), CatalogID: nullify(catalogID),
		Stage: stage, Reason: reason, Detail: detail,
	})
	if err != nil {
		slog.Error(logging.FeedbackWriteFailed, "path", l.path, "error", err.Error())
		return
	}

	l.mu.Lock()
	defer l.mu.Unlock()
	if dir := filepath.Dir(l.path); dir != "" {
		_ = os.MkdirAll(dir, 0o755)
	}
	f, err := os.OpenFile(l.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		slog.Error(logging.FeedbackWriteFailed, "path", l.path, "error", err.Error())
		return
	}
	defer f.Close()
	if _, err := f.Write(append(line, '\n')); err != nil {
		slog.Error(logging.FeedbackWriteFailed, "path", l.path, "error", err.Error())
	}
}
