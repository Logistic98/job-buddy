#!/usr/bin/env bash
# Backend-local quality gate. Use this before returning backend changes.
# It delegates to the repository harness so backend work always runs both tests and eval checks.

set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/.." && pwd)"
cd "$REPO_ROOT"

MODE="${1:---quick}"
case "$MODE" in
  --quick|quick)
    exec "$REPO_ROOT/.agent-harness/scripts/gate.sh" agent-backend --quick
    ;;
  --full|full)
    exec "$REPO_ROOT/.agent-harness/scripts/gate.sh" agent-backend
    ;;
  *)
    echo "usage: agent-backend/scripts/quality-gate.sh [--quick|--full]" >&2
    exit 2
    ;;
esac
