package state

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"log/slog"
	"sort"
)

//go:embed migrations/*.sql
var migrationFS embed.FS

// Migrate applies the embedded schema migrations in filename order. Every statement is written
// idempotently (CREATE TABLE / ADD COLUMN IF NOT EXISTS), so this is safe to run on every startup
// and coexists with the Java service's Flyway history — whichever runs first creates the tables,
// the other no-ops. (Simpler than pulling in a migration library for four idempotent files.)
func Migrate(ctx context.Context, db *sql.DB) error {
	entries, err := migrationFS.ReadDir("migrations")
	if err != nil {
		return err
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		if !e.IsDir() {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names) // V1__… < V2__… < V3__… < V4__…

	for _, name := range names {
		sqlBytes, readErr := migrationFS.ReadFile("migrations/" + name)
		if readErr != nil {
			return readErr
		}
		if _, execErr := db.ExecContext(ctx, string(sqlBytes)); execErr != nil {
			return fmt.Errorf("migration %s: %w", name, execErr)
		}
		slog.Info("crawler.migration.applied", "migration", name)
	}
	return nil
}
