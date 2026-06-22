#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="$ROOT_DIR/.run/pids"
LOG_ROOT="$ROOT_DIR/.run/logs"

health_url() {
  case "$1" in
    agent-sandbox) echo "http://localhost:${SANDBOX_PORT:-8061}/health" ;;
    agent-runtime) echo "http://localhost:${RUNTIME_PORT:-8010}/health" ;;
    agent-intent) echo "http://localhost:${INTENT_PORT:-8020}/health" ;;
    agent-memory) echo "http://localhost:${MEMORY_PORT:-8030}/health" ;;
    agent-tool) echo "http://localhost:${TOOL_PORT:-8040}/health" ;;
    agent-eval) echo "http://localhost:${EVAL_PORT:-8050}/health" ;;
    agent-backend) echo "http://localhost:${BACKEND_PORT:-8080}/api/health" ;;
    agent-frontend) echo "http://localhost:${FRONTEND_PORT:-5173}/" ;;
    *) echo "" ;;
  esac
}

probe_url() {
  local url="$1"
  if [[ -z "$url" ]] || ! command -v curl >/dev/null 2>&1; then
    echo ""
    return
  fi
  local code
  code="$(curl -L -s -o /dev/null -w '%{http_code}' --max-time 2 "$url" || echo "000")"
  echo " http=$code url=$url"
}

service_log() {
  local name="$1"
  local today="${RUN_DATE:-$(date +%Y%m%d)}"
  local current="$LOG_ROOT/$today/${name}.log"
  if [[ -f "$current" ]]; then
    echo "$current"
    return
  fi
  find "$LOG_ROOT" -path "*/${name}.log" -type f 2>/dev/null | sort | tail -n 1
}

if [[ ! -d "$PID_DIR" ]]; then
  echo "No services recorded."
  exit 0
fi

for pid_file in "$PID_DIR"/*.pid; do
  [[ -e "$pid_file" ]] || continue
  name="$(basename "$pid_file" .pid)"
  pid="$(cat "$pid_file")"
  url="$(health_url "$name")"
  health="$(probe_url "$url")"
  if kill -0 "$pid" >/dev/null 2>&1; then
    echo "[$name] running pid=$pid log=$(service_log "$name")${health}"
  else
    echo "[$name] stopped pid=$pid log=$(service_log "$name")${health}"
  fi
done
