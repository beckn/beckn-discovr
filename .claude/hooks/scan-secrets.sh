#!/bin/bash
# Pre-commit secret scanner — blocks commits containing secrets
FOUND=""
for file in $(git diff --cached --name-only --diff-filter=ACM 2>/dev/null); do
  [ -f "$file" ] || continue
  file --mime "$file" 2>/dev/null | grep -q binary && continue
  M=$(grep -nE '(AKIA[0-9A-Z]{16}|BEGIN.*PRIVATE KEY|password\s*[:=]\s*"[^"]+"|bearer\s+[a-zA-Z0-9._-]+|token\s*[:=]\s*"[a-zA-Z0-9._-]+"|secret\s*[:=]\s*"[^"]+")' "$file" 2>/dev/null || true)
  [ -n "$M" ] && FOUND="$FOUND $file"
done
ENV=$(git diff --cached --name-only --diff-filter=ACM 2>/dev/null | grep -E '^\\.env$|^\\.env\\.|credentials\\.json$' || true)
[ -n "$ENV" ] && FOUND="$FOUND [sensitive-files:$ENV]"
if [ -n "$FOUND" ]; then
  echo "{\"decision\":\"block\",\"reason\":\"BLOCKED: Secrets or sensitive files in staged files:$FOUND\"}"
else
  echo "{\"continue\":true}"
fi
