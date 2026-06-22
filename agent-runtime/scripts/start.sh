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

cd "$MODULE_DIR"

PORT="${PORT:-8010}"
echo "[agent-runtime] starting FastAPI on port ${PORT}"

uv sync --extra dev
uv run python -m uvicorn server:app --host 0.0.0.0 --port "$PORT"
