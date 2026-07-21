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

    public Optional<String> findIndexDigest(String indexUrl) {
        return jdbc.sql("SELECT index_digest FROM index_crawl_state WHERE index_url = ?")
                .param(indexUrl)
                .query(String.class)
                .optional();
    }

    /** Upsert after all records for the index are handled (index_digest = last accepted digest). */
    public void upsertIndexState(String indexUrl, String indexDigest, String nextUpdate) {
        jdbc.sql("""
                INSERT INTO index_crawl_state (index_url, index_digest, next_update, last_seen_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (index_url) DO UPDATE
                   SET index_digest = EXCLUDED.index_digest,
                       next_update  = EXCLUDED.next_update,
                       last_seen_at = now()
                """)
                .param(indexUrl)
                .param(indexDigest)
                .param(nextUpdate == null ? null : OffsetDateTime.parse(nextUpdate))
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
    public void upsertPart(String partUrl, String catalogId, long version, String digest, String sourceUpdatedAt) {
        jdbc.sql("""
                INSERT INTO catalog_part_state (part_url, catalog_id, version, digest, source_updated_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (part_url) DO UPDATE
                   SET catalog_id        = EXCLUDED.catalog_id,
                       version           = EXCLUDED.version,
                       digest            = EXCLUDED.digest,
                       source_updated_at = EXCLUDED.source_updated_at,
                       last_seen_at      = now()
                """)
                .param(partUrl)
                .param(catalogId)
                .param(version)
                .param(digest)
                .param(sourceUpdatedAt == null ? null : OffsetDateTime.parse(sourceUpdatedAt))
                .update();
    }
}
