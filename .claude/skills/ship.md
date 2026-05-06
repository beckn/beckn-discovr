---
name: ship
description: Full post-implementation cycle — review code, fix findings, run E2E verification, commit, push, create PR, comment on issues, update ticket status. Use when ready to ship a branch.
user_invocable: true
---

## When to use

When the user says: "ship", "raise PR", "ship this", "create PR and close tickets".

## Phase 1 — Gather context upfront (ask once)

Ask ALL of these in one message before starting:

1. **Which issues are linked?** (fetch titles from git commits/branch name, show with titles)
2. **Target branch for PR?** (e.g., `release-1.0.0-RC1`, `main`)
3. **Assignee?** (e.g., `github-username`)

Wait for answer. Then run autonomously through phases 2-6.

## Phase 2 — Review

Run the `review` agent on all changed files. If CRITICAL or HIGH findings exist, fix them automatically using the `debug` agent. Re-review until clean.

## Phase 3 — E2E Verification

If Docker stack is running, run a quick E2E smoke test:
- Publish → Subscribe → Discover → Pull
- Check cache logs (if applicable)
- Report pass/fail

If all pass, continue. If fail, stop and report.

## Phase 4 — Commit and Push

```bash
git add -A
git commit -m "#<issue> <type>(<scope>): <description>

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
git push origin <branch>
```

## Phase 5 — Create PR

Use `gh pr create` with structured body including:
- Summary (bullet points)
- Issues (Closes #NN / Related #NN)
- What was implemented
- Scenarios verified
- Review findings addressed
- Test results

## Phase 6 — Update tickets

For each linked issue:

1. **Add implementation comment** — what was implemented, PR link
2. **Assign** to the specified assignee
3. **Set Release and Sprint** on project board (from the values gathered in Phase 1 or from existing issue metadata)
4. **Set Status = "In Review"** on project board
5. **Close** completed task issues (not epics)

```bash
# Assign
gh issue edit <number> -R <repo> --add-assignee <assignee>

# Comment
gh issue comment <number> -R <repo> --body "## Implementation Complete ..."

# Add to project + set fields
gh project item-add <project> --owner beckn --url <issue-url> --format json
gh project item-edit --project-id <id> --id <item-id> --field-id <status-field> --single-select-option-id <in-review-option>

# Close tasks (not epics)
gh issue close <number> -R <repo>
```

## Phase 7 — Summary

Report:
- PR URL
- Issues commented + assigned + status updated
- Issues closed
- Any issues left open (epics, deferred)

## Rules

- Always ask context upfront — no interruptions mid-cycle
- Never close epic issues — only tasks
- Always add implementation comments before closing
- Always set assignee, release, sprint, status on project board
- PR title: `<type>(<scope>): <description>` under 70 chars
- Use HEREDOC for PR body and issue comments
