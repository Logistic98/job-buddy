#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_ROOT="$ROOT_DIR/.run/logs"
RUN_DATE="${RUN_DATE:-$(date +%Y%m%d)}"
LOG_DIR="$LOG_ROOT/$RUN_DATE"
PID_DIR="$ROOT_DIR/.run/pids"
LOG_RETENTION_DAYS="${LOG_RETENTION_DAYS:-14}"
STARTED_SERVICES=()
mkdir -p "$LOG_DIR" "$PID_DIR"

if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ROOT_DIR/.env"
  set +a
fi

# shellcheck source=scripts/lib/service-process.sh
source "$ROOT_DIR/scripts/lib/service-process.sh"

cleanup_old_logs() {
  [[ "${START_ALL_CLEANUP_ENABLED:-1}" == "1" ]] || return 0
  [[ -d "$LOG_ROOT" ]] || return 0
  find "$LOG_ROOT" -mindepth 1 -maxdepth 1 -type d -name '20[0-9][0-9][0-9][0-9][0-9][0-9]' -mtime +"$LOG_RETENTION_DAYS" -exec rm -rf {} +
}

cleanup_old_logs

start_service() {
  local name="$1"
  local script="$2"
  local health_url="${3:-}"
  local port="$4"
  shift 4
  local env_args=("$@")
  local log_file="$LOG_DIR/${name}.log"
  local pid_file="$PID_DIR/${name}.pid"

  if [[ -f "$pid_file" ]]; then
    local old_pid
    old_pid="$(cat "$pid_file")"
    if [[ -n "$old_pid" ]] && process_is_running "$old_pid" && process_tree_belongs_to_module "$old_pid" "$ROOT_DIR/$name"; then
      echo "[$name] already running or starting, pid=$old_pid"
      return 0
    fi
    rm -f "$pid_file"
  fi

  # Reconcile immediately before launch so a repository process started after
  # stop-all cannot win the port race. External listeners remain protected.
  if ! stop_repository_listeners "$name" "$port"; then
    return 1
  fi

  echo "[$name] starting, log=$log_file"
  (
    cd "$ROOT_DIR"
    if [[ "${#env_args[@]}" -gt 0 ]]; then
      env "${env_args[@]}" "$script"
    else
      "$script"
    fi
  ) >"$log_file" 2>&1 &
  echo $! > "$pid_file"
  STARTED_SERVICES+=("$name")
  echo "[$name] pid=$(cat "$pid_file")"
}

wait_for_http() {
  local name="$1"
  local url="$2"
  local port="$3"
  local pid_file="$PID_DIR/${name}.pid"
  local timeout="${START_ALL_READY_TIMEOUT_SECONDS:-300}"
  local deadline=$((SECONDS + timeout))

  while (( SECONDS < deadline )); do
    if [[ ! -f "$pid_file" ]] || ! kill -0 "$(cat "$pid_file")" >/dev/null 2>&1; then
      echo "[$name] exited before readiness; last log lines:" >&2
      tail -n 40 "$LOG_DIR/${name}.log" >&2 || true
      return 1
    fi
    if curl --fail --silent --show-error --max-time 2 "$url" >/dev/null 2>&1; then
      if ! listener_belongs_to_process_tree "$(cat "$pid_file")" "$port"; then
        echo "[$name] health endpoint is served by a listener outside managed pid=$(cat "$pid_file"); refusing stale readiness" >&2
        tail -n 40 "$LOG_DIR/${name}.log" >&2 || true
        return 1
      fi
      echo "[$name] ready: $url"
      return 0
    fi
    sleep 1
  done

  echo "[$name] readiness timed out after ${timeout}s: $url" >&2
  tail -n 40 "$LOG_DIR/${name}.log" >&2 || true
  return 1
}

