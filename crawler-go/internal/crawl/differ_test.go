package crawl

import (
	"context"
	"testing"

	"github.com/beckn/beckn-discovr/crawler-go/internal/model"
	"github.com/beckn/beckn-discovr/crawler-go/internal/state"
)

// fakeStore lets us drive Differ decisions without a database.
type fakeStore struct{ parts map[string]*state.PartState }

func (f fakeStore) FindPart(_ context.Context, url string) (*state.PartState, error) {
	return f.parts[url], nil
}

func rec(status, visibility string, version int64, parts ...model.Part) model.Record {
	return model.Record{Details: model.Details{
		CatalogID: "CAT-1", Version: version, Status: status, Visibility: visibility, Parts: parts,
	}}
}

func part(url, digest string) model.Part { return model.Part{URL: url, Digest: digest} }

func decideOne(t *testing.T, store PartStore, r model.Record) Decision {
	t.Helper()
	decs, err := NewDiffer(store).Diff(context.Background(), model.Index{Records: []model.Record{r}})
	if err != nil {
		t.Fatal(err)
	}
	return decs[0]
}

func TestDiffer(t *testing.T) {
	empty := fakeStore{parts: map[string]*state.PartState{}}

	t.Run("never seen -> PUSH all parts", func(t *testing.T) {
		d := decideOne(t, empty, rec("ACTIVE", "public", 1, part("u1", "sha-256:a"), part("u2", "sha-256:b")))
		if d.Action != ActionPush || len(d.ChangedParts) != 2 {
			t.Fatalf("got %s parts=%d", d.Action, len(d.ChangedParts))
		}
	})

	t.Run("stored & matching -> SKIP_UNCHANGED", func(t *testing.T) {
		store := fakeStore{parts: map[string]*state.PartState{"u1": {Version: 1, Digest: "sha-256:a"}}}
		d := decideOne(t, store, rec("ACTIVE", "public", 1, part("u1", "sha-256:a")))
		if d.Action != ActionSkipUnchanged {
			t.Fatalf("got %s", d.Action)
		}
	})

	t.Run("digest changed -> PUSH only changed", func(t *testing.T) {
		store := fakeStore{parts: map[string]*state.PartState{
			"u1": {Version: 1, Digest: "sha-256:a"}, // unchanged
		}}
		d := decideOne(t, store, rec("ACTIVE", "public", 1, part("u1", "sha-256:a"), part("u2", "sha-256:NEW")))
		if d.Action != ActionPush || len(d.ChangedParts) != 1 || d.ChangedParts[0].URL != "u2" {
			t.Fatalf("got %s parts=%v", d.Action, d.ChangedParts)
		}
	})

	t.Run("RETIRED -> RETIRE", func(t *testing.T) {
		if d := decideOne(t, empty, rec("RETIRED", "public", 1, part("u1", "sha-256:a"))); d.Action != ActionRetire {
			t.Fatalf("got %s", d.Action)
		}
	})

	t.Run("DRAFT -> SKIP_INACTIVE", func(t *testing.T) {
		d := decideOne(t, empty, rec("DRAFT", "public", 1, part("u1", "sha-256:a")))
		if d.Action != ActionSkipInactive || d.Detail != "status=DRAFT" {
			t.Fatalf("got %s detail=%q", d.Action, d.Detail)
		}
	})

	t.Run("non-public -> SKIP_NON_PUBLIC", func(t *testing.T) {
		if d := decideOne(t, empty, rec("ACTIVE", "private", 1, part("u1", "sha-256:a"))); d.Action != ActionSkipNonPublic {
			t.Fatalf("got %s", d.Action)
		}
	})

	t.Run("version rollback -> SKIP_ROLLBACK", func(t *testing.T) {
		store := fakeStore{parts: map[string]*state.PartState{"u1": {Version: 5, Digest: "sha-256:a"}}}
		d := decideOne(t, store, rec("ACTIVE", "public", 2, part("u1", "sha-256:NEW")))
		if d.Action != ActionSkipRollback {
			t.Fatalf("got %s", d.Action)
		}
	})
}
