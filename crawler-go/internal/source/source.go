// Package source supplies the current list of manifest sources to crawl. Which implementation is
// active is chosen by crawler.source ("config" or "db"). Read fresh on every pass so runtime
// changes (e.g. a new row added via the UI) take effect within a poll. Ports the Java
// SourceRegistry + ConfigSourceRegistry + DbSourceRegistry.
package source

import (
	"context"
	"database/sql"
	"strings"

	"github.com/beckn/beckn-discovr/crawler-go/internal/config"
)

// Source is one manifest source: a full DeDi manifest URL (fetched directly, no path appended) and
// an optional human label for logs (falls back to the manifest's own name).
type Source struct {
	ManifestURL string
	DisplayName string
}

// Registry supplies the current sources to crawl.
type Registry interface {
	Sources(ctx context.Context) ([]Source, error)
}

// New picks the registry implementation from cfg.Source: "db" reads the crawler_source table,
// anything else ("config", default) uses the configured providers list.
func New(cfg config.Config, db *sql.DB) Registry {
	if strings.EqualFold(cfg.Source, "db") {
		return &dbRegistry{db: db}
	}
	sources := make([]Source, 0, len(cfg.Providers))
	for _, u := range cfg.Providers {
		if t := strings.TrimSpace(u); t != "" {
			sources = append(sources, Source{ManifestURL: t})
		}
	}
	return &configRegistry{sources: sources}
}

// configRegistry serves a fixed list built from crawler.providers.
type configRegistry struct{ sources []Source }

func (r *configRegistry) Sources(context.Context) ([]Source, error) { return r.sources, nil }

// dbRegistry serves the active rows of crawler_source, re-read on every call.
type dbRegistry struct{ db *sql.DB }

func (r *dbRegistry) Sources(ctx context.Context) ([]Source, error) {
	rows, err := r.db.QueryContext(ctx,
		`SELECT dedi_url, display_name FROM crawler_source WHERE status = true ORDER BY created_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Source
	for rows.Next() {
		var url string
		var name sql.NullString
		if err := rows.Scan(&url, &name); err != nil {
			return nil, err
		}
		out = append(out, Source{ManifestURL: url, DisplayName: name.String})
	}
	return out, rows.Err()
}