rollback_started_services() {
  local index
  local name

  [[ "${#STARTED_SERVICES[@]}" -gt 0 ]] || return 0
  echo "Startup failed; rolling back services started by this invocation." >&2
  for ((index = ${#STARTED_SERVICES[@]} - 1; index >= 0; index--)); do
    name="${STARTED_SERVICES[$index]}"
    stop_recorded_service "$name" || true
    stop_repository_listeners "$name" "$(service_port "$name")" || true
  done
}

start_all_services() {
  local sandbox_health="http://127.0.0.1:${SANDBOX_PORT:-8061}/health"
  local tool_health="http://127.0.0.1:${TOOL_PORT:-8040}/health"
  local runtime_health="http://127.0.0.1:${RUNTIME_PORT:-8010}/health"
  local intent_health="http://127.0.0.1:${INTENT_PORT:-8020}/health"
  local memory_health="http://127.0.0.1:${MEMORY_PORT:-8030}/health"
  local eval_health="http://127.0.0.1:${EVAL_PORT:-8050}/health"
  local backend_health="http://127.0.0.1:${BACKEND_PORT:-8080}/actuator/health"
  local frontend_health="http://127.0.0.1:${FRONTEND_PORT:-5173}/"

  # Backend owns the shared database schema. It must complete Flyway migrations
  # before agent-memory creates its agent_memory_* tables in the same schema.
  start_service "agent-sandbox" "$ROOT_DIR/agent-sandbox/scripts/start.sh" "$sandbox_health" "${SANDBOX_PORT:-8061}" "PORT=${SANDBOX_PORT:-8061}" || return 1
  wait_for_http "agent-sandbox" "$sandbox_health" "${SANDBOX_PORT:-8061}" || return 1
  start_service "agent-backend" "$ROOT_DIR/agent-backend/scripts/start.sh" "$backend_health" "${BACKEND_PORT:-8080}" \
    "SERVER_PORT=${BACKEND_PORT:-8080}" \
    "AGENT_SANDBOX_URL=${AGENT_SANDBOX_URL:-http://127.0.0.1:${SANDBOX_PORT:-8061}}" \
    "AGENT_RUNTIME_URL=${AGENT_RUNTIME_URL:-http://127.0.0.1:${RUNTIME_PORT:-8010}}" || return 1
  wait_for_http "agent-backend" "$backend_health" "${BACKEND_PORT:-8080}" || return 1
  start_service "agent-memory" "$ROOT_DIR/agent-memory/scripts/start.sh" "$memory_health" "${MEMORY_PORT:-8030}" "PORT=${MEMORY_PORT:-8030}" || return 1
  wait_for_http "agent-memory" "$memory_health" "${MEMORY_PORT:-8030}" || return 1
  start_service "agent-tool" "$ROOT_DIR/agent-tool/scripts/start.sh" "$tool_health" "${TOOL_PORT:-8040}" "PORT=${TOOL_PORT:-8040}" || return 1
  wait_for_http "agent-tool" "$tool_health" "${TOOL_PORT:-8040}" || return 1
  start_service "agent-runtime" "$ROOT_DIR/agent-runtime/scripts/start.sh" "$runtime_health" "${RUNTIME_PORT:-8010}" \
    "PORT=${RUNTIME_PORT:-8010}" \
    "AGENT_TOOL_URL=${AGENT_TOOL_URL:-http://127.0.0.1:${TOOL_PORT:-8040}}" || return 1
  wait_for_http "agent-runtime" "$runtime_health" "${RUNTIME_PORT:-8010}" || return 1
  start_service "agent-intent" "$ROOT_DIR/agent-intent/scripts/start.sh" "$intent_health" "${INTENT_PORT:-8020}" "PORT=${INTENT_PORT:-8020}" || return 1
  wait_for_http "agent-intent" "$intent_health" "${INTENT_PORT:-8020}" || return 1
  start_service "agent-eval" "$ROOT_DIR/agent-eval/scripts/start.sh" "$eval_health" "${EVAL_PORT:-8050}" "PORT=${EVAL_PORT:-8050}" || return 1
  wait_for_http "agent-eval" "$eval_health" "${EVAL_PORT:-8050}" || return 1
  start_service "agent-frontend" "$ROOT_DIR/agent-frontend/scripts/start.sh" "$frontend_health" "${FRONTEND_PORT:-5173}" \
    "FRONTEND_PORT=${FRONTEND_PORT:-5173}" \
    "VITE_PROXY_TARGET=${VITE_PROXY_TARGET:-http://localhost:${BACKEND_PORT:-8080}}" || return 1
  wait_for_http "agent-frontend" "$frontend_health" "${FRONTEND_PORT:-5173}" || return 1
}

print_started_summary() {
  local public_host="${JOB_BUDDY_SERVER_HOST:-127.0.0.1}"
  echo
  echo "All services have been started."
  echo "Logs: $LOG_DIR"
  echo "PIDs: $PID_DIR"
  echo "Frontend: http://${public_host}:${FRONTEND_PORT:-5173}"
  echo "Backend health: http://${public_host}:${BACKEND_PORT:-8080}/api/health"
  echo "Swagger docs: http://${public_host}:${BACKEND_PORT:-8080}/doc.html"
  echo "OpenAPI docs: http://${public_host}:${BACKEND_PORT:-8080}/v3/api-docs"
  echo "Boss login: credentials persist in PostgreSQL auth_state and are injected into agent-tool memory"
  echo "Boss tool: http://${public_host}:${TOOL_PORT:-8040}/v1/tools/boss_browser/execute (runtime proxy: http://${public_host}:${RUNTIME_PORT:-8010}/v1/runtime/tools/boss_browser/invoke)"
  echo "Sandbox health: http://${public_host}:${SANDBOX_PORT:-8061}/health"
  echo
  echo "Log retention: ${LOG_RETENTION_DAYS} days (override with LOG_RETENTION_DAYS, disable startup cleanup with START_ALL_CLEANUP_ENABLED=0)"
  echo "Stop all services with: ./scripts/stop-all.sh"
}

main() {
  STARTED_SERVICES=()
  if ! start_all_services; then
    rollback_started_services
    return 1
  fi
  print_started_summary
}

run_with_lifecycle_lock() {
  local result=0
  acquire_lifecycle_lock "start-all" || return 1
  main "$@" || result=$?
  release_lifecycle_lock
  return "$result"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  run_with_lifecycle_lock "$@"
fi
