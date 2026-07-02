---
description: Quick reference for turning a requirement into GitHub Stories + tasks across Beckn org Project boards (51 Catalg, 52 Discovr, 58 Pipeline). Prefer the github-epics agent for the full approval-gated workflow.
---

## When to use

- You have bullets or a rough scope and need **Stories + tasks** on Beckn org project boards.
- You want consistent Markdown bodies and **no GitHub writes until the user approves**.

## Prefer the agent

Invoke the **`github-epics`** agent for: gather sprints → proposal → **wait for approval** → `gh` execution.

## Three boards — every issue goes on TWO

| Board | # | Repos | Sprint type |
|-------|---|-------|-------------|
| Beckn Catalg | 51 | beckn-catalg | Single-select |
| Beckn Discovr | 52 | beckn-discovr | Single-select |
| Fabric Pipeline Engineering | 58 | All repos | Iteration |

**Pattern**: Issue lives in home repo → added to home board (51/52) with home sprint → ALSO added to Project 58 with pipeline sprint.

## Terminology

- **Story** = user-facing theme/deliverable. Label: `story`.
- **Task** = concrete unit of work under a Story. Label: `task`.

## Agent workflow

1. **Phase A**: Ask user for Release, Catalg sprint, Discovr sprint, Pipeline sprint, Assignee, Repos
2. **Phase B**: Produce plan with board assignment table (CREATE vs UPDATE per issue per board) — wait for approval
3. **Phase C**: Execute — create issues, add to both boards, set sprints, cross-link

## Body shape

- **Story**: `## Summary`, `## Tracking` (`- [ ] #NN ...`), `## Tasks` (`- #NN ...`), `## Outcomes`.
- **Task**: `## Summary`, `## Acceptance criteria`, `## Story` → `- #<story>`.

## gh patterns

- **Real newlines**: `gh issue create -R <repo> -t "Title" --body "$(cat <<'EOF' ... EOF)"`
- **Field IDs**: `gh project field-list <project#> --owner beckn --format json`
- **Add + set fields**: `gh project item-add <project#> --owner beckn --url <issue-url> --format json -q '.id'` then `gh project item-edit` with `--project-id` and field-specific option (one field per call).
- **"Already in project"**: skip `item-add`, still run `item-edit` for Sprint.

## Auth

If project commands fail: `gh auth refresh -s read:project,project`
