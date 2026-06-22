#!/usr/bin/env bash
# Show recent harness runs and their summary status.
#
# Usage:
#   status.sh [limit]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNS_DIR="$REPO_ROOT/.agent-harness/runs"
LIMIT="${1:-10}"

if [[ ! -d "$RUNS_DIR" ]]; then
  echo "no runs directory yet: $RUNS_DIR"
  exit 0
fi

count=0
find "$RUNS_DIR" -mindepth 1 -maxdepth 1 -type d | sort -r | while read -r run_dir; do
  count=$((count + 1))
  if [[ "$count" -gt "$LIMIT" ]]; then
    break
  fi

  name="$(basename "$run_dir")"
  summary="$run_dir/summary.md"
  verdict="$run_dir/verdict.md"
  echo "== $name =="
  if [[ -f "$summary" ]]; then
    grep -E '^- (slug|status|turns|duration_sec|verify_cmd):' "$summary" || true
  else
    echo "- summary: missing"
  fi
  if [[ -f "$verdict" ]]; then
    echo "- verdict: $verdict"
  fi
  echo "- path: $run_dir"
  echo
done
