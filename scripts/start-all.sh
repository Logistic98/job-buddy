#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_ROOT="$ROOT_DIR/.run/logs"
RUN_DATE="${RUN_DATE:-$(date +%Y%m%d)}"
LOG_DIR="$LOG_ROOT/$RUN_DATE"
PID_DIR="$ROOT_DIR/.run/pids"
LOG_RETENTION_DAYS="${LOG_RETENTION_DAYS:-14}"
mkdir -p "$LOG_DIR" "$PID_DIR"

if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ROOT_DIR/.env"
  set +a
fi

cleanup_old_logs() {
  [[ "${START_ALL_CLEANUP_ENABLED:-1}" == "1" ]] || return 0
  [[ -d "$LOG_ROOT" ]] || return 0
  find "$LOG_ROOT" -mindepth 1 -maxdepth 1 -type d -name '20[0-9][0-9][0-9][0-9][0-9][0-9]' -mtime +"$LOG_RETENTION_DAYS" -exec rm -rf {} +
}

cleanup_old_logs

start_service() {
  local name="$1"
  local script="$2"
  local env_prefix="${3:-}"
  local log_file="$LOG_DIR/${name}.log"
  local pid_file="$PID_DIR/${name}.pid"

  if [[ -f "$pid_file" ]]; then
    local old_pid
    old_pid="$(cat "$pid_file")"
    if kill -0 "$old_pid" >/dev/null 2>&1; then
      echo "[$name] already running, pid=$old_pid"
      return
    fi
  fi

  echo "[$name] starting, log=$log_file"
  if [[ -n "$env_prefix" ]]; then
    bash -c "cd '$ROOT_DIR' && $env_prefix '$script'" >"$log_file" 2>&1 &
  else
    bash -c "cd '$ROOT_DIR' && '$script'" >"$log_file" 2>&1 &
  fi
  echo $! > "$pid_file"
  echo "[$name] pid=$(cat "$pid_file")"
}

# Core services. Ports can be overridden by editing env prefixes below or exporting variables before running.
start_service "agent-sandbox" "$ROOT_DIR/agent-sandbox/scripts/start.sh" "PORT=${SANDBOX_PORT:-8061}"
start_service "agent-tool" "$ROOT_DIR/agent-tool/scripts/start.sh" "PORT=${TOOL_PORT:-8040} BOSS_CLI_HOME=${BOSS_CLI_HOME:-$ROOT_DIR/.run/boss-cli-home}"
start_service "agent-runtime" "$ROOT_DIR/agent-runtime/scripts/start.sh" "PORT=${RUNTIME_PORT:-8010} AGENT_TOOL_URL=${AGENT_TOOL_URL:-http://127.0.0.1:${TOOL_PORT:-8040}}"
start_service "agent-intent" "$ROOT_DIR/agent-intent/scripts/start.sh" "PORT=${INTENT_PORT:-8020}"
start_service "agent-memory" "$ROOT_DIR/agent-memory/scripts/start.sh" "PORT=${MEMORY_PORT:-8030}"
start_service "agent-eval" "$ROOT_DIR/agent-eval/scripts/start.sh" "PORT=${EVAL_PORT:-8050}"
start_service "agent-backend" "$ROOT_DIR/agent-backend/scripts/start.sh" "SERVER_PORT=${BACKEND_PORT:-8080} BOSS_CLI_HOME=${BOSS_CLI_HOME:-$ROOT_DIR/.run/boss-cli-home} AGENT_SANDBOX_URL=${AGENT_SANDBOX_URL:-http://127.0.0.1:${SANDBOX_PORT:-8061}} AGENT_RUNTIME_URL=${AGENT_RUNTIME_URL:-http://127.0.0.1:${RUNTIME_PORT:-8010}}"
start_service "agent-frontend" "$ROOT_DIR/agent-frontend/scripts/start.sh" "FRONTEND_PORT=${FRONTEND_PORT:-5173} VITE_PROXY_TARGET=${VITE_PROXY_TARGET:-http://localhost:${BACKEND_PORT:-8080}}"

echo
echo "All services have been started."
echo "Logs: $LOG_DIR"
echo "PIDs: $PID_DIR"
echo "Frontend: http://localhost:${FRONTEND_PORT:-5173}"
echo "Backend health: http://localhost:${BACKEND_PORT:-8080}/api/health"
echo "Swagger docs: http://localhost:${BACKEND_PORT:-8080}/doc.html"
echo "OpenAPI docs: http://localhost:${BACKEND_PORT:-8080}/v3/api-docs"
echo "Boss login: boss-cli credentials live in ${BOSS_CLI_HOME:-$ROOT_DIR/.run/boss-cli-home}; login to Boss in your normal browser so agent-tool can import cookies"
echo "Boss tool: http://localhost:${TOOL_PORT:-8040}/v1/tools/boss_browser/execute (runtime proxy: http://localhost:${RUNTIME_PORT:-8010}/v1/runtime/tools/boss_browser/invoke)"
echo "Sandbox health: http://localhost:${SANDBOX_PORT:-8061}/health"
echo
echo "Log retention: ${LOG_RETENTION_DAYS} days (override with LOG_RETENTION_DAYS, disable startup cleanup with START_ALL_CLEANUP_ENABLED=0)"
echo "Stop all services with: ./scripts/stop-all.sh"
