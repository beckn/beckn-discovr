package org.beckn.crawler.crawl;

import org.beckn.crawler.config.CrawlerProperties;
import org.beckn.crawler.feedback.FeedbackLog;
import org.beckn.crawler.logging.LogEvent;
import org.beckn.crawler.model.FeedModels.Index;
import org.beckn.crawler.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Orchestrates crawling on two independent cadences (design: manifest and index change at very
 * different rates and, in the target architecture, live in different places):
 *
 * <ul>
 *   <li>{@link #refreshManifests()} — long cadence (e.g. weekly). Learns each provider's identity
 *       and where its index lives, and caches it. The manifest rarely changes; in production it
 *       comes from a DeDi service.</li>
 *   <li>{@link #runIndexPass()} — short cadence (e.g. per minute). Uses the cached manifest to fetch
 *       the index directly, detects changes by the index's own digest, and pushes changed catalogs.
 *       The index + catalog parts live in the cloud bucket.</li>
 * </ul>
 *
 * <p>Guiding rule: state advances only after a confirmed 200 Ack, so every failure self-heals on
 * the next pass and a partially-fetched catalog is never indexed.
 */
@Component
public class Crawler {

    private static final Logger log = LoggerFactory.getLogger(Crawler.class);

    private final CrawlerProperties props;
    private final ManifestResolver manifestResolver;
    private final IndexPoller indexPoller;
    private final Differ differ;
    private final Fetcher fetcher;
    private final Pusher pusher;
    private final StateStore state;
    private final FeedbackLog feedback;

    /**
     * Cached registries per provider base URL — one entry per manifest {@code files[]} registry.
     * Refreshed on the long cadence, read on the short one.
     */
    private final Map<String, List<ManifestResolver.Resolved>> manifestCache = new ConcurrentHashMap<>();

    public Crawler(CrawlerProperties props, ManifestResolver manifestResolver, IndexPoller indexPoller,
                   Differ differ, Fetcher fetcher, Pusher pusher, StateStore state, FeedbackLog feedback) {
        this.props = props;
        this.manifestResolver = manifestResolver;
        this.indexPoller = indexPoller;
        this.differ = differ;
        this.fetcher = fetcher;
        this.pusher = pusher;
        this.state = state;
        this.feedback = feedback;
    }

    // ── Long cadence: refresh the manifest (provider identity + index location) ──────────────

    /** Re-read every provider's manifest and refresh the cache. Never throws. */
    public void refreshManifests() {
        log.info(LogEvent.MANIFEST_REFRESH_STARTED, value("providers", props.providers().size()));
        for (String provider : props.providers()) {
            try {
                List<ManifestResolver.Resolved> registries = manifestResolver.resolve(provider);
                manifestCache.put(provider, registries);
                for (ManifestResolver.Resolved r : registries) {
                    log.info(LogEvent.MANIFEST_REFRESHED, value("provider", r.name()),
                            value("registry", r.registry()), value("indexUrl", r.indexUrl()));
                    verifyIndexAgainstManifest(r);
                }
            } catch (Exception e) {
                // Keep any previously cached manifest so index polling can carry on.
                log.warn(LogEvent.MANIFEST_REFRESH_FAILED, value("provider", provider), value("error", e.toString()));
                feedback.record(provider, null, "resolve", "manifest_error", e.toString());
            }
        }
        log.info(LogEvent.MANIFEST_REFRESH_COMPLETED);
    }

    /**
     * Integrity checkpoint at manifest-read time (startup + daily): fetch the live index and confirm
     * it hashes to the digest the manifest promised. When the manifest is read, the publisher's
     * manifest and index should be consistent, so a mismatch flags tampering or a publisher that
     * didn't keep the chain in sync. Soft — logged + recorded, does not gate the per-minute poll
     * (which can't use this stale digest and relies on part-level verification + the deferred
     * index signature).
     */
    private void verifyIndexAgainstManifest(ManifestResolver.Resolved reg) {
        if (!reg.isLive() || reg.indexDigest() == null || reg.indexDigest().isBlank()) return;
        try {
            IndexPoller.Result r = indexPoller.fetch(reg);
            if (r.digest().equalsIgnoreCase(reg.indexDigest())) {
                log.info(LogEvent.MANIFEST_INDEX_VERIFIED, value("provider", reg.name()),
                        value("registry", reg.registry()));
            } else {
                log.warn(LogEvent.MANIFEST_INDEX_MISMATCH, value("provider", reg.name()),
                        value("registry", reg.registry()), value("manifestDigest", reg.indexDigest()),
                        value("indexDigest", r.digest()));
                feedback.record(reg.domain(), null, "validate", "manifest_index_digest_mismatch",
                        "manifest=" + reg.indexDigest() + " index=" + r.digest());
            }
        } catch (Exception e) {
            log.warn(LogEvent.MANIFEST_INDEX_MISMATCH, value("provider", reg.name()),
                    value("registry", reg.registry()), value("error", e.toString()));
            feedback.record(reg.domain(), null, "validate", "manifest_index_check_error", e.toString());
        }
    }

    // ── Short cadence: poll each provider's index for catalog changes ────────────────────────

    /** Run one index-poll pass across every provider. Never throws — errors are logged. */
    public void runIndexPass() {
        log.info(LogEvent.PASS_STARTED, value("providers", props.providers().size()));
        for (String provider : props.providers()) {
            try {
                pollProvider(provider);
            } catch (Exception e) {
                log.error(LogEvent.PROVIDER_FAILED, value("provider", provider), value("error", e.toString()));
                feedback.record(provider, null, "poll", "provider_error", e.toString());
            }
        }
        log.info(LogEvent.PASS_COMPLETED);
    }

    private void pollProvider(String provider) throws Exception {
        // Use the cached registries; lazily learn the manifest once if we haven't yet (first boot
        // before the long-cadence refresh has run). No hardcoded startup ordering needed.
        List<ManifestResolver.Resolved> registries = manifestCache.get(provider);
        if (registries == null) {
            registries = manifestResolver.resolve(provider);
            manifestCache.put(provider, registries);
            for (ManifestResolver.Resolved r : registries) {
                log.info(LogEvent.MANIFEST_REFRESHED, value("provider", r.name()),
                        value("registry", r.registry()), value("indexUrl", r.indexUrl()));
            }
        }

        // Poll every registry the manifest advertises (each files[] entry = its own index).
        log.info(LogEvent.PROVIDER_CHECKING, value("provider", registries.get(0).name()),
                value("registries", registries.size()));
        for (ManifestResolver.Resolved registry : registries) {
            pollIndex(registry);
        }
    }

    /** Poll one registry's index: state gate → fetch → change-detect → diff → push. Never throws. */
    private void pollIndex(ManifestResolver.Resolved reg) {
        String domain = reg.domain();   // technical identity (bppId, integrity check)
        String name = reg.name();       // human-friendly label for logs
        String registry = reg.registry();

        // Registry-level gate: only crawl an index whose registry state is "live".
        if (!reg.isLive()) {
            log.warn(LogEvent.REGISTRY_NOT_LIVE, value("provider", name), value("registry", registry),
                    value("state", reg.state()));
            feedback.record(domain, null, "validate", "registry_not_live", "files[].state=" + reg.state());
            return;
        }

        // Fetch the index (per-minute) and compute its digest — the change signal.
        IndexPoller.Result result;
        try {
            result = indexPoller.fetch(reg);
        } catch (IndexPoller.IndexIntegrityException e) {
            log.warn(LogEvent.INDEX_INTEGRITY_FAILED, value("provider", name), value("registry", registry),
                    value("reason", e.getMessage()));
            feedback.record(domain, null, "validate", "index_integrity", e.getMessage());
            return;
        } catch (Exception e) {
            log.warn(LogEvent.INDEX_INTEGRITY_FAILED, value("provider", name), value("registry", registry),
                    value("error", e.toString()));
            feedback.record(domain, null, "poll", "index_error", e.toString());
            return;
        }

        // Cheap check: unchanged index → nothing to do.
        var storedDigest = state.findIndexDigest(reg.indexUrl());
        if (storedDigest.isPresent() && storedDigest.get().equalsIgnoreCase(result.digest())) {
            log.info(LogEvent.INDEX_UNCHANGED, value("provider", name), value("registry", registry), value("pushed", 0));
            return;
        }
        log.info(LogEvent.INDEX_CHANGED, value("provider", name), value("registry", registry));
        Index index = result.index();
        log.info(LogEvent.INDEX_VERIFIED, value("provider", name), value("registry", registry),
                value("records", index.records().size()));

        // Decide + act per catalog record.
        boolean retryNeeded = false;
        int pushed = 0;
        for (Differ.Decision d : differ.diff(index)) {
            String catalogId = d.record().details().catalogId();
            switch (d.action()) {
                case SKIP_UNCHANGED -> log.info(LogEvent.CATALOG_UNCHANGED, value("catalogId", catalogId));
                case SKIP_INACTIVE -> {
                    log.info(LogEvent.CATALOG_INACTIVE, value("catalogId", catalogId), value("detail", d.detail()));
                    feedback.record(domain, catalogId, "validate", "status_not_active", d.detail());
                }
                case SKIP_NON_PUBLIC -> {
                    log.info(LogEvent.CATALOG_NONPUBLIC, value("catalogId", catalogId), value("detail", d.detail()));
                    feedback.record(domain, catalogId, "validate", "non_public", d.detail());
                }
                case SKIP_ROLLBACK -> {
                    log.warn(LogEvent.CATALOG_ROLLBACK, value("catalogId", catalogId), value("detail", d.detail()));
                    feedback.record(domain, catalogId, "validate", "version_rollback", d.detail());
                }
                case RETIRE -> {
                    log.info(LogEvent.CATALOG_RETIRED, value("catalogId", catalogId), value("detail", d.detail()));
                    feedback.record(domain, catalogId, "validate", "retired_skipped", d.detail());
                }
                case PUSH -> {
                    if (pushCatalog(domain, d)) pushed++;
                    else retryNeeded = true;
                }
            }
        }

        // Advance the index digest only if the whole pass succeeded — else re-detect + retry.
        if (retryNeeded) {
            log.warn(LogEvent.PROVIDER_RETRY, value("provider", name), value("registry", registry),
                    value("pushed", pushed), value("stateUpdated", false));
        } else {
            state.upsertIndexState(reg.indexUrl(), result.digest(), index.nextUpdate());
            log.info(LogEvent.PROVIDER_DONE, value("provider", name), value("registry", registry),
                    value("pushed", pushed), value("stateUpdated", true));
        }
    }

    /** Fetch+verify all changed parts, push once, persist part state on 200. Returns success. */
    private boolean pushCatalog(String domain, Differ.Decision d) {
        String catalogId = d.record().details().catalogId();
        long version = d.record().details().version();
        log.info(LogEvent.CATALOG_CHANGED, value("catalogId", catalogId), value("parts", d.changedParts().size()));

        List<byte[]> bodies = new ArrayList<>();
        try {
            for (var part : d.changedParts()) {
                bodies.add(fetcher.fetchVerified(part.url(), part.digest()));
                log.info(LogEvent.PART_FETCHED, value("catalogId", catalogId), value("url", part.url()));
            }
        } catch (Fetcher.DigestMismatchException e) {
            log.warn(LogEvent.CATALOG_DIGEST_MISMATCH, value("catalogId", catalogId), value("reason", e.getMessage()));
            feedback.record(domain, catalogId, "verify", "digest_mismatch", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn(LogEvent.CATALOG_FETCH_FAILED, value("catalogId", catalogId), value("error", e.toString()));
            feedback.record(domain, catalogId, "fetch", "fetch_error", e.toString());
            return false;
        }

        Pusher.Result result;
        try {
            result = pusher.push(domain, bodies);
        } catch (Exception e) {
            log.warn(LogEvent.CATALOG_PUSH_REJECTED, value("catalogId", catalogId), value("error", e.toString()));
            feedback.record(domain, catalogId, "push", "push_error", e.toString());
            return false;
        }
        if (!result.ack()) {
            log.warn(LogEvent.CATALOG_PUSH_REJECTED, value("catalogId", catalogId), value("detail", result.detail()));
            feedback.record(domain, catalogId, "push", "push_nack", result.detail());
            return false;
        }

        for (var part : d.changedParts()) {
            state.upsertPart(part.url(), catalogId, version, part.digest(), part.lastModified());
        }
        log.info(LogEvent.CATALOG_PUSHED, value("catalogId", catalogId), value("version", version),
                value("parts", d.changedParts().size()), value("status", result.status()));
        return true;
    }
}
