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
PORT="${PORT:-8061}"
if [[ -z "${AGENT_SANDBOX_SRT_BIN:-}" ]] && command -v npm >/dev/null 2>&1; then
  NPM_ROOT="$(npm root -g 2>/dev/null || true)"
  NPM_BIN="$(dirname "$(dirname "$NPM_ROOT")")/bin"
  if [[ -x "$NPM_BIN/srt" ]]; then
    export AGENT_SANDBOX_SRT_BIN="$NPM_BIN/srt"
    export PATH="$NPM_BIN:$PATH"
  fi
fi

echo "[agent-sandbox] starting FastAPI on port ${PORT}"
echo "[agent-sandbox] srt cli: ${AGENT_SANDBOX_SRT_BIN:-$(command -v srt || echo missing)}"

uv sync --frozen --extra dev
HOST="$HOST" PORT="$PORT" uv run python -m uvicorn app.server.app:create_app --factory --host "$HOST" --port "$PORT"
