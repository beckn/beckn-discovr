// Package logging sets up structured JSON logging and holds the stable log-event names.
//
// Mirrors the Java crawler's convention (and the discover/publish jobs): the log MESSAGE is a
// stable dotted event id (e.g. "crawler.index.changed") and the data rides along as key/value
// attrs — here via slog's variadic args, e.g. slog.Info(EventIndexChanged, "provider", name).
// Output keys are renamed to match the Java logstash JSON as closely as Go's slog allows.
package logging

import (
	"log/slog"
	"os"
)

// Setup installs a JSON slog logger as the process default. Every record carries service=crawler,
// and the standard keys are renamed (msg->message, time->@timestamp) to line up with the Java logs.
func Setup() {
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
		ReplaceAttr: func(_ []string, a slog.Attr) slog.Attr {
			switch a.Key {
			case slog.TimeKey:
				// Match the Java logstash pattern: yyyy-MM-dd'T'HH:mm:ss.SSSZ (millisecond precision).
				a.Key = "@timestamp"
				a.Value = slog.StringValue(a.Value.Time().Format("2006-01-02T15:04:05.000Z0700"))
			case slog.MessageKey:
				a.Key = "message"
			}
			return a
		},
	})
	slog.SetDefault(slog.New(handler).With("service", "crawler"))
}

// Log-event names — kept identical to the Java LogEvent constants so dashboards/queries port over.
const (
	// startup
	StartupConfig = "crawler.startup.config"

	// manifest refresh (long cadence — learns provider identity + index location)
	ManifestRefreshStarted   = "crawler.manifest.refresh.started"
	ManifestRefreshCompleted = "crawler.manifest.refresh.completed"
	ManifestRefreshed        = "crawler.manifest.refreshed"
	ManifestRefreshFailed    = "crawler.manifest.refresh.failed"
	// integrity checkpoint at manifest read (startup + daily): does the live index match the digest?
	ManifestIndexVerified = "crawler.manifest.index.verified"
	ManifestIndexMismatch = "crawler.manifest.index.mismatch"

	// index pass lifecycle (short cadence — polls the index for catalog changes)
	PassStarted   = "crawler.pass.started"
	PassCompleted = "crawler.pass.completed"
	PassFailed    = "crawler.pass.failed"

	// per-provider
	ProviderChecking = "crawler.provider.checking"
	ProviderFailed   = "crawler.provider.failed"
	ProviderDone     = "crawler.provider.done"
	ProviderRetry    = "crawler.provider.retry"

	// manifest / index
	RegistryNotLive      = "crawler.registry.not_live"
	IndexUnchanged       = "crawler.index.unchanged"
	IndexChanged         = "crawler.index.changed"
	IndexVerified        = "crawler.index.verified"
	IndexIntegrityFailed = "crawler.index.integrity.failed"

	// per-catalog decisions
	CatalogUnchanged      = "crawler.catalog.unchanged"
	CatalogInactive       = "crawler.catalog.skipped.inactive"
	CatalogNonPublic      = "crawler.catalog.skipped.nonpublic"
	CatalogRollback       = "crawler.catalog.skipped.rollback"
	CatalogRetired        = "crawler.catalog.retired"
	CatalogChanged        = "crawler.catalog.changed"
	PartFetched           = "crawler.part.fetched"
	CatalogPushed         = "crawler.catalog.pushed"
	CatalogPushRejected   = "crawler.catalog.push.rejected"
	CatalogDigestMismatch = "crawler.catalog.digest.mismatch"
	CatalogFetchFailed    = "crawler.catalog.fetch.failed"

	// feedback
	Feedback            = "crawler.feedback"
	FeedbackWriteFailed = "crawler.feedback.write.failed"
)
