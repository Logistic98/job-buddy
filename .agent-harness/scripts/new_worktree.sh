#!/usr/bin/env bash
# Create an isolated git worktree for parallel agent work.
#
# Usage:
#   new_worktree.sh <branch_name>
#
# Result:
#   ../job-buddy-<sanitized-branch> with a fresh branch checked out.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: new_worktree.sh <branch_name>" >&2
  exit 2
fi

BRANCH="$1"
SLUG="$(echo "$BRANCH" | tr '/_ ' '---' | tr -cd 'a-zA-Z0-9-')"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PARENT="$(dirname "$REPO_ROOT")"
WT_PATH="$PARENT/job-buddy-${SLUG}"

if [[ -e "$WT_PATH" ]]; then
  echo "worktree path already exists: $WT_PATH" >&2
  exit 1
fi

cd "$REPO_ROOT"

if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
  git worktree add "$WT_PATH" "$BRANCH"
else
  git worktree add -b "$BRANCH" "$WT_PATH"
fi

echo "worktree ready at: $WT_PATH"
echo "next:"
echo "  cd $WT_PATH"
echo "  ./.agent-harness/scripts/run_goal.sh .agent-harness/goals/<your_goal>.md"
