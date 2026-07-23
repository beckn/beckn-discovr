// Package state is the crawler's memory of "what I saw last time" (design doc §5.3) — a faithful
// port of the Java StateStore over database/sql. Two URL-keyed tables plus the crawler_source
// source table. Rows in the part/index tables are written per the same proven-state rules as the
// Java version.
package state

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

// Store wraps the *sql.DB with the crawler's state queries.
type Store struct {
	db *sql.DB
}

// New builds a Store over an open database handle.
func New(db *sql.DB) *Store { return &Store{db: db} }

// PartState is one row of catalog_part_state (only the fields the Differ needs are mapped).
type PartState struct {
	Version int64
	Digest  string
}

// IndexSyncState is an index's change-detection state: its last-processed digest and how that
// pass ended. Either may be empty (NULL in the row).
type IndexSyncState struct {
	Digest     string
	SyncStatus string
}

// ── catalog_part_state ──────────────────────────────────────────────────────

// FindPart returns the stored state for a part URL, or (nil, nil) if never seen.
func (s *Store) FindPart(ctx context.Context, partURL string) (*PartState, error) {
	var ps PartState
	err := s.db.QueryRowContext(ctx,
		`SELECT version, digest FROM catalog_part_state WHERE part_url = $1`, partURL,
	).Scan(&ps.Version, &ps.Digest)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &ps, nil
}

// UpsertPart records a part AFTER its push returns 200 (digest is proven, never merely announced).
func (s *Store) UpsertPart(ctx context.Context, partURL, catalogID string, version int64, digest, sourceUpdatedAt, providerDomain string) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO catalog_part_state (part_url, catalog_id, version, digest, source_updated_at, provider_domain, last_seen_at)
		VALUES ($1, $2, $3, $4, $5, $6, now())
		ON CONFLICT (part_url) DO UPDATE
		   SET catalog_id        = EXCLUDED.catalog_id,
		       version           = EXCLUDED.version,
		       digest            = EXCLUDED.digest,
		       source_updated_at = EXCLUDED.source_updated_at,
		       provider_domain   = EXCLUDED.provider_domain,
		       last_seen_at      = now()`,
		partURL, catalogID, version, digest, parseTS(sourceUpdatedAt), providerDomain)
	return err
}

// ── index_crawl_state ─────────────────────────────────────────────────────

// FindIndexState reads an index's digest + sync_status together, or (nil, nil) if no row. The poll
// skips an index only when the digest matches AND sync_status='success' (see crawl.pollIndex).
func (s *Store) FindIndexState(ctx context.Context, indexURL string) (*IndexSyncState, error) {
	var digest, status sql.NullString
	err := s.db.QueryRowContext(ctx,
		`SELECT index_digest, sync_status FROM index_crawl_state WHERE index_url = $1`, indexURL,
	).Scan(&digest, &status)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &IndexSyncState{Digest: digest.String, SyncStatus: status.String}, nil
}

// UpsertIndexState is the SUCCESS path: every pushed part ACKed. Advances index_digest and stamps
// sync_status='success' / clears error_detail. Call ONLY when the whole pass succeeded.
func (s *Store) UpsertIndexState(ctx context.Context, indexURL, indexDigest, nextUpdate, providerDomain string) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO index_crawl_state (index_url, index_digest, next_update, provider_domain,
		                               sync_status, error_detail, last_seen_at)
		VALUES ($1, $2, $3, $4, 'success', NULL, now())
		ON CONFLICT (index_url) DO UPDATE
		   SET index_digest    = EXCLUDED.index_digest,
		       next_update     = EXCLUDED.next_update,
		       provider_domain = EXCLUDED.provider_domain,
		       sync_status     = 'success',
		       error_detail    = NULL,
		       last_seen_at    = now()`,
		indexURL, indexDigest, parseTS(nextUpdate), providerDomain)
	return err
}

// RecordIndexOutcome is the PARTIAL/FAILED path. Advances index_digest to the current digest (so it
// reflects the index version processed) but stamps sync_status='partial'|'failed' + error_detail.
// Retry is driven by sync_status, not a stale digest: the poll re-diffs whenever sync_status !=
// 'success' and Differ re-pushes only the still-failed parts.
func (s *Store) RecordIndexOutcome(ctx context.Context, indexURL, indexDigest, nextUpdate, providerDomain, status, errorDetail string) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO index_crawl_state (index_url, index_digest, next_update, provider_domain,
		                               sync_status, error_detail, last_seen_at)
		VALUES ($1, $2, $3, $4, $5, $6, now())
		ON CONFLICT (index_url) DO UPDATE
		   SET index_digest    = EXCLUDED.index_digest,
		       next_update     = EXCLUDED.next_update,
		       provider_domain = EXCLUDED.provider_domain,
		       sync_status     = EXCLUDED.sync_status,
		       error_detail    = EXCLUDED.error_detail,
		       last_seen_at    = now()`,
		indexURL, indexDigest, parseTS(nextUpdate), providerDomain, status, errorDetail)
	return err
}

// UpdateSourceIdentity records the manifest's provider identity back onto its source row, so the UI
// can join provider → crawl state and show the real name. No-op for config sources (no matching row).
func (s *Store) UpdateSourceIdentity(ctx context.Context, dediURL, providerDomain, providerName string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE crawler_source SET provider_domain = $1, provider_name = $2 WHERE dedi_url = $3`,
		providerDomain, providerName, dediURL)
	return err
}

// parseTS converts an RFC3339 timestamp string to a value suitable for a TIMESTAMPTZ param, or nil
// (SQL NULL) when blank/unparseable — mirrors the Java OffsetDateTime.parse-or-null handling.
func parseTS(s string) any {
	if s == "" {
		return nil
	}
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t
	}
	return nil
}
