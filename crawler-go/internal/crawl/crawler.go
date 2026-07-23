// Package crawl is the crawler engine: manifest resolution, index polling, diffing, verified
// fetch, per-part push, and state roll-up. This file is the orchestrator — a faithful port of the
// Java Crawler, driving the two cadences (manifest refresh + index poll).
package crawl

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"

	"github.com/beckn/beckn-discovr/crawler-go/internal/feedback"
	"github.com/beckn/beckn-discovr/crawler-go/internal/logging"
	"github.com/beckn/beckn-discovr/crawler-go/internal/model"
	"github.com/beckn/beckn-discovr/crawler-go/internal/source"
	"github.com/beckn/beckn-discovr/crawler-go/internal/state"
)

// Crawler wires the pieces together and holds the manifest cache shared across both cadences.
type Crawler struct {
	sources   source.Registry
	manifests *ManifestResolver
	indexPoll *IndexPoller
	differ    *Differ
	fetcher   *Fetcher
	pusher    *Pusher
	state     *state.Store
	feedback  *feedback.Log

	mu    sync.RWMutex          // guards cache (manifest refresh + index poll run on separate goroutines)
	cache map[string][]Resolved // registries per manifest URL — written on refresh, read on poll
}

// New builds a Crawler from its collaborators.
func New(sources source.Registry, manifests *ManifestResolver, indexPoll *IndexPoller, differ *Differ,
	fetcher *Fetcher, pusher *Pusher, st *state.Store, fb *feedback.Log) *Crawler {
	return &Crawler{
		sources: sources, manifests: manifests, indexPoll: indexPoll, differ: differ,
		fetcher: fetcher, pusher: pusher, state: st, feedback: fb,
		cache: make(map[string][]Resolved),
	}
}

// ── Long cadence: refresh the manifest (provider identity + index location) ──────────────

// RefreshManifests re-reads every source's manifest and refreshes the cache. Never panics.
func (c *Crawler) RefreshManifests(ctx context.Context) {
	sources, err := c.sources.Sources(ctx)
	if err != nil {
		slog.Error(logging.ManifestRefreshFailed, "error", err.Error())
		return
	}
	slog.Info(logging.ManifestRefreshStarted, "sources", len(sources))
	for _, src := range sources {
		registries, err := c.manifests.Resolve(ctx, src.ManifestURL)
		if err != nil {
			// Keep any previously cached manifest so index polling can carry on.
			slog.Warn(logging.ManifestRefreshFailed, "manifestUrl", src.ManifestURL, "error", err.Error())
			c.feedback.Record(src.ManifestURL, "", "resolve", "manifest_error", err.Error())
			continue
		}
		c.putCache(src.ManifestURL, registries)
		c.recordSourceIdentity(ctx, src, registries)
		for _, r := range registries {
			lbl := label(src, r)
			slog.Info(logging.ManifestRefreshed, "provider", lbl, "registry", r.Registry, "indexUrl", r.IndexURL)
			c.verifyIndexAgainstManifest(ctx, r, lbl)
		}
	}
	slog.Info(logging.ManifestRefreshCompleted)
}

// label is the log label for a source's registry: the source's displayName if set, else the manifest name.
func label(src source.Source, reg Resolved) string {
	if strings.TrimSpace(src.DisplayName) != "" {
		return src.DisplayName
	}
	return reg.Name
}

// recordSourceIdentity records the manifest's provider identity (domain + name) back onto the
// source row, so the UI can join provider → crawl state by domain. All of a manifest's registries
// share the one domain/name, so the first entry is representative. No-op for config sources.
func (c *Crawler) recordSourceIdentity(ctx context.Context, src source.Source, registries []Resolved) {
	if len(registries) == 0 {
		return
	}
	first := registries[0]
	if err := c.state.UpdateSourceIdentity(ctx, src.ManifestURL, first.Domain, first.Name); err != nil {
		slog.Warn(logging.ManifestRefreshFailed, "manifestUrl", src.ManifestURL, "error", err.Error())
	}
}

