#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="$(cd "$MODULE_DIR/.." && pwd)"

if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.env"
  set +a
fi
if [[ -f "$MODULE_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$MODULE_DIR/.env"
  set +a
fi

# Runtime 与 backend 必须使用同一个仓库级 workspace。环境变量中的相对路径
# 一律相对仓库根目录解析，避免因当前工作目录不同产生路径越界误判。
RUNTIME_WORKSPACE="${JOB_BUDDY_RUNTIME_WORKSPACE_DIR:-.run/runtime-workspace}"
if [[ "$RUNTIME_WORKSPACE" != /* ]]; then
  RUNTIME_WORKSPACE="$ROOT_DIR/$RUNTIME_WORKSPACE"
fi
mkdir -p "$RUNTIME_WORKSPACE"
export JOB_BUDDY_RUNTIME_WORKSPACE_DIR="$(cd "$RUNTIME_WORKSPACE" && pwd)"

cd "$MODULE_DIR"

HOST="${HOST:-${JOB_BUDDY_BIND_HOST:-127.0.0.1}}"
PORT="${PORT:-8010}"
echo "[agent-runtime] starting FastAPI on ${HOST}:${PORT}"

uv sync --frozen --extra dev
uv run python -m uvicorn server:app --host "$HOST" --port "$PORT"
