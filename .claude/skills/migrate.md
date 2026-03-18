---
description: Apply Beckn Protocol v2.0 field name migrations to source files and JSON test fixtures in Beckn Discovr. Pass a job name to scope the migration: /migrate catalog-discover-job
---

Apply Beckn Protocol v2.0 migrations to the Beckn Discovr project.

## v2.0 field name reference

### Context (camelCase — all fixtures and @JsonProperty)
| Old | New |
|-----|-----|
| `transaction_id` | `transactionId` |
| `message_id` | `messageId` |
| `bap_id` | `bapId` |
| `bap_uri` | `bapUri` |
| `bpp_id` | `bppId` |
| `bpp_uri` | `bppUri` |
| `network_id` (array) | `networkId` (string) |
| `schema_context` | `schemaContext` |
| `core_version` | **remove** |

### Catalog/Item fields
| Old | New |
|-----|-----|
| `beckn:id` | `id` |
| `beckn:items` | `items` |
| `beckn:offers` | `offers` |
| `beckn:descriptor` | `descriptor` |
| `beckn:provider` | `provider` |
| `beckn:itemAttributes` | `itemAttributes` |
| `schema:name` | `name` |
| `beckn:shortDesc` | `shortDesc` |
| `beckn:longDesc` | `longDesc` |
| `schema:image` | `images` |

### Test assertions
| Old | New |
|-----|-----|
| `$.ack_status` | `$.status` |
| `$.transaction_id` | remove |
| `$.timestamp` (in ACK) | remove |
| `$.error.code` | `$.error.errorCode` |
| `$.error.paths` | remove |
| `$.error.message` | `$.error.errorMessage` |

## Steps

1. Grep for old patterns to find all files that need changes.
2. Update `@JsonProperty` annotations in Java model classes.
3. Update JSON fixture files in `src/test/resources/`.
4. Update test assertions in Java test files.
5. `./gradlew compileJava && ./gradlew compileTestJava`
6. `./gradlew test`
7. Report all files changed and test result.
