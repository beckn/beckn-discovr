---
description: Compile and run all Beckn Discovr tests (all 3 Java jobs) and print a pass/fail table. Optionally pass a job name to run only that job: /run-tests catalog-discover-job
---

Run tests for the Beckn Discovr project. If a specific job name was passed, run only that job. Otherwise run all three.

**Jobs and commands:**

| Job | Command |
|-----|---------|
| catalog-discover-job | `cd /Users/manju/Documents/Projects/beckn/beckn-discovr/jobs/catalog-discover-job && ./gradlew test 2>&1 \| tail -15` |
| catalog-publish-job | `cd /Users/manju/Documents/Projects/beckn/beckn-discovr/jobs/catalog-publish-job && ./gradlew test 2>&1 \| tail -10` |
| response-dispatcher | `cd /Users/manju/Documents/Projects/beckn/beckn-discovr/jobs/response-dispatcher && ./gradlew test 2>&1 \| tail -10` |

Run each relevant job's test command. Then print a summary table:

```
## Test Results — Beckn Discovr

| Job | Tests | Passed | Failed | Status |
|-----|-------|--------|--------|--------|
| catalog-discover-job | ... | ... | ... | PASS/FAIL |
| catalog-publish-job | ... | ... | ... | PASS/FAIL |
| response-dispatcher | ... | ... | ... | PASS/FAIL |

### Failures (if any)
**ClassName.methodName**: <message>
```

If there are failures, suggest: "Run `/fix-tests` to diagnose and fix, or invoke the `debug` agent."
