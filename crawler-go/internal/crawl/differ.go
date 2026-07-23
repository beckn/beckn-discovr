package crawl

import (
	"context"
	"fmt"
	"strings"

	"github.com/beckn/beckn-discovr/crawler-go/internal/model"
	"github.com/beckn/beckn-discovr/crawler-go/internal/state"
)

// Action is the decision for one catalog record. Ported from the Java Differ.Action enum.
type Action string

const (
	ActionPush          Action = "PUSH"
	ActionRetire        Action = "RETIRE"
	ActionSkipUnchanged Action = "SKIP_UNCHANGED"
	ActionSkipNonPublic Action = "SKIP_NON_PUBLIC"
	ActionSkipRollback  Action = "SKIP_ROLLBACK"
	ActionSkipInactive  Action = "SKIP_INACTIVE"
)

// Decision is what to do with one catalog record. ChangedParts is populated only for PUSH.
type Decision struct {
	Record       model.Record
	Action       Action
	ChangedParts []model.Part
	Detail       string
}

// PartStore is the subset of state.Store the Differ needs — an interface so tests can inject a fake.
type PartStore interface {
	FindPart(ctx context.Context, partURL string) (*state.PartState, error)
}

// Differ compares each index record against stored state and decides what to do. Pure decision
// logic — no fetching or side effects (that's the Crawler's job).
type Differ struct {
	state PartStore
}

// NewDiffer builds a Differ over the state store (or any PartStore).
func NewDiffer(s PartStore) *Differ { return &Differ{state: s} }

// Diff returns one Decision per record in the index.
func (d *Differ) Diff(ctx context.Context, idx model.Index) ([]Decision, error) {
	decisions := make([]Decision, 0, len(idx.Records))
	for _, rec := range idx.Records {
		dec, err := d.decide(ctx, rec)
		if err != nil {
			return nil, err
		}
		decisions = append(decisions, dec)
	}
	return decisions, nil
}

func (d *Differ) decide(ctx context.Context, rec model.Record) (Decision, error) {
	det := rec.Details

	if det.IsRetired() {
		return Decision{Record: rec, Action: ActionRetire, Detail: "status=RETIRED"}, nil
	}
	// Strict status gate: only ACTIVE catalogs are ingested. Anything neither ACTIVE nor RETIRED
	// (DRAFT, INACTIVE, unknown, blank) is skipped.
	if !det.IsActive() {
		return Decision{Record: rec, Action: ActionSkipInactive, Detail: "status=" + det.Status}, nil
	}
	if !det.IsPublic() {
		return Decision{Record: rec, Action: ActionSkipNonPublic, Detail: "visibility!=public"}, nil
	}

	parts := det.Parts

	// Rollback guard: all parts of a catalog share its version. A decrease vs. any stored part =
	// rollback/tampering → skip the whole record.
	for _, part := range parts {
		stored, err := d.state.FindPart(ctx, part.URL)
		if err != nil {
			return Decision{}, err
		}
		if stored != nil && det.Version < stored.Version {
			return Decision{
				Record: rec, Action: ActionSkipRollback,
				Detail: fmt.Sprintf("version %d < stored %d", det.Version, stored.Version),
			}, nil
		}
	}

	// Changed = never seen, or the stored digest differs from the announced one.
	var changed []model.Part
	for _, part := range parts {
		stored, err := d.state.FindPart(ctx, part.URL)
		if err != nil {
			return Decision{}, err
		}
		if stored == nil || !strings.EqualFold(part.Digest, stored.Digest) {
			changed = append(changed, part)
		}
	}
	if len(changed) == 0 {
		return Decision{Record: rec, Action: ActionSkipUnchanged, Detail: "all parts unchanged"}, nil
	}
	return Decision{
		Record: rec, Action: ActionPush, ChangedParts: changed,
		Detail: fmt.Sprintf("%d changed part(s)", len(changed)),
	}, nil
}
