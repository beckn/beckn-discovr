package digest

import "testing"

func TestSHA256KnownValue(t *testing.T) {
	// sha-256 of the empty byte slice.
	got := SHA256([]byte(""))
	want := "sha-256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	if got != want {
		t.Fatalf("SHA256(\"\") = %q, want %q", got, want)
	}
}

func TestMatches(t *testing.T) {
	body := []byte("catalog bytes\n") // trailing newline is part of the bytes, as on disk
	good := SHA256(body)

	cases := []struct {
		name     string
		bytes    []byte
		expected string
		want     bool
	}{
		{"exact", body, good, true},
		{"case-insensitive hex", body, "sha-256:" + upper(good[len("sha-256:"):]), true},
		{"surrounding whitespace trimmed", body, "  " + good + "  ", true},
		{"wrong bytes", []byte("other"), good, false},
		{"no prefix", body, good[len("sha-256:"):], false},
		{"blank expected", body, "", false},
		{"trailing-newline sensitive", []byte("catalog bytes"), good, false}, // without \n must not match
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := Matches(c.bytes, c.expected); got != c.want {
				t.Errorf("Matches = %v, want %v", got, c.want)
			}
		})
	}
}

func upper(s string) string {
	b := []byte(s)
	for i, c := range b {
		if c >= 'a' && c <= 'f' {
			b[i] = c - 32
		}
	}
	return string(b)
}
