package org.beckn.crawler.state;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The crawler's memory of "what I saw last time" — design doc §5.3.
 * Two URL-keyed tables. Rows are upserted ONLY after the corresponding push succeeds, so a
 * crash mid-pass re-does that catalog rather than skipping it.
 */
@Repository
public class StateStore {

    /** One row per index (= per provider for the POC). */
    public record IndexState(String indexUrl, String indexDigest, String nextUpdate, Instant lastSeenAt) {}

    /** One row per catalog part. */
    public record PartState(String partUrl, String catalogId, long version, String digest,
                            String sourceUpdatedAt, Instant lastSeenAt) {}

    private final JdbcClient jdbc;

    public StateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── index_crawl_state ──────────────────────────────────────────────────────

    /** The change-detection state of one index: its last-processed digest and how that pass ended. */
    public record IndexSyncState(String digest, String syncStatus) {}

    /**
     * Read an index's digest + sync_status together. The poll skips an index only when BOTH the
     * digest matches AND the last pass was 'success' — a 'partial'/'failed' index is re-diffed even
     * with an unchanged digest, so still-failed parts keep retrying (see Crawler.pollIndex).
     */
    public Optional<IndexSyncState> findIndexState(String indexUrl) {
        return jdbc.sql("SELECT index_digest, sync_status FROM index_crawl_state WHERE index_url = ?")
                .param(indexUrl)
                .query((rs, n) -> new IndexSyncState(rs.getString("index_digest"), rs.getString("sync_status")))
                .optional();
    }

    /**
     * SUCCESS path: every pushed part ACKed. Advances index_digest (= last accepted digest) and
     * stamps sync_status='success' / clears error_detail. Call this ONLY when the whole pass succeeded.
     */
    public void upsertIndexState(String indexUrl, String indexDigest, String nextUpdate, String providerDomain) {
        jdbc.sql("""
                INSERT INTO index_crawl_state (index_url, index_digest, next_update, provider_domain,
                                               sync_status, error_detail, last_seen_at)
                VALUES (?, ?, ?, ?, 'success', NULL, now())
                ON CONFLICT (index_url) DO UPDATE
                   SET index_digest    = EXCLUDED.index_digest,
                       next_update     = EXCLUDED.next_update,
                       provider_domain = EXCLUDED.provider_domain,
                       sync_status     = 'success',
                       error_detail    = NULL,
                       last_seen_at    = now()
                """)
                .param(indexUrl)
                .param(indexDigest)
                .param(nextUpdate == null ? null : OffsetDateTime.parse(nextUpdate))
                .param(providerDomain)
                .update();
    }

    /**
     * PARTIAL/FAILED path: at least one part failed to push. Advances index_digest to the CURRENT
     * digest (same as success) so it reflects the index version we processed, but stamps
     * sync_status='partial'|'failed' + error_detail. Retry is NOT driven by a stale digest here —
     * it is driven by sync_status: the poll re-diffs whenever sync_status != 'success' (even if the
     * digest matches), and Differ re-pushes ONLY the still-failed parts (ACKed parts are stored in
     * catalog_part_state and skipped as unchanged). Fixing a part changes its bytes → its digest →
     * the index digest, so a content fix is also picked up naturally.
     *
     * @param status       "partial" or "failed"
     * @param errorDetail  JSON array of failed parts: [{catalogId, partUrl, httpStatus, detail}, ...]
     */
    public void recordIndexOutcome(String indexUrl, String indexDigest, String nextUpdate,
                                   String providerDomain, String status, String errorDetail) {
        jdbc.sql("""
                INSERT INTO index_crawl_state (index_url, index_digest, next_update, provider_domain,
                                               sync_status, error_detail, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (index_url) DO UPDATE
                   SET index_digest    = EXCLUDED.index_digest,
                       next_update     = EXCLUDED.next_update,
                       provider_domain = EXCLUDED.provider_domain,
                       sync_status     = EXCLUDED.sync_status,
                       error_detail    = EXCLUDED.error_detail,
                       last_seen_at    = now()
                """)
                .param(indexUrl)
                .param(indexDigest)
                .param(nextUpdate == null ? null : OffsetDateTime.parse(nextUpdate))
                .param(providerDomain)
                .param(status)
                .param(errorDetail)
                .update();
    }

    /**
     * Record the provider identity (from the manifest) back onto its source row, so the UI can
     * join provider → crawl state and show the real name. No-op for config sources (no matching row).
     */
    public void updateSourceIdentity(String dediUrl, String providerDomain, String providerName) {
        jdbc.sql("""
                UPDATE crawler_source
                   SET provider_domain = ?, provider_name = ?
                 WHERE dedi_url = ?
                """)
                .param(providerDomain)
                .param(providerName)
                .param(dediUrl)
                .update();
    }

    // ── catalog_part_state ─────────────────────────────────────────────────────

    public Optional<PartState> findPart(String partUrl) {
        return jdbc.sql("""
                SELECT part_url, catalog_id, version, digest, source_updated_at, last_seen_at
                  FROM catalog_part_state WHERE part_url = ?
                """)
                .param(partUrl)
                .query((rs, n) -> new PartState(
                        rs.getString("part_url"),
                        rs.getString("catalog_id"),
                        rs.getLong("version"),
                        rs.getString("digest"),
                        rs.getString("source_updated_at"),
                        rs.getObject("last_seen_at", java.sql.Timestamp.class).toInstant()))
                .optional();
    }

    /** Upsert AFTER that part's push returns 200 (digest is a proven digest, never merely announced). */
    public void upsertPart(String partUrl, String catalogId, long version, String digest,
                           String sourceUpdatedAt, String providerDomain) {
        jdbc.sql("""
                INSERT INTO catalog_part_state (part_url, catalog_id, version, digest, source_updated_at, provider_domain, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (part_url) DO UPDATE
                   SET catalog_id        = EXCLUDED.catalog_id,
                       version           = EXCLUDED.version,
                       digest            = EXCLUDED.digest,
                       source_updated_at = EXCLUDED.source_updated_at,
                       provider_domain   = EXCLUDED.provider_domain,
                       last_seen_at      = now()
                """)
                .param(partUrl)
                .param(catalogId)
                .param(version)
                .param(digest)
                .param(sourceUpdatedAt == null ? null : OffsetDateTime.parse(sourceUpdatedAt))
                .param(providerDomain)
                .update();
    }
}
