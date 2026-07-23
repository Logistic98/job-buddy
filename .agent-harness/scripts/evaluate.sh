#!/usr/bin/env bash
# Run the repository's deterministic Agent evaluation suite.
#
# Module tests belong to verify.sh. This script intentionally does not keep a
# second, hand-maintained list of selected tests for every module.
#
# Usage:
#   evaluate.sh
#   evaluate.sh all
#   evaluate.sh <module>

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

TARGET="${1:-all}"

log() { printf "[eval] %s\n" "$*"; }
fail() { printf "[eval] FAIL: %s\n" "$*" >&2; exit 1; }
need_cmd() { command -v "$1" >/dev/null 2>&1 || fail "$2 requires '$1' in PATH"; }

is_known_target() {
  local candidate="$1"
  [[ "$candidate" == "all" ]] && return 0
  ./.agent-harness/scripts/verify.sh --list | grep -Fxq "$candidate"
}

run_agent_eval() {
  [[ -d agent-eval ]] || fail "agent-eval directory is missing"
  need_cmd uv "agent-eval"

  log "agent-eval: grader tests and engine self-check"
  pushd agent-eval >/dev/null
  uv sync --frozen --extra dev --quiet || fail "agent-eval: dependency sync failed"
  env -u JOB_BUDDY_RUNTIME_USE_LLM_PLANNER \
    AGENT_INTERNAL_SERVICE_TOKEN= \
    JOB_BUDDY_ENVIRONMENT=development \
    uv run python -m pytest -q || fail "agent-eval: pytest failed"
  uv run python scripts/run_engine_eval.py --self-check || fail "agent-eval: engine self-check failed"
  popd >/dev/null
}

is_known_target "$TARGET" || fail "unknown eval target: $TARGET"

if [[ "$TARGET" == "agent-frontend" ]]; then
  log "agent-frontend behavior checks are part of its Vitest suite run by verify.sh"
else
  run_agent_eval
fi

log "all evals passed"
