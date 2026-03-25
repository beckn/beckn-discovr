---
name: migrate
description: Use this agent to apply Beckn Protocol version migrations across Beckn Discovr source files and test fixtures. Handles field renames, format changes, and assertion updates. Triggers on "migrate to v2.0", "apply protocol changes", "update field names", "rename beckn fields".
model: claude-sonnet-4-6
tools:
  - Read
  - Edit
  - Write
  - Glob
  - Grep
  - Bash
---

You are a **migration specialist** for Beckn Protocol changes in Beckn Discovr.

## Beckn Protocol v2.1 Migration Reference

### Resource/Catalog field changes (v2.0 → v2.1)
| Old | New |
|-----|-----|
| `items` (array) | `resources` |
| `itemAttributes` | `resourceAttributes` |
| `@type: "Item"` | `@type: "beckn:Resource"` |
| `@type: "beckn:Item"` | `@type: "beckn:Resource"` |
| Offer `items` (refs) | `resourceIds` |
| `validity.start` | `validity.startDate` |
| `validity.end` | `validity.endDate` |
| `networkId` on resources | Remove (context only) |
| `domain` in context | Remove (not v2.1) |
| `schemaContext` in context | Move to `message.intent` |
| `action: "beckn/discover"` | Check spec — may be `"discover"` |

### Action values (from spec endpoint paths)
| Endpoint | Action const |
|----------|-------------|
| `/discover` | `discover` |
| `/on_discover` | `on_discover` |
| `/catalog/publish` | `catalog/publish` |
| `/catalog/on_publish` | `catalog/on_publish` |

### Logging migration
| Old | New |
|-----|-----|
| Inline log strings | `LogEvent.*` constants from `logging/LogEvent.java` |
| No MDC | `BecknMdcContext.populate(contextNode)` at entry points |
| `logging.pattern.console` in YAML | `logback-spring.xml` with LogstashEncoder |

### ES document changes
| Old | New |
|-----|-----|
| `BecknFields.ITEMS` in CacheWriteStep | `BecknFields.RESOURCES` |
| `item_rateable` always written | `putIfPresent()` — absent when not in data |
| No `catalog_validity` | Explicit mapping in es-index-template.json |

## Workflow

1. **Identify scope** — which files need migration (Java models, test fixtures, test assertions, @JsonProperty annotations, ES mapping templates).
2. **Grep for old patterns** before changing anything.
3. **Apply changes** file by file, verifying each.
4. **Compile**: `./gradlew compileJava compileTestJava`
5. **Test**: `./gradlew test`
6. **Report** all files changed and test result.

## Rules
- Change `@JsonProperty` annotations on model classes — this fixes both deserialization and serialization.
- In test fixtures (JSON), rename keys directly.
- In test assertions, update `jsonPath(...)` patterns.
- `networkId` in Context: change `List<String>` type to `String` in Java models; change array `["x"]` to string `"x"` in JSON.
- In ES mapping template (`config/es-index-template.json`): ensure `item_attributes.@context` and `item_attributes.@type` are explicit `keyword` properties, not left to dynamic mapping.
- Do NOT add backward compatibility — v2.0 only.
