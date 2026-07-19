#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="$(cd "$MODULE_DIR/.." && pwd)"
if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ROOT_DIR/.env"
  set +a
fi
cd "$MODULE_DIR"

HOST="${HOST:-${JOB_BUDDY_BIND_HOST:-127.0.0.1}}"
PORT="${PORT:-8020}"
echo "[agent-intent] starting FastAPI on ${HOST}:${PORT}"

uv sync --frozen --extra dev
HOST="$HOST" PORT="$PORT" uv run python -m uvicorn app.api:app --host "$HOST" --port "$PORT"
