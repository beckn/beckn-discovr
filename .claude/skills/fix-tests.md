---
description: Read current test failures in Beckn Discovr, diagnose root cause, apply minimal fixes, and re-run until passing. Pass a job name to focus: /fix-tests catalog-discover-job
---

Fix failing tests in the Beckn Discovr project.

**Steps:**

1. **Find failures** — read `build/test-results/test/TEST-*.xml` for the target job, or run `./gradlew test 2>&1 | grep -A 10 "FAILED"`.

2. **Diagnose** — read the failing file at the indicated line. Common patterns:
   - Fixture JSON still has v1 field names: `transaction_id`→`transactionId`, `bap_id`→`bapId`, `bap_uri`→`bapUri`, `network_id`→`networkId`, `beckn:id`→`id`
   - `network_id` array `["x"]` → plain string `"x"` (networkId changed to String in v2.0)
   - ACK/NACK are wrapped in `message`: assert `$.message.status` (not `$.status`/`$.ack_status`)
   - Error fields: `$.message.error.code` / `$.message.error.message` (NOT `errorCode`/`errorMessage`)
   - Assert `$.message.messageId` AND `$.message.transactionId` are echoed from the request context on both ACK and NACK
   - Test asserts `$.error.paths` → remove (not in v2.0 NACK)
   - Test asserts `$.timestamp` in ACK body → remove
   - Test calls `context.setCoreVersion(...)` → remove (field deleted in v2.0)
   - Test calls `context.setNetworkId(List.of(...))` → change to `context.setNetworkId("...")` (String)
   - JSON path uses `beckn:items`, `beckn:offers`, `beckn:id` → use `items`, `offers`, `id`
   - Spring bean not found → check `@Component`/`@Bean`

3. **Fix minimally** — only change the broken line(s).

4. **Verify** — `./gradlew compileJava && ./gradlew compileTestJava && ./gradlew test`

5. **Report**:
   ```
   Fixed: <file>:<line> — <what changed>
   Test result: N passed, M failed
   ```

Max 3 fix-compile-test rounds. Report what remains after 3 rounds and stop.
