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
PORT="${FRONTEND_PORT:-5173}"
BACKEND_URL="${VITE_PROXY_TARGET:-http://127.0.0.1:${BACKEND_PORT:-8080}}"

echo "[agent-frontend] starting Vue dev server on port ${PORT}, backend=${BACKEND_URL}"

if [[ ! -d node_modules ]]; then
  npm install
fi

VITE_PROXY_TARGET="$BACKEND_URL" npm run dev -- --host "$HOST" --port "$PORT" --strictPort
