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

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Orchestrates one crawl pass over all configured providers (design doc §5.4).
 * Guiding rule: state advances only after a confirmed 200 Ack, so every failure self-heals
 * on the next pass and a partially-fetched catalog is never indexed.
 *
 * <p>Logs use the same structured style as the discover/publish jobs: the message is a stable
 * event id and the data rides along as {@code value("key", val)} pairs.
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
        log.info(LogEvent.PASS_STARTED, value("providers", props.providers().size()));
        for (String provider : props.providers()) {
            try {
                crawlProvider(provider);
            } catch (Exception e) {
                log.error(LogEvent.PROVIDER_FAILED, value("provider", provider), value("error", e.toString()));
                feedback.record(provider, null, "resolve", "provider_error", e.toString());
            }
        }
        log.info(LogEvent.PASS_COMPLETED);
    }

    private void crawlProvider(String provider) throws Exception {
        log.info(LogEvent.PROVIDER_CHECKING, value("provider", provider));

        // 1. RESOLVE — fetch the tiny manifest.
        ManifestResolver.Resolved manifest = manifestResolver.resolve(provider);
        String domain = manifest.domain();   // technical identity (bppId, integrity check)
        String name = manifest.name();       // human-friendly label for logs
        log.info(LogEvent.MANIFEST_RESOLVED, value("provider", name), value("indexUrl", manifest.indexUrl()));

        // 2. CHEAP TOP-LEVEL CHECK — did anything change at all? (the "unmodified" scenario)
        var storedDigest = state.findIndexDigest(manifest.indexUrl());
        if (storedDigest.isPresent() && storedDigest.get().equalsIgnoreCase(manifest.indexDigest())) {
            log.info(LogEvent.INDEX_UNCHANGED, value("provider", name), value("pushed", 0));
            return;
        }
        log.info(LogEvent.INDEX_CHANGED, value("provider", name));

        // 3. FETCH + VERIFY INDEX
        Index index;
        try {
            index = indexPoller.fetchAndVerify(manifest);
        } catch (IndexPoller.IndexIntegrityException e) {
            log.warn(LogEvent.INDEX_INTEGRITY_FAILED, value("provider", name), value("reason", e.getMessage()));
            feedback.record(domain, null, "validate", "index_integrity", e.getMessage());
            return;
        }
        log.info(LogEvent.INDEX_VERIFIED, value("provider", name), value("records", index.records().size()));

        // 4-6. Decide + act per catalog record.
        boolean retryNeeded = false;
        int pushed = 0;
        for (Differ.Decision d : differ.diff(index)) {
            String catalogId = d.record().details().catalogId();
            switch (d.action()) {
                case SKIP_UNCHANGED -> log.info(LogEvent.CATALOG_UNCHANGED, value("catalogId", catalogId));
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

        // 7. Advance the index digest only if the whole pass succeeded — else re-detect + retry.
        if (retryNeeded) {
            log.warn(LogEvent.PROVIDER_RETRY, value("provider", name), value("pushed", pushed),
                    value("stateUpdated", false));
        } else {
            state.upsertIndexState(manifest.indexUrl(), manifest.indexDigest(), index.nextUpdate());
            log.info(LogEvent.PROVIDER_DONE, value("provider", name), value("pushed", pushed),
                    value("stateUpdated", true));
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
