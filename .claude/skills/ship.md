---
name: ship
description: Creates a PR, comments on linked issues with PR link, closes completed issues, and updates project board. Use when ready to ship a branch.
user_invocable: true
---

## When to use

When the user says: "raise PR", "ship this", "create PR and close tickets", "ship to release".

## Workflow

### Step 1 — Gather info

1. Get current branch: `git branch --show-current`
2. Get commits on this branch vs main: `git log main..HEAD --oneline`
3. Get changed files: `git diff main..HEAD --stat`
4. Ask the user: **"Which branch should I target? (e.g., main, release/1.0.0)"**
5. Wait for answer before proceeding.

### Step 2 — Find linked issues

Search commit messages and branch name for issue references (`#NNN`). For each found issue, fetch the title:

```bash
gh issue view <number> -R <repo> --json number,title,state --jq '"#\(.number) \(.title) [\(.state)]"'
```

Present each issue with its number, title, and state. Ask the user which to link and which to close. Wait for confirmation before proceeding.

### Step 3 — Create PR

Use `gh pr create` with a structured body:

```bash
gh pr create --base <target-branch> --title "<short title>" --body "$(cat <<'EOF'
## Summary
<1-3 bullet points of what changed>

## Issues
- Closes #NNN
- Closes #NNN
- Related #NNN

## What was implemented
<list of changes>

## Scenarios verified
<list of test scenarios that passed — from the scenarios agent or E2E verification>

## Test results
<unit tests + integration tests + E2E status>
EOF
)"
```

### Step 4 — Comment on issues

For each linked issue, add a comment with the PR link:

```bash
gh issue comment <number> -R <repo> --body "PR raised: <PR-URL>"
```

### Step 5 — Close completed issues

For each issue marked "Closes #NNN" in the PR body:

```bash
gh issue close <number> -R <repo>
```

Do NOT close epic issues — only close task issues. Epics stay open until all tasks are done.

### Step 6 — Update project board (if possible)

Move closed issues to "Done" status on the project board:

```bash
ITEM_ID=$(gh project item-list <project-number> --owner beckn --format json | python3 -c "...")
gh project item-edit --project-id <id> --id <item-id> --field-id <status-field> --single-select-option-id <done-option>
```

### Step 7 — Summary

Report:
- PR URL
- Issues commented
- Issues closed
- Project board updated

## Rules

- Always ask for target branch before creating PR
- Always ask which issues to link if not obvious from commits
- Never close epic issues — only tasks
- PR title should be short (<70 chars)
- PR body must include: Summary, Issues, What was implemented, Scenarios verified
- Use HEREDOC for PR body (real newlines)
