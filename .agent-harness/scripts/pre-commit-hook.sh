#!/usr/bin/env bash
# Optional local pre-commit hook: runs verify.sh --quick only for modules that
# have staged changes, so commits stay fast while still catching regressions
# before they reach CI. Not installed by default; see .agent-harness/README.md
# for the opt-in installation command.
#
# Usage (after installing as .git/hooks/pre-commit):
#   git commit ...   # hook runs automatically
# Manual dry run:
#   .agent-harness/scripts/pre-commit-hook.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

STAGED_FILES="$(git diff --cached --name-only --diff-filter=ACMR)"
if [[ -z "$STAGED_FILES" ]]; then
  exit 0
fi

declare -a TARGETS=()
while IFS= read -r module; do
  if grep -q "^${module}/" <<<"$STAGED_FILES"; then
    TARGETS+=("$module")
  fi
done < <(./.agent-harness/scripts/verify.sh --list)

if [[ ${#TARGETS[@]} -eq 0 ]]; then
  echo "[pre-commit] no known module changed, skipping verify"
  exit 0
fi

for target in "${TARGETS[@]}"; do
  echo "[pre-commit] verify.sh ${target} --quick"
  if ! ./.agent-harness/scripts/verify.sh "$target" --quick; then
    echo "[pre-commit] ${target} verify failed, blocking commit (use --no-verify to bypass)"
    exit 1
  fi
done
