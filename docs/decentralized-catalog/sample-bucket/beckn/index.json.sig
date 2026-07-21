PLACEHOLDER — detached signature over index.json.

Signature verification is DEFERRED for the POC (see the design doc, section "Scope").
In the full design this file holds a Beckn HTTP-signature (Ed25519) over the exact bytes
of index.json, verified by the crawler against the signingKey from manifest.json.

For the POC the crawler skips this file; integrity is still guaranteed by the per-part
sha256 digests inside index.json.
