---
description: Review recently changed files in Beckn Discovr for correctness, security, Spring usage, Beckn v2.0 compliance, and test coverage. Returns CRITICAL/HIGH/MEDIUM/LOW findings and a verdict.
---

Perform a focused code review of recently changed files in the Beckn Discovr project.

**Steps:**

1. Run `git diff --name-only HEAD~1 HEAD 2>/dev/null || git diff --name-only --cached` to find changed files.

2. Read each changed file.

3. Review for findings:
   - **CRITICAL**: SQL injection, SSRF (HTTP POST to unvalidated URL), hardcoded secrets, `@Autowired` field injection, blocking `.get()` on Kafka futures, `Thread.sleep()` in tests
   - **HIGH**: No auth validation before processing, missing error log on exception path, test with no specific field assertion, missing idempotency test
   - **MEDIUM**: `new ObjectMapper()`, hardcoded topic/URL strings, `@ConfigurationProperties` without `@Validated`, no spatial/JSONPath filter pre-compilation at startup
   - **LOW**: `collect(toList())` instead of `.toList()`, magic numbers, missing `var`

4. Beckn v2.0 checks:
   - Context `@JsonProperty` uses camelCase (`transactionId`, `bapId`, `bapUri`) — not snake_case
   - No `beckn:` prefixes in catalog/item/offer field names
   - ACK = `{"status":"ACK"}` — not `{"ack_status":"ACK",...}`
   - NACK has `error.errorCode`/`error.errorMessage` — not `error.code`/`error.paths`
   - HTTP 409 handled as `AckNoCallback` — not treated as error

5. Output:
   ```
   ## Review — Beckn Discovr
   Files: [list]
   Findings: CRITICAL=N HIGH=N MEDIUM=N LOW=N
   Verdict: APPROVE | REQUEST CHANGES | BLOCK

   ### [SEVERITY] Title
   File: path/to/File.java:line
   Issue: ...
   Fix: ...
   ```
