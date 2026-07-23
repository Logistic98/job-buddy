#!/usr/bin/env bash

if [[ "${JOB_BUDDY_SERVICE_PROCESS_LIB_LOADED:-0}" == "1" ]]; then
  return 0
fi
JOB_BUDDY_SERVICE_PROCESS_LIB_LOADED=1

process_command() {
  ps -p "$1" -o command= 2>/dev/null || true
}

process_parent() {
  ps -p "$1" -o ppid= 2>/dev/null | tr -d '[:space:]'
}

process_is_running() {
  local process_status
  process_status="$(ps -p "$1" -o stat= 2>/dev/null | tr -d '[:space:]')"
  [[ -n "$process_status" ]] && [[ "$process_status" != Z* ]]
}

process_cwd() {
  local pid="$1"
  if [[ -e "/proc/$pid/cwd" ]]; then
    readlink "/proc/$pid/cwd" 2>/dev/null || true
    return
  fi
  if command -v lsof >/dev/null 2>&1; then
    lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1
  fi
}

process_belongs_to_module() {
  local pid="$1"
  local module_dir="$2"
  local command
  local cwd
  command="$(process_command "$pid")"
  cwd="$(process_cwd "$pid")"

  [[ "$command" == *"$module_dir"* ]] || [[ "$cwd" == "$module_dir" ]] || [[ "$cwd" == "$module_dir/"* ]]
}

process_tree_belongs_to_module() {
  local pid="$1"
  local module_dir="$2"
  local child
  local children

  if process_belongs_to_module "$pid" "$module_dir"; then
    return 0
  fi
  children="$(pgrep -P "$pid" 2>/dev/null || true)"
  for child in $children; do
    if process_tree_belongs_to_module "$child" "$module_dir"; then
      return 0
    fi
  done
  return 1
}

process_is_descendant_of() {
  local pid="$1"
  local ancestor_pid="$2"
  local current_pid="$pid"

  while [[ -n "$current_pid" ]] && [[ "$current_pid" -gt 1 ]]; do
    if [[ "$current_pid" == "$ancestor_pid" ]]; then
      return 0
    fi
    current_pid="$(process_parent "$current_pid")"
  done
  return 1
}

service_process_root() {
  local pid="$1"
  local module_dir="$2"
  local root_pid="$pid"
  local parent_pid

  while true; do
    parent_pid="$(process_parent "$root_pid")"
    if [[ -z "$parent_pid" ]] || [[ "$parent_pid" -le 1 ]] || ! process_belongs_to_module "$parent_pid" "$module_dir"; then
      break
    fi
    root_pid="$parent_pid"
  done

  echo "$root_pid"
}

kill_tree() {
  local pid="$1"
  local signal="${2:-TERM}"
  local children
  local child
  children="$(pgrep -P "$pid" 2>/dev/null || true)"
  for child in $children; do
    kill_tree "$child" "$signal"
  done
  if process_is_running "$pid"; then
    kill "-$signal" "$pid" >/dev/null 2>&1 || true
  fi
}

stop_process_tree() {
  local pid="$1"
  local timeout="${STOP_ALL_TIMEOUT_SECONDS:-10}"
  local attempts=$((timeout * 5))
  local attempt

  kill_tree "$pid" TERM
  for ((attempt = 0; attempt < attempts; attempt++)); do
    if ! process_is_running "$pid"; then
      return 0
    fi
    sleep 0.2
  done

  echo "process tree pid=$pid did not stop within ${timeout}s; sending KILL" >&2
  kill_tree "$pid" KILL
  sleep 0.2
  if process_is_running "$pid"; then
    echo "process tree pid=$pid is still running" >&2
    return 1
  fi
}

listener_pids_for_port() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | sort -u
    return 0
  fi
  if command -v ss >/dev/null 2>&1; then
    ss -ltnp "sport = :$port" 2>/dev/null \
      | sed -nE 's/.*pid=([0-9]+).*/\1/p' \
      | sort -u
    return 0
  fi
  return 1
}

service_port() {
  case "$1" in
    agent-sandbox) echo "${SANDBOX_PORT:-8061}" ;;
    agent-backend) echo "${BACKEND_PORT:-8080}" ;;
    agent-memory) echo "${MEMORY_PORT:-8030}" ;;
    agent-tool) echo "${TOOL_PORT:-8040}" ;;
    agent-runtime) echo "${RUNTIME_PORT:-8010}" ;;
    agent-intent) echo "${INTENT_PORT:-8020}" ;;
    agent-eval) echo "${EVAL_PORT:-8050}" ;;
    agent-frontend) echo "${FRONTEND_PORT:-5173}" ;;
    *) return 1 ;;
  esac
}

