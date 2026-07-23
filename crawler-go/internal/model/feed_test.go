package model

import (
	"encoding/json"
	"testing"
)

func TestManifestUnmarshal(t *testing.T) {
	const js = `{
	  "type":"dedi-manifest","domain":"https://example.com","name":"Acme Ltd",
	  "files":[{"registry":"beckn-catalogs","url":"https://x/index.json","digest":"sha-256:abc","state":"live"}]
	}`
	var m Manifest
	if err := json.Unmarshal([]byte(js), &m); err != nil {
		t.Fatal(err)
	}
	if m.Name != "Acme Ltd" || m.Domain != "https://example.com" || len(m.Files) != 1 {
		t.Fatalf("bad manifest: %+v", m)
	}
	if !m.Files[0].IsLive() {
		t.Error("files[0] should be live")
	}
}

func TestIndexUnmarshalAndDetailGates(t *testing.T) {
	const js = `{
	  "type":"dedi-file","publisher":{"domain":"https://example.com"},"namespace":"ns",
	  "next_update":"2026-08-04T00:00:00Z",
	  "records":[{"record_name":"CAT-1","details":{
	     "catalogId":"CAT-1","version":3,"catalogType":"REGULAR","status":"ACTIVE","visibility":"public",
	     "updatedAt":"2026-07-22T00:00:00Z",
	     "parts":[{"url":"https://x/p1.json","digest":"sha-256:d1","lastModified":"2026-07-22T00:00:00Z"}]}}]
	}`
	var idx Index
	if err := json.Unmarshal([]byte(js), &idx); err != nil {
		t.Fatal(err)
	}
	if idx.Publisher.Domain != "https://example.com" || idx.NextUpdate != "2026-08-04T00:00:00Z" {
		t.Fatalf("bad index header: %+v", idx)
	}
	if len(idx.Records) != 1 {
		t.Fatal("want 1 record")
	}
	d := idx.Records[0].Details
	if d.CatalogID != "CAT-1" || d.Version != 3 || len(d.Parts) != 1 || d.Parts[0].Digest != "sha-256:d1" {
		t.Fatalf("bad details: %+v", d)
	}
	if !d.IsActive() || d.IsRetired() || !d.IsPublic() {
		t.Errorf("gate mismatch: active=%v retired=%v public=%v", d.IsActive(), d.IsRetired(), d.IsPublic())
	}
}

func TestVisibilityObjectIsNotPublic(t *testing.T) {
	var d Details
	if err := json.Unmarshal([]byte(`{"status":"ACTIVE","visibility":{"networks":["n1"]}}`), &d); err != nil {
		t.Fatal(err)
	}
	if d.IsPublic() {
		t.Error("object visibility must not count as public")
	}
}

func TestRetiredAndInactive(t *testing.T) {
	var retired, draft Details
	_ = json.Unmarshal([]byte(`{"status":"RETIRED","visibility":"public"}`), &retired)
	_ = json.Unmarshal([]byte(`{"status":"DRAFT","visibility":"public"}`), &draft)
	if !retired.IsRetired() {
		t.Error("RETIRED should be retired")
	}
	if draft.IsActive() {
		t.Error("DRAFT should not be active")
	}
}
