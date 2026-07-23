// Command crawler is the Decentralized Catalog Crawler (POC), Go port.
//
// It periodically pulls provider-hosted DeDi files, verifies the digest chain, and feeds changed
// catalogs into the discover /catalog/push pipeline on two cadences (manifest refresh + index poll).
// Behaviour, config env vars, DB schema, and log-event names match the Java service — it is a
// drop-in replacement.
package main

import (
	"context"
	"database/sql"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib" // registers the "pgx" database/sql driver

	"github.com/beckn/beckn-discovr/crawler-go/internal/config"
	"github.com/beckn/beckn-discovr/crawler-go/internal/crawl"
	"github.com/beckn/beckn-discovr/crawler-go/internal/feedback"
	"github.com/beckn/beckn-discovr/crawler-go/internal/httpclient"
	"github.com/beckn/beckn-discovr/crawler-go/internal/logging"
	"github.com/beckn/beckn-discovr/crawler-go/internal/scheduler"
	"github.com/beckn/beckn-discovr/crawler-go/internal/source"
	"github.com/beckn/beckn-discovr/crawler-go/internal/state"
)

func main() {
	logging.Setup()

	cfg, err := config.Load()
	if err != nil {
		slog.Error("crawler.startup.failed", "error", err.Error())
		os.Exit(1)
	}

	db, err := sql.Open("pgx", cfg.DBDSN)
	if err != nil {
		slog.Error("crawler.startup.failed", "error", "open db: "+err.Error())
		os.Exit(1)
	}
	defer db.Close()
	db.SetMaxOpenConns(5)
	db.SetConnMaxIdleTime(5 * time.Minute)

	pingCtx, cancelPing := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancelPing()
	if err := db.PingContext(pingCtx); err != nil {
		slog.Error("crawler.startup.failed", "error", "ping db: "+err.Error())
		os.Exit(1)
	}
	migrateCtx, cancelMigrate := context.WithTimeout(context.Background(), 30*time.Second)
	if err := state.Migrate(migrateCtx, db); err != nil {
		cancelMigrate()
		slog.Error("crawler.startup.failed", "error", "migrate: "+err.Error())
		os.Exit(1)
	}
	cancelMigrate()

	logStartupConfig(cfg)

	// Wire the engine (constructor injection, like the Spring beans).
	st := state.New(db)
	httpc := httpclient.New(cfg.HTTPTimeout, cfg.MaxPartBytes, cfg.CacheBust)
	crawler := crawl.New(
		source.New(cfg, db),
		crawl.NewManifestResolver(httpc),
		crawl.NewIndexPoller(httpc),
		crawl.NewDiffer(st),
		crawl.NewFetcher(httpc),
		crawl.NewPusher(httpc, cfg.PushEndpoint),
		st,
		feedback.New(cfg.FeedbackLogPath),
	)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if !cfg.SchedulerEnabled {
		slog.Info("crawler.scheduler.disabled")
		<-ctx.Done()
		return
	}

	wg := scheduler.Start(ctx, crawler, cfg.ManifestRefreshInterval, cfg.IndexPollInterval)
	<-ctx.Done() // SIGINT/SIGTERM
	slog.Info("crawler.shutdown")
	wg.Wait() // let the in-flight cadence runs finish (bounded by the HTTP timeout)
}

// logStartupConfig emits the effective configuration once, right after boot — the Go equivalent of
// the Java StartupLogger. providers is only relevant in config mode.
func logStartupConfig(cfg config.Config) {
	// Same field set as the Java StartupLogger (providers only in config mode).
	attrs := []any{"source", cfg.Source}
	if cfg.Source == "config" {
		attrs = append(attrs, "providers", cfg.Providers)
	}
	attrs = append(attrs,
		"pushEndpoint", cfg.PushEndpoint,
		"manifestRefreshInterval", cfg.ManifestRefreshInterval.String(),
		"indexPollInterval", cfg.IndexPollInterval.String(),
		"httpTimeout", cfg.HTTPTimeout.String(),
		"maxPartBytes", cfg.MaxPartBytes,
		"feedbackLog", cfg.FeedbackLogPath,
	)
	slog.Info(logging.StartupConfig, attrs...)
}
