package config

import (
	"testing"
	"time"
)

func TestParseDuration(t *testing.T) {
	cases := []struct {
		in      string
		want    time.Duration
		wantErr bool
	}{
		{"1d", 24 * time.Hour, false},
		{"7d", 7 * 24 * time.Hour, false},
		{"1m", time.Minute, false},
		{"30s", 30 * time.Second, false},
		{"500ms", 500 * time.Millisecond, false},
		{"1h30m", 90 * time.Minute, false},
		{"bogus", 0, true},
	}
	for _, c := range cases {
		got, err := parseDuration(c.in)
		if c.wantErr {
			if err == nil {
				t.Errorf("parseDuration(%q) expected error", c.in)
			}
			continue
		}
		if err != nil {
			t.Errorf("parseDuration(%q) unexpected error: %v", c.in, err)
		}
		if got != c.want {
			t.Errorf("parseDuration(%q) = %v, want %v", c.in, got, c.want)
		}
	}
}

func TestBuildDSNFromJDBC(t *testing.T) {
	dsn, display, err := buildDSN("jdbc:postgresql://postgres-discovery:5432/catalog_db", "catalog_user", "catalog123")
	if err != nil {
		t.Fatalf("buildDSN error: %v", err)
	}
	want := "postgres://catalog_user:catalog123@postgres-discovery:5432/catalog_db?sslmode=disable"
	if dsn != want {
		t.Errorf("dsn = %q, want %q", dsn, want)
	}
	if display != "postgres-discovery:5432/catalog_db" {
		t.Errorf("display = %q", display)
	}
}

func TestBuildDSNMissing(t *testing.T) {
	if _, _, err := buildDSN("", "u", "p"); err == nil {
		t.Error("expected error for empty CRAWLER_DB_URL")
	}
}
