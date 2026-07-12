#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$MODULE_DIR"

ROOT_DIR="$(cd "$MODULE_DIR/.." && pwd)"
if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ROOT_DIR/.env"
  set +a
fi

PORT="${SERVER_PORT:-8080}"
AGENT_RUNTIME_URL="${AGENT_RUNTIME_URL:-http://127.0.0.1:${RUNTIME_PORT:-8010}}"
AGENT_SANDBOX_URL="${AGENT_SANDBOX_URL:-http://127.0.0.1:${SANDBOX_PORT:-8061}}"
echo "[agent-backend] starting Spring Boot on port ${PORT}"
echo "[agent-backend] Boss credentials: PostgreSQL auth_state"
echo "[agent-backend] boss tool via runtime: ${AGENT_RUNTIME_URL}/v1/runtime/tools/boss_browser/invoke"
echo "[agent-backend] sandbox service: ${AGENT_SANDBOX_URL}"

export SERVER_PORT="$PORT"
export AGENT_RUNTIME_URL
export AGENT_SANDBOX_URL

if [[ -x ./mvnw ]]; then
  ./mvnw spring-boot:run
else
  mvn spring-boot:run
fi
