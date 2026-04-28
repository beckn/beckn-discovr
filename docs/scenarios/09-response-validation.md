# 09 — Response Validation (Cross-Cutting)

## Overview
Cross-cutting checks applied to every HTTP response received during the verification run.

## Checks

| # | Check | Verified by |
|---|-------|-------------|
| RV-01 | Discover response `action` = `"on_discover"` | SC-06, SC-08 |
| RV-02 | Response uses `resources` field (not `items`) | SC-06, SC-07 |
| RV-03 | No `@context`/`@type` on Resource or Descriptor — only on `resourceAttributes`/`offerAttributes` | SC-06 |
| RV-04 | Push ACK is HTTP 202 with `{"status":"ACK"}` | SC-01 |
| RV-05 | Push NACK has `status: "NACK"` + `error.errorCode` + `error.errorMessage` | SC-02, SC-03 |
| RV-06 | No `publishDirectives` in any stored payload | SC-39 (DB check) |
| RV-07 | MERGE mode: existing resources preserved after upsert | SC-19 |
| RV-08 | FULL mode: old resources completely removed from DB + ES | SC-25, SC-25a |
| RV-09 | Cross-BPP: BPP identity never overwritten by offer publisher | SC-29 |
| RV-10 | Structured logs use dot.separated.lowercase event names | SC-40, SC-41 |

## Rules
- Mark RV as PASS only if ALL sub-checks passed across ALL scenarios
- If any scenario that feeds a RV check was SKIPPED, mark that RV as SKIP
