// Package scheduler drives the two crawl cadences, mirroring the Java CrawlScheduler:
//   - manifest refresh every ManifestRefreshInterval (long — provider identity + index location)
//   - index poll every IndexPollInterval (short — catalog changes)
//
// Both use fixed-DELAY timing (the interval is measured after each run completes, not from its
// start) and run once immediately on startup. Crucially — like Spring's default single-threaded
// task scheduler — both cadences run on ONE goroutine, so they are serialized and NEVER overlap:
// a manifest refresh and an index poll can't run at the same time. The index poll lazily learns
// the manifest on a cache miss, so no startup ordering is needed.
package scheduler

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/beckn/beckn-discovr/crawler-go/internal/crawl"
	"github.com/beckn/beckn-discovr/crawler-go/internal/logging"
)

// Start launches the scheduler on a single goroutine and returns a WaitGroup the caller can wait on
// for a clean shutdown. The goroutine stops when ctx is cancelled (after the in-flight run returns).
func Start(ctx context.Context, crawler *crawl.Crawler, manifestInterval, indexInterval time.Duration) *sync.WaitGroup {
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		run(ctx, crawler, manifestInterval, indexInterval)
	}()
	return &wg
}

// run is the single-threaded scheduler loop. Both tasks are due at t=0 (manifest first, matching the
// Java registration order); thereafter each re-arms one interval AFTER its own completion (fixed
// delay). Because everything happens on this one goroutine, the two cadences never overlap.
func run(ctx context.Context, crawler *crawl.Crawler, manifestInterval, indexInterval time.Duration) {
	manifestDue := time.Now()
	indexDue := time.Now()
	for {
		if ctx.Err() != nil {
			return
		}
		now := time.Now()
		if !now.Before(manifestDue) {
			safeRun(ctx, crawler.RefreshManifests, logging.ManifestRefreshFailed)
			manifestDue = time.Now().Add(manifestInterval)
		}
		if !now.Before(indexDue) {
			safeRun(ctx, crawler.RunIndexPass, logging.PassFailed)
			indexDue = time.Now().Add(indexInterval)
		}

		next := manifestDue
		if indexDue.Before(next) {
			next = indexDue
		}
		wait := time.Until(next)
		if wait < 0 {
			wait = 0
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
	}
}

// safeRun guards a cadence tick so a panic in one run doesn't kill the goroutine (the Java tasks
// caught and logged; RefreshManifests/RunIndexPass already never return errors — this is a backstop).
func safeRun(ctx context.Context, fn func(context.Context), failEvent string) {
	defer func() {
		if r := recover(); r != nil {
			slog.Error(failEvent, "error", fmt.Sprintf("%v", r))
		}
	}()
	fn(ctx)
}
