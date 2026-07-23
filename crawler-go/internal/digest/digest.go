// Package digest is the SHA-256 integrity check for the DeDi digest chain (design doc §5.7).
//
// A file never carries its own digest — the expected value always comes from the parent file.
// Digests are written "sha-256:<lowercase-hex>". We compute over the exact response bytes and
// compare case-insensitively. Faithful port of the Java DigestUtil.
package digest

import (
	"crypto/sha256"
	"encoding/hex"
	"strings"
)

// Prefix is the algorithm marker every DeDi digest carries.
const Prefix = "sha-256:"

// SHA256 returns "sha-256:<hex>" of the given bytes.
func SHA256(b []byte) string {
	sum := sha256.Sum256(b)
	return Prefix + hex.EncodeToString(sum[:])
}

// Matches reports whether b hashes to expected. expected must carry the "sha-256:" prefix (as it
// appears in the parent DeDi file); the comparison is case-insensitive. A blank expected value is
// never a match.
func Matches(b []byte, expected string) bool {
	e := strings.TrimSpace(expected)
	if e == "" {
		return false
	}
	if !strings.HasPrefix(strings.ToLower(e), Prefix) {
		return false
	}
	return strings.EqualFold(SHA256(b), e)
}