// verifyIndexAgainstManifest is the integrity checkpoint at manifest-read time (startup + daily):
// fetch the live index and confirm it hashes to the digest the manifest promised. Soft — logged +
// recorded, does not gate the per-minute poll (which relies on part-level verification).
func (c *Crawler) verifyIndexAgainstManifest(ctx context.Context, reg Resolved, lbl string) {
	if !reg.IsLive() || strings.TrimSpace(reg.IndexDigest) == "" {
		return
	}
	r, err := c.indexPoll.Fetch(ctx, reg)
	if err != nil {
		slog.Warn(logging.ManifestIndexMismatch, "provider", lbl, "registry", reg.Registry, "error", err.Error())
		c.feedback.Record(reg.Domain, "", "validate", "manifest_index_check_error", err.Error())
		return
	}
	if strings.EqualFold(r.Digest, reg.IndexDigest) {
		slog.Info(logging.ManifestIndexVerified, "provider", lbl, "registry", reg.Registry)
	} else {
		slog.Warn(logging.ManifestIndexMismatch, "provider", lbl, "registry", reg.Registry,
			"manifestDigest", reg.IndexDigest, "indexDigest", r.Digest)
		c.feedback.Record(reg.Domain, "", "validate", "manifest_index_digest_mismatch",
			"manifest="+reg.IndexDigest+" index="+r.Digest)
	}
}

// ── Short cadence: poll each provider's index for catalog changes ────────────────────────

// RunIndexPass runs one index-poll pass across every current source. Never panics.
func (c *Crawler) RunIndexPass(ctx context.Context) {
	sources, err := c.sources.Sources(ctx)
	if err != nil {
		slog.Error(logging.PassFailed, "error", err.Error())
		return
	}
	slog.Info(logging.PassStarted, "sources", len(sources))
	for _, src := range sources {
		if err := c.pollProvider(ctx, src); err != nil {
			slog.Error(logging.ProviderFailed, "manifestUrl", src.ManifestURL, "error", err.Error())
			c.feedback.Record(src.ManifestURL, "", "poll", "provider_error", err.Error())
		}
	}
	slog.Info(logging.PassCompleted)
}

func (c *Crawler) pollProvider(ctx context.Context, src source.Source) error {
	// Use the cached registries; lazily learn the manifest once on a cache miss (first boot, or a
	// source just added via the UI). No hardcoded startup ordering.
	registries := c.getCache(src.ManifestURL)
	if registries == nil {
		resolved, err := c.manifests.Resolve(ctx, src.ManifestURL)
		if err != nil {
			return err
		}
		c.putCache(src.ManifestURL, resolved)
		c.recordSourceIdentity(ctx, src, resolved)
		for _, r := range resolved {
			slog.Info(logging.ManifestRefreshed, "provider", label(src, r), "registry", r.Registry, "indexUrl", r.IndexURL)
		}
		registries = resolved
	}

	slog.Info(logging.ProviderChecking, "provider", label(src, registries[0]), "registries", len(registries))
	for _, registry := range registries {
		if err := c.pollIndex(ctx, registry, label(src, registry)); err != nil {
			return err // a state-store failure aborts the whole provider pass (Java parity)
		}
	}
	return nil
}

