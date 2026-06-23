#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$MODULE_DIR"

PORT="${FRONTEND_PORT:-5173}"
BACKEND_URL="${VITE_PROXY_TARGET:-http://localhost:8080}"

echo "[agent-frontend] starting Vue dev server on port ${PORT}, backend=${BACKEND_URL}"

if [[ ! -d node_modules ]]; then
  npm install
fi

VITE_PROXY_TARGET="$BACKEND_URL" npm run dev -- --port "$PORT"
