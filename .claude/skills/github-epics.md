---
description: Quick reference for turning a requirement into GitHub Epics + tasks on Beckn Discovr Project 52 with Release/Sprint fields. Prefer the github-epics agent for the full approval-gated workflow.
---

## When to use

- You have bullets or a rough scope and need **Epics + sub-tasks** on [Project 52](https://github.com/orgs/beckn/projects/52).
- You want consistent Markdown bodies and **no GitHub writes until the user approves**.

## Prefer the agent

Invoke the **`github-epics`** agent for: clarifying questions → proposal → **wait for approval** → `gh` execution.

## Constants (Discovr)

| Item | Value |
|------|--------|
| Repo | `beckn/beckn-discovr` |
| Project # | `52` |
| Owner flag | `--owner beckn` |

## Body shape

- **Epic**: `## Summary`, `## Tracking` (`- [ ] #NN ...`), `## Child tasks`.
- **Task**: `## Summary`, `## Epic` (`- #<epic>`), `## Acceptance criteria`.

## gh patterns

- **Real newlines**: `gh issue create -R beckn/beckn-discovr -t "Title" --body "$(cat <<'EOF' ... EOF)"`
- **Field IDs**: `gh project field-list 52 --owner beckn --format json`
- **Add to project**: `gh project item-add 52 --owner beckn --url <issue-url>`
- **Duplicate add**: ignore error; fix fields with `item-edit` if needed.

## Auth

`gh auth refresh -s read:project,project`