acquire_lifecycle_lock() {
  local action="$1"
  local lock_dir="$ROOT_DIR/.run/service-lifecycle.lock"
  local owner_pid
  mkdir -p "$ROOT_DIR/.run"

  if mkdir "$lock_dir" 2>/dev/null; then
    echo "$$" > "$lock_dir/pid"
    echo "$action" > "$lock_dir/action"
    return 0
  fi

  owner_pid="$(cat "$lock_dir/pid" 2>/dev/null || true)"
  if [[ "$owner_pid" =~ ^[0-9]+$ ]] && process_is_running "$owner_pid"; then
    echo "another service lifecycle operation is running: action=$(cat "$lock_dir/action" 2>/dev/null || echo unknown) pid=$owner_pid" >&2
    return 1
  fi

  rm -f "$lock_dir/pid" "$lock_dir/action"
  if ! rmdir "$lock_dir" 2>/dev/null || ! mkdir "$lock_dir" 2>/dev/null; then
    echo "cannot recover stale service lifecycle lock: $lock_dir" >&2
    return 1
  fi
  echo "$$" > "$lock_dir/pid"
  echo "$action" > "$lock_dir/action"
}

release_lifecycle_lock() {
  local lock_dir="$ROOT_DIR/.run/service-lifecycle.lock"
  local owner_pid
  owner_pid="$(cat "$lock_dir/pid" 2>/dev/null || true)"
  [[ "$owner_pid" == "$$" ]] || return 0
  rm -f "$lock_dir/pid" "$lock_dir/action"
  rmdir "$lock_dir" 2>/dev/null || true
}

stop_recorded_service() {
  local name="$1"
  local pid_file="$PID_DIR/${name}.pid"
  local module_dir="$ROOT_DIR/$name"
  local pid

  [[ -f "$pid_file" ]] || return 0
  pid="$(cat "$pid_file")"
  if ! [[ "$pid" =~ ^[0-9]+$ ]]; then
    echo "[$name] invalid recorded pid=$pid; removing stale PID file" >&2
  elif process_is_running "$pid"; then
    if process_tree_belongs_to_module "$pid" "$module_dir"; then
      echo "[$name] stopping recorded pid=$pid"
      if ! stop_process_tree "$pid"; then
        rm -f "$pid_file"
        return 1
      fi
    else
      echo "[$name] recorded pid=$pid no longer belongs to this repository; refusing to stop it" >&2
    fi
  else
    echo "[$name] not running"
  fi
  rm -f "$pid_file"
}

stop_repository_listeners() {
  local name="$1"
  local port="$2"
  local module_dir="$ROOT_DIR/$name"
  local max_passes="${STOP_ALL_LISTENER_MAX_PASSES:-5}"
  local pass
  local listener_pids
  local active_listener_pids
  local listener_pid
  local root_pid
  local external_listener=0

  for ((pass = 1; pass <= max_passes; pass++)); do
    if ! listener_pids="$(listener_pids_for_port "$port")"; then
      echo "[$name] cannot inspect port $port because neither lsof nor ss is available" >&2
      return 1
    fi
    active_listener_pids=""
    for listener_pid in $listener_pids; do
      if process_is_running "$listener_pid"; then
        active_listener_pids="${active_listener_pids}${active_listener_pids:+ }${listener_pid}"
      fi
    done
    [[ -n "$active_listener_pids" ]] || return 0

    external_listener=0
    for listener_pid in $active_listener_pids; do
      if ! process_belongs_to_module "$listener_pid" "$module_dir"; then
        echo "[$name] port $port is held by external pid=$listener_pid; refusing to stop it" >&2
        external_listener=1
        continue
      fi

      root_pid="$(service_process_root "$listener_pid" "$module_dir")"
      echo "[$name] stopping unrecorded repository process pid=$root_pid (listener pid=$listener_pid, port=$port)"
      stop_process_tree "$root_pid" || return 1
    done

    if [[ "$external_listener" == "1" ]]; then
      return 1
    fi
    sleep 0.2
  done

  listener_pids="$(listener_pids_for_port "$port" || true)"
  active_listener_pids=""
  for listener_pid in $listener_pids; do
    if process_is_running "$listener_pid"; then
      active_listener_pids="${active_listener_pids}${active_listener_pids:+ }${listener_pid}"
    fi
  done
  [[ -z "$active_listener_pids" ]] && return 0
  echo "[$name] port $port is still occupied after $max_passes cleanup pass(es): $active_listener_pids" >&2
  return 1
}

listener_belongs_to_process_tree() {
  local root_pid="$1"
  local port="$2"
  local listener_pids
  local listener_pid

  listener_pids="$(listener_pids_for_port "$port" || true)"
  for listener_pid in $listener_pids; do
    if process_is_descendant_of "$listener_pid" "$root_pid"; then
      return 0
    fi
  done
  return 1
}
