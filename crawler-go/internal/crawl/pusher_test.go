package crawl

import (
	"encoding/json"
	"regexp"
	"testing"
)

func TestFirstBppURI(t *testing.T) {
	with := [][]byte{[]byte(`{"id":"CAT-1"}`), []byte(`{"id":"CAT-1","bppUri":"https://bpp.example"}`)}
	if got := firstBppURI(with); got != "https://bpp.example" {
		t.Errorf("firstBppURI = %q", got)
	}
	without := [][]byte{[]byte(`{"id":"CAT-1"}`)}
	if got := firstBppURI(without); got != "" {
		t.Errorf("firstBppURI = %q, want empty", got)
	}
}

func TestNewUUIDFormat(t *testing.T) {
	re := regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	for i := 0; i < 100; i++ {
		if u := newUUID(); !re.MatchString(u) {
			t.Fatalf("newUUID produced invalid v4 uuid: %q", u)
		}
	}
}

// The envelope must carry the raw catalog part(s) under message.catalogs unchanged, with the
// spec-compliant context (mirrors the Java PusherTest.buildsSpecCompliantEnvelope).
func TestEnvelopeShape(t *testing.T) {
	var env envelope
	env.Context.Action = "catalog/publish"
	env.Context.BppID = "https://example.com"
	env.Context.Version = "2.0.0"
	env.Message.Catalogs = []json.RawMessage{json.RawMessage(`{"id":"CAT-1","resources":[]}`)}

	b, err := json.Marshal(env)
	if err != nil {
		t.Fatal(err)
	}
	var back struct {
		Context struct {
			Action, BppID, Version string
		}
		Message struct {
			Catalogs []map[string]any
		}
	}
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatal(err)
	}
	if back.Context.Action != "catalog/publish" || back.Context.Version != "2.0.0" {
		t.Errorf("bad context: %+v", back.Context)
	}
	if len(back.Message.Catalogs) != 1 || back.Message.Catalogs[0]["id"] != "CAT-1" {
		t.Errorf("bad catalogs: %+v", back.Message.Catalogs)
	}
}
