#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$MODULE_DIR"

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

uv sync --extra dev
PORT="$PORT" uv run python -m uvicorn app.server.app:create_app --factory --host 0.0.0.0 --port "$PORT"