// pollIndex polls one registry's index: state gate → fetch → change-detect → diff → push.
//
// Returns an error ONLY for a state-store (DB) failure — mirroring the Java version, where those
// StateStore calls throw unchecked exceptions that abort the whole provider pass and are logged as
// provider_error by RunIndexPass. Fetch/integrity failures are handled locally (logged + fed back,
// nil returned) exactly as Java catches them inside pollIndex.
func (c *Crawler) pollIndex(ctx context.Context, reg Resolved, lbl string) error {
	domain := reg.Domain
	name := lbl
	registry := reg.Registry

	// Registry-level gate: only crawl an index whose registry state is "live".
	if !reg.IsLive() {
		slog.Warn(logging.RegistryNotLive, "provider", name, "registry", registry, "state", reg.State)
		c.feedback.Record(domain, "", "validate", "registry_not_live", "files[].state="+reg.State)
		return nil
	}

	// Fetch the index (per-minute) and compute its digest — the change signal.
	result, err := c.indexPoll.Fetch(ctx, reg)
	if err != nil {
		var integ *IntegrityError
		if errors.As(err, &integ) {
			slog.Warn(logging.IndexIntegrityFailed, "provider", name, "registry", registry, "reason", integ.Error())
			c.feedback.Record(domain, "", "validate", "index_integrity", integ.Error())
		} else {
			slog.Warn(logging.IndexIntegrityFailed, "provider", name, "registry", registry, "error", err.Error())
			c.feedback.Record(domain, "", "poll", "index_error", err.Error())
		}
		return nil
	}

	// Cheap check: skip only when the digest is unchanged AND the last pass fully succeeded. A
	// 'partial'/'failed' index is re-diffed even on an unchanged digest, so its still-failed parts
	// keep retrying (ACKed parts are then SKIP_UNCHANGED — only the failed part re-pushes).
	stored, err := c.state.FindIndexState(ctx, reg.IndexURL)
	if err != nil {
		return err // DB error → abort the provider pass (Java: unchecked, → provider_error)
	}
	if stored != nil && strings.EqualFold(result.Digest, stored.Digest) && strings.EqualFold(stored.SyncStatus, "success") {
		slog.Info(logging.IndexUnchanged, "provider", name, "registry", registry, "pushed", 0)
		return nil
	}
	slog.Info(logging.IndexChanged, "provider", name, "registry", registry)
	idx := result.Index
	slog.Info(logging.IndexVerified, "provider", name, "registry", registry, "records", len(idx.Records))

	decisions, err := c.differ.Diff(ctx, idx)
	if err != nil {
		return err // Differ reads catalog_part_state; a DB error aborts the pass (Java: unchecked)
	}

	// Decide + act per catalog record. Each PUSH record pushes its parts INDIVIDUALLY (one HTTP
	// call per part), so outcomes are collected at the part grain across all records.
	ackedParts := 0
	var failures []partOutcome
	for _, d := range decisions {
		catalogID := d.Record.Details.CatalogID
		switch d.Action {
		case ActionSkipUnchanged:
			slog.Info(logging.CatalogUnchanged, "catalogId", catalogID)
		case ActionSkipInactive:
			slog.Info(logging.CatalogInactive, "catalogId", catalogID, "detail", d.Detail)
			c.feedback.Record(domain, catalogID, "validate", "status_not_active", d.Detail)
		case ActionSkipNonPublic:
			slog.Info(logging.CatalogNonPublic, "catalogId", catalogID, "detail", d.Detail)
			c.feedback.Record(domain, catalogID, "validate", "non_public", d.Detail)
		case ActionSkipRollback:
			slog.Warn(logging.CatalogRollback, "catalogId", catalogID, "detail", d.Detail)
			c.feedback.Record(domain, catalogID, "validate", "version_rollback", d.Detail)
		case ActionRetire:
			slog.Info(logging.CatalogRetired, "catalogId", catalogID, "detail", d.Detail)
			c.feedback.Record(domain, catalogID, "validate", "retired_skipped", d.Detail)
		case ActionPush:
			outcomes, pushErr := c.pushCatalog(ctx, domain, d)
			if pushErr != nil {
				return pushErr // a state-write (DB) failure aborts the pass, like Java
			}
			for _, o := range outcomes {
				if o.acked {
					ackedParts++
				} else {
					failures = append(failures, o)
				}
			}
		}
	}

	// Roll the part outcomes up to an index-level status. Advance the index digest on EVERY pass;
	// retry is gated on sync_status (see FindIndexState check above), so a partial/failed index is
	// re-diffed next poll and Differ re-pushes only the still-failed parts.
	if len(failures) == 0 {
		if err := c.state.UpsertIndexState(ctx, reg.IndexURL, result.Digest, idx.NextUpdate, domain); err != nil {
			return err
		}
		slog.Info(logging.ProviderDone, "provider", name, "registry", registry,
			"pushed", ackedParts, "syncStatus", "success", "stateUpdated", true)
	} else {
		status := "failed"
		if ackedParts > 0 {
			status = "partial"
		}
		if err := c.state.RecordIndexOutcome(ctx, reg.IndexURL, result.Digest, idx.NextUpdate, domain, status, failuresJSON(failures)); err != nil {
			return err
		}
		slog.Warn(logging.ProviderRetry, "provider", name, "registry", registry,
			"pushed", ackedParts, "failed", len(failures), "syncStatus", status, "stateUpdated", false)
	}
	return nil
}

// partOutcome is the result of pushing ONE catalog part. httpStatus is nil when the failure was pre-HTTP.
type partOutcome struct {
	catalogID  string
	partURL    string
	acked      bool
	httpStatus *int
	detail     string
}

