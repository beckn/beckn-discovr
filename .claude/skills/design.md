---
description: Quickly sketch a design for a small/medium Beckn Discovr change. For major features use the design agent instead. Outputs a single focused proposal with acceptance criteria for user approval.
---

Produce a focused design proposal for the requested change in Beckn Discovr.

**When to use this skill vs the `design` agent:**
- This skill: small/medium changes (< 1 day), single job affected, no new Kafka topics or DB tables
- `design` agent: major features, cross-job changes, new Kafka topics/DB tables, architectural decisions

**Steps:**

1. Read the relevant existing files to understand current patterns.

2. Produce a single focused proposal:

```
## Design Proposal: [title]

### What changes
[2-3 sentences]

### Job affected
[catalog-discover-job / catalog-publish-job / response-dispatcher]

### Files affected
- path/to/File.java — what changes

### Kafka / DB changes
[if any, else "None"]

### Beckn v2.0 compliance
[confirm ACK/NACK format, field names, on_discover structure]

### Acceptance criteria
- [ ] criterion 1
- [ ] criterion 2

### What NOT to do
[guard rails]
```

3. Ask: **"Shall I implement this?"** — wait for user approval before writing any code.

If scope is larger than expected, recommend using the `design` agent instead.
