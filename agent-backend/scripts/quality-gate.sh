#!/usr/bin/env bash
# 后端模块交付前质量门禁。
# 委托仓库 Harness 统一执行测试与评估。

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
