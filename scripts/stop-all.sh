#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="$ROOT_DIR/.run/pids"
STOP_FAILURES=0

if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ROOT_DIR/.env"
  set +a
fi

# shellcheck source=scripts/lib/service-process.sh
source "$ROOT_DIR/scripts/lib/service-process.sh"

main() {
  local name
  local pid_file
  local services=(
    agent-sandbox
    agent-backend
    agent-memory
    agent-tool
    agent-runtime
    agent-intent
    agent-eval
    agent-frontend
  )

  if [[ -d "$PID_DIR" ]]; then
    for pid_file in "$PID_DIR"/*.pid; do
      [[ -e "$pid_file" ]] || continue
      name="$(basename "$pid_file" .pid)"
      if ! stop_recorded_service "$name"; then
        STOP_FAILURES=$((STOP_FAILURES + 1))
      fi
    done
  fi

  for name in "${services[@]}"; do
    if ! stop_repository_listeners "$name" "$(service_port "$name")"; then
      STOP_FAILURES=$((STOP_FAILURES + 1))
    fi
  done

  if [[ "$STOP_FAILURES" -gt 0 ]]; then
    echo "Stopped repository services with $STOP_FAILURES unresolved listener or process error(s)." >&2
    return 1
  fi
  echo "All repository services stopped."
}

run_with_lifecycle_lock() {
  local result=0
  acquire_lifecycle_lock "stop-all" || return 1
  main "$@" || result=$?
  release_lifecycle_lock
  return "$result"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  run_with_lifecycle_lock "$@"
fi
