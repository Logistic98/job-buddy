#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$MODULE_DIR"

PORT="${PORT:-8030}"
echo "[agent-memory] starting FastAPI on port ${PORT}"

uv sync --extra dev
PORT="$PORT" uv run python -m uvicorn app.api:app --host 0.0.0.0 --port "$PORT"
