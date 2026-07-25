#!/usr/bin/env bash
# Configure this checkout to use the repository-owned Git hooks.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "[git-hooks] not inside a Git worktree: $REPO_ROOT" >&2
  exit 1
fi

git config --local core.hooksPath .agent-harness/hooks

configured_path="$(git config --local --get core.hooksPath)"
if [[ "$configured_path" != ".agent-harness/hooks" ]]; then
  echo "[git-hooks] failed to configure core.hooksPath" >&2
  exit 1
fi

echo "[git-hooks] installed: core.hooksPath=$configured_path"
