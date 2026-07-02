---
description: Quick reference for turning a requirement into a GitHub Story + its sub-issue Tasks in the beckn-discovr repo, wired to the milestone, Discovr board (Project 52), and Fabric Engineering pipeline (Project 58). Prefer the github-stories agent for the full approval-gated workflow.
---

## When to use

- You have bullets or a rough scope and need **one Story + its sub-issue Tasks** in **beckn-discovr**.
- You want clear issue bodies, correct issue types/labels/milestone, and **no GitHub writes until the user approves**.

## Prefer the agent

Invoke the **`github-stories`** agent for the full flow: gather milestone/sprints/assignee → plan → **wait for approval** → `gh` execution.

## Rules

- **Repo:** create issues in **`beckn/beckn-discovr` only** (Catalg work → the Catalg repo's own agent).
- **Hierarchy:** **Story → Tasks** only (no epics). Tasks are attached as **native sub-issues** of the Story.
- **Every issue** goes to **Project 52** (Discovr board, Sprint + Status=Ready) **and Project 58** (Fabric pipeline, iteration + Status=Todo), and is set to the **milestone**.
- **Issue types:** set native org issue type — `Story` on the Story, `Task` on Tasks (discover via `gh api orgs/beckn/issue-types`).
- **Labels:** `story` / `task` + domain (`discovr`, `api`, `discover`, `publish`, `dispatcher`, …).

## Ask upfront (Phase A)

Milestone · Discovr sprint (Proj 52) · Fabric pipeline sprint (Proj 58 iteration) · Assignee(s). Then stop for approval after showing the plan (Phase B).

## Body shape

- **Story**: `## Summary`, `## Scope`, `## Sub-issues (Tasks)` (`- [ ] #NN …`), `## Acceptance criteria`, `## Notes / references`.
- **Task**: `## Summary`, `## Parent Story` (`- #<story>`), `## Acceptance criteria`, `## Implementation notes`.

## gh patterns

- **Real newlines**: `gh issue create -R beckn/beckn-discovr -t "Title" --type "Story" --label story --milestone "<title>" --body "$(cat <<'EOF' ... EOF)"`
- **Issue types**: `gh api orgs/beckn/issue-types --jq '.[].name'` (set via `--type` or GraphQL `updateIssue`)
- **Sub-issue link**: `tid=$(gh api repos/beckn/beckn-discovr/issues/<task#> --jq .id); gh api --method POST repos/beckn/beckn-discovr/issues/<story#>/sub_issues -F sub_issue_id=$tid`
- **Field IDs**: `gh project field-list <#> --owner beckn --format json` (never hardcode)
- **Add + set fields**: `gh project item-add <#> --owner beckn --url <issue-url>` then `gh project item-edit` (one field per call)
- **"Already in project"**: skip `item-add`, still `item-edit` for Sprint

## Auth

If project/issue-type commands fail: `gh auth refresh -h github.com -s read:project,project`
