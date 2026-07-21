package org.beckn.crawler.crawl;

import org.beckn.crawler.config.CrawlerProperties;
import org.beckn.crawler.feedback.FeedbackLog;
import org.beckn.crawler.model.FeedModels.Index;
import org.beckn.crawler.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates one crawl pass over all configured providers (design doc §5.4).
 * Guiding rule: state advances only after a confirmed 200 Ack, so every failure self-heals
 * on the next pass and a partially-fetched catalog is never indexed.
 *
 * <p>Logs are written as a simple, human-readable lifecycle so the flow is easy to follow in a
 * live demo.
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

    /** Runs one full pass across every configured provider. Never throws — errors are logged. */
    public void runPass() {
        log.info("═════ Crawl pass starting — {} provider(s) ═════", props.providers().size());
        for (String provider : props.providers()) {
            try {
                crawlProvider(provider);
            } catch (Exception e) {
                log.error("[{}] Provider failed ({}) — leaving state untouched, moving on", provider, e.toString());
                feedback.record(provider, null, "resolve", "provider_error", e.toString());
            }
        }
        log.info("═════ Crawl pass complete ═════");
    }

    private void crawlProvider(String provider) throws Exception {
        log.info("[{}] Checking provider", provider);

        // 1. RESOLVE — fetch the tiny manifest.
        ManifestResolver.Resolved manifest = manifestResolver.resolve(provider);
        String domain = manifest.domain();
        log.info("[{}] Manifest OK — index = {}", domain, manifest.indexUrl());

        // 2. CHEAP TOP-LEVEL CHECK — did anything change at all? (the "unmodified" scenario)
        var storedDigest = state.findIndexDigest(manifest.indexUrl());
        if (storedDigest.isPresent() && storedDigest.get().equalsIgnoreCase(manifest.indexDigest())) {
            log.info("[{}] No change since last run → skipping (0 catalogs pushed)", domain);
            return;
        }
        log.info("[{}] Change detected (index digest differs) → fetching index", domain);

        // 3. FETCH + VERIFY INDEX
        Index index;
        try {
            index = indexPoller.fetchAndVerify(manifest);
        } catch (IndexPoller.IndexIntegrityException e) {
            log.warn("[{}] Index failed integrity check → skipping provider ({})", domain, e.getMessage());
            feedback.record(domain, null, "validate", "index_integrity", e.getMessage());
            return;
        }
        log.info("[{}] Index verified — {} record(s) to evaluate", domain, index.records().size());

        // 4-6. Decide + act per catalog record.
        boolean retryNeeded = false;
        int pushed = 0;
        for (Differ.Decision d : differ.diff(index)) {
            String catalogId = d.record().details().catalogId();
            switch (d.action()) {
                case SKIP_UNCHANGED -> log.info("   • {} : unchanged → skip", catalogId);
                case SKIP_NON_PUBLIC -> {
                    log.info("   • {} : not public → skip", catalogId);
                    feedback.record(domain, catalogId, "validate", "non_public", d.detail());
                }
                case SKIP_ROLLBACK -> {
                    log.warn("   • {} : version rollback → reject ({})", catalogId, d.detail());
                    feedback.record(domain, catalogId, "validate", "version_rollback", d.detail());
                }
                case RETIRE -> {
                    log.info("   • {} : RETIRED → skip (POC logs the intent)", catalogId);
                    feedback.record(domain, catalogId, "validate", "retired_skipped", d.detail());
                }
                case PUSH -> {
                    if (pushCatalog(domain, d)) pushed++;
                    else retryNeeded = true;
                }
            }
        }

        // 7. Advance the index digest only if the whole pass succeeded — else re-detect + retry.
        if (retryNeeded) {
            log.warn("[{}] Done — {} catalog(s) pushed, some failed → NOT advancing state, will retry next pass",
                    domain, pushed);
        } else {
            state.upsertIndexState(manifest.indexUrl(), manifest.indexDigest(), index.nextUpdate());
            log.info("[{}] Done — {} catalog(s) pushed, state updated", domain, pushed);
        }
    }

    /** Fetch+verify all changed parts, push once, persist part state on 200. Returns success. */
    private boolean pushCatalog(String domain, Differ.Decision d) {
        String catalogId = d.record().details().catalogId();
        long version = d.record().details().version();
        log.info("   • {} : changed → fetching {} part(s)", catalogId, d.changedParts().size());

        List<byte[]> bodies = new ArrayList<>();
        try {
            for (var part : d.changedParts()) {
                bodies.add(fetcher.fetchVerified(part.url(), part.digest()));
                log.info("       fetched + digest verified: {}", part.url());
            }
        } catch (Fetcher.DigestMismatchException e) {
            log.warn("   • {} : digest mismatch → reject, not indexed ({})", catalogId, e.getMessage());
            feedback.record(domain, catalogId, "verify", "digest_mismatch", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("   • {} : fetch failed → skip ({})", catalogId, e.toString());
            feedback.record(domain, catalogId, "fetch", "fetch_error", e.toString());
            return false;
        }

        Pusher.Result result;
        try {
            result = pusher.push(domain, bodies);
        } catch (Exception e) {
            log.warn("   • {} : push errored → skip ({})", catalogId, e.toString());
            feedback.record(domain, catalogId, "push", "push_error", e.toString());
            return false;
        }
        if (!result.ack()) {
            log.warn("   • {} : push rejected ({}) → will retry next pass", catalogId, result.detail());
            feedback.record(domain, catalogId, "push", "push_nack", result.detail());
            return false;
        }

        for (var part : d.changedParts()) {
            state.upsertPart(part.url(), catalogId, version, part.digest(), part.lastModified());
        }
        log.info("   • {} : pushed → 200 OK (version {}, {} part(s))", catalogId, version, d.changedParts().size());
        return true;
    }
}
