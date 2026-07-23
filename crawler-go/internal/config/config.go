// Package config loads all crawler settings from the environment — the Go equivalent of the Java
// CrawlerProperties + application.yml. Nothing is hardcoded: providers, endpoint, cadence, timeouts
// and paths all come from the same CRAWLER_* env vars the Java service reads, so this is a drop-in.
package config

import (
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config is the fully-resolved crawler configuration.
type Config struct {
	Providers               []string      // full DeDi manifest URLs (source=config)
	Source                  string        // "config" | "db"
	PushEndpoint            string        // absolute URL of discover /catalog/push
	ManifestRefreshInterval time.Duration // long cadence (provider identity + index location)
	IndexPollInterval       time.Duration // short cadence (catalog changes)
	HTTPTimeout             time.Duration
	MaxPartBytes            int64
	CacheBust               bool
	FeedbackLogPath         string
	SchedulerEnabled        bool // false disables the timers (used in tests)

	DBDSN     string // Go/pgx DSN, converted from the JDBC URL + user/pass
	DBDisplay string // host/db only, for the startup log (no credentials)
}

// Load reads the environment and returns the resolved Config, or an error listing what's missing.
func Load() (Config, error) {
	c := Config{
		Providers:        splitCSV(os.Getenv("CRAWLER_PROVIDERS")),
		Source:           getenv("CRAWLER_SOURCE", "config"),
		PushEndpoint:     os.Getenv("CRAWLER_PUSH_ENDPOINT"),
		MaxPartBytes:     getenvInt64("CRAWLER_MAX_PART_BYTES", 10485760),
		CacheBust:        getenvBool("CRAWLER_HTTP_CACHE_BUST", true),
		FeedbackLogPath:  getenv("CRAWLER_FEEDBACK_LOG_PATH", "./feedback.log"),
		SchedulerEnabled: getenvBool("CRAWLER_SCHEDULER_ENABLED", true),
	}

	var errs []string
	var err error
	if c.ManifestRefreshInterval, err = parseDuration(getenv("CRAWLER_MANIFEST_REFRESH_INTERVAL", "1d")); err != nil {
		errs = append(errs, "CRAWLER_MANIFEST_REFRESH_INTERVAL: "+err.Error())
	}
	if c.IndexPollInterval, err = parseDuration(getenv("CRAWLER_INDEX_POLL_INTERVAL", "1m")); err != nil {
		errs = append(errs, "CRAWLER_INDEX_POLL_INTERVAL: "+err.Error())
	}
	if c.HTTPTimeout, err = parseDuration(getenv("CRAWLER_HTTP_TIMEOUT", "30s")); err != nil {
		errs = append(errs, "CRAWLER_HTTP_TIMEOUT: "+err.Error())
	}
	if c.PushEndpoint == "" {
		errs = append(errs, "CRAWLER_PUSH_ENDPOINT is required")
	}

	dsn, display, dbErr := buildDSN(os.Getenv("CRAWLER_DB_URL"),
		os.Getenv("CRAWLER_DB_USERNAME"), os.Getenv("CRAWLER_DB_PASSWORD"))
	if dbErr != nil {
		errs = append(errs, dbErr.Error())
	}
	c.DBDSN, c.DBDisplay = dsn, display

	if len(errs) > 0 {
		return c, fmt.Errorf("invalid config: %s", strings.Join(errs, "; "))
	}
	return c, nil
}

// buildDSN converts the Java-style JDBC URL (jdbc:postgresql://host:port/db) plus the separate
// username/password env vars into a Go/pgx DSN (postgres://user:pass@host:port/db?sslmode=disable).
// Also accepts an already-native postgres:// URL. sslmode defaults to disable (matches the local POC).
func buildDSN(jdbcURL, user, pass string) (dsn, display string, err error) {
	if jdbcURL == "" {
		return "", "", fmt.Errorf("CRAWLER_DB_URL is required")
	}
	raw := strings.TrimPrefix(jdbcURL, "jdbc:")
	raw = strings.Replace(raw, "postgresql://", "postgres://", 1)
	u, perr := url.Parse(raw)
	if perr != nil {
		return "", "", fmt.Errorf("CRAWLER_DB_URL: %w", perr)
	}
	if user != "" {
		u.User = url.UserPassword(user, pass)
	}
	q := u.Query()
	if q.Get("sslmode") == "" {
		q.Set("sslmode", "disable")
	}
	u.RawQuery = q.Encode()
	return u.String(), u.Host + u.Path, nil
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func getenvInt64(key string, def int64) int64 {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			return n
		}
	}
	return def
}

func getenvBool(key string, def bool) bool {
	if v := os.Getenv(key); v != "" {
		if b, err := strconv.ParseBool(v); err == nil {
			return b
		}
	}
	return def
}

func splitCSV(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if t := strings.TrimSpace(p); t != "" {
			out = append(out, t)
		}
	}
	return out
}

// parseDuration accepts the Spring-style values the Java service uses ("1d", "1m", "30s", "500ms").
// Go's time.ParseDuration handles all of those EXCEPT a day suffix, so "<n>d" is expanded to hours.
func parseDuration(s string) (time.Duration, error) {
	s = strings.TrimSpace(s)
	if strings.HasSuffix(s, "d") {
		n, err := strconv.Atoi(strings.TrimSuffix(s, "d"))
		if err != nil {
			return 0, fmt.Errorf("invalid day duration %q", s)
		}
		return time.Duration(n) * 24 * time.Hour, nil
	}
	return time.ParseDuration(s)
}