// pushCatalog pushes each changed part of a catalog as its OWN HTTP call (message.catalogs=[part]),
// so the publish pipeline's default MERGE accumulates parts into one catalog by id and a per-part
// failure isolates to that part. Returns one outcome per changed part.
//
// A non-nil error means a state-store (DB) write failed — the caller aborts the whole provider
// pass (Java parity). Fetch/verify/push failures are NOT errors here: they are normal per-part
// failure outcomes that feed the partial/failed rollup.
func (c *Crawler) pushCatalog(ctx context.Context, domain string, d Decision) ([]partOutcome, error) {
	catalogID := d.Record.Details.CatalogID
	version := d.Record.Details.Version
	slog.Info(logging.CatalogChanged, "catalogId", catalogID, "parts", len(d.ChangedParts))

	outcomes := make([]partOutcome, 0, len(d.ChangedParts))
	for _, part := range d.ChangedParts {
		o, err := c.pushPart(ctx, domain, catalogID, version, part)
		if err != nil {
			return outcomes, err // abandon remaining parts, like the Java exception path
		}
		outcomes = append(outcomes, o)
	}
	return outcomes, nil
}

// pushPart fetches+verifies ONE part, pushes it in a single-part envelope, and persists its state on
// 200. Returns a non-nil error ONLY when the state-store write itself fails (which aborts the pass,
// matching Java's unchecked StateStore exception); fetch/verify/push failures return a failed
// outcome with a nil error.
func (c *Crawler) pushPart(ctx context.Context, domain, catalogID string, version int64, part model.Part) (partOutcome, error) {
	body, err := c.fetcher.FetchVerified(ctx, part.URL, part.Digest)
	if err != nil {
		var dm *DigestMismatchError
		if errors.As(err, &dm) {
			slog.Warn(logging.CatalogDigestMismatch, "catalogId", catalogID, "reason", dm.Error())
			c.feedback.Record(domain, catalogID, "verify", "digest_mismatch", dm.Error())
			return partOutcome{catalogID, part.URL, false, nil, "digest_mismatch: " + dm.Error()}, nil
		}
		slog.Warn(logging.CatalogFetchFailed, "catalogId", catalogID, "error", err.Error())
		c.feedback.Record(domain, catalogID, "fetch", "fetch_error", err.Error())
		return partOutcome{catalogID, part.URL, false, nil, "fetch_error: " + err.Error()}, nil
	}

	result, err := c.pusher.Push(ctx, domain, [][]byte{body}) // one part per call
	if err != nil {
		slog.Warn(logging.CatalogPushRejected, "catalogId", catalogID, "error", err.Error())
		c.feedback.Record(domain, catalogID, "push", "push_error", err.Error())
		return partOutcome{catalogID, part.URL, false, nil, "push_error: " + err.Error()}, nil
	}
	if !result.Ack {
		slog.Warn(logging.CatalogPushRejected, "catalogId", catalogID, "detail", result.Detail)
		c.feedback.Record(domain, catalogID, "push", "push_nack", result.Detail)
		st := result.Status
		return partOutcome{catalogID, part.URL, false, &st, result.Detail}, nil
	}

	// 200 Ack = accepted for async processing. Proven-enqueued → advance part state. A failure here
	// is a hard error that aborts the whole provider pass (Java lets the StateStore exception
	// propagate to runIndexPass → provider_error), rather than being retried per-part.
	if err := c.state.UpsertPart(ctx, part.URL, catalogID, version, part.Digest, part.LastModified, domain); err != nil {
		return partOutcome{}, fmt.Errorf("upsert catalog_part_state for %s: %w", part.URL, err)
	}
	slog.Info(logging.CatalogPushed, "catalogId", catalogID, "version", version, "url", part.URL, "status", result.Status)
	st := result.Status
	return partOutcome{catalogID, part.URL, true, &st, ""}, nil
}

// failuresJSON serializes failed parts to the error_detail array: [{catalogId, partUrl, httpStatus, detail}, ...].
func failuresJSON(failures []partOutcome) string {
	type failEntry struct {
		CatalogID  string `json:"catalogId"`
		PartURL    string `json:"partUrl"`
		HTTPStatus *int   `json:"httpStatus,omitempty"`
		Detail     string `json:"detail"`
	}
	arr := make([]failEntry, 0, len(failures))
	for _, o := range failures {
		arr = append(arr, failEntry{o.catalogID, o.partURL, o.httpStatus, o.detail})
	}
	b, err := json.Marshal(arr)
	if err != nil {
		return `[{"detail":"error_detail serialization failed: ` + err.Error() + `"}]`
	}
	return string(b)
}

func (c *Crawler) getCache(k string) []Resolved {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.cache[k]
}

func (c *Crawler) putCache(k string, v []Resolved) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.cache[k] = v
}
