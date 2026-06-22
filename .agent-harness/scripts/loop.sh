#!/usr/bin/env bash
# Generic loop executor. Designed for cron / GitHub Actions triggering.
# Reads a loop spec file with YAML-like front matter and runs the embedded task once.
#
# Usage:
#   loop.sh <loop_file>

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

if [[ $# -ne 1 ]]; then
  echo "usage: loop.sh <loop_file>" >&2
  exit 2
fi

LOOP_FILE="$1"
[[ -f "$LOOP_FILE" ]] || { echo "loop file not found: $LOOP_FILE" >&2; exit 2; }

extract_front() { awk '/^---$/{c++; next} c==1{print}' "$LOOP_FILE"; }
extract_body() { awk '/^---$/{c++; next} c==2{print}' "$LOOP_FILE"; }

front="$(extract_front)"
body="$(extract_body)"

get_field() {
  local key="$1"
  echo "$front" | grep -E "^${key}:" | head -n1 | sed -E "s/^${key}:[[:space:]]*//" || true
}

NAME="$(get_field name)"
VERIFY_CMD="$(get_field verify_cmd)"
MAX_MINUTES="$(get_field max_minutes)"
MODEL="$(get_field model)"

NAME="${NAME:-$(basename "$LOOP_FILE" .md)}"
VERIFY_CMD="${VERIFY_CMD:-./.agent-harness/scripts/gate.sh all --quick}"
MAX_MINUTES="${MAX_MINUTES:-15}"
MODEL="${MODEL:-${CLAUDE_MODEL:-}}"

RUN_ROOT="$REPO_ROOT/.agent-harness/runs"
HARNESS_RUN_RETENTION_DAYS="${HARNESS_RUN_RETENTION_DAYS:-30}"
cleanup_old_harness_runs() {
  [[ "${HARNESS_CLEANUP_ENABLED:-1}" == "1" ]] || return 0
  [[ -d "$RUN_ROOT" ]] || return 0
  find "$RUN_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime +"$HARNESS_RUN_RETENTION_DAYS" -exec rm -rf {} +
}
cleanup_old_harness_runs

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RUN_DIR="$RUN_ROOT/loop-${TIMESTAMP}-${NAME}"
mkdir -p "$RUN_DIR"

log() { printf "[loop:%s] %s\n" "$NAME" "$*" | tee -a "$RUN_DIR/transcript.log"; }

log "start loop name=$NAME max_minutes=$MAX_MINUTES model=${MODEL:-default}"

if ! command -v claude >/dev/null 2>&1; then
  log "claude CLI not found, aborting"
  exit 127
fi

PROMPT="你正在 job-buddy 仓库内的 loop 任务中运行。
本次任务说明如下：

$body

约束：
1. 优先只读分析；除非 loop 明确允许，否则不要修改业务代码。
2. 任何修改都必须能通过验证命令证明，验证命令默认包含测试和评估。
3. 完成后输出一段简洁的纯中文报告，写明执行动作、发现问题、验证结果、是否需要人工跟进。"

# 巡检默认只读权限；允许写改的 loop 通过 CLAUDE_PERMISSION_MODE=acceptEdits 显式放开。
PERMISSION_MODE="${CLAUDE_PERMISSION_MODE:-default}"
CLAUDE_ARGS=(-p "$PROMPT" --permission-mode "$PERMISSION_MODE")
if [[ -n "$MODEL" ]]; then
  CLAUDE_ARGS+=(--model "$MODEL")
fi

set +e
python3 - "$MAX_MINUTES" "$RUN_DIR/transcript.log" "${CLAUDE_ARGS[@]}" <<'PY'
import subprocess
import sys

max_minutes = int(sys.argv[1])
log_path = sys.argv[2]
args = sys.argv[3:]
with open(log_path, "ab") as log:
    try:
        completed = subprocess.run(["claude", *args], stdout=log, stderr=subprocess.STDOUT, timeout=max_minutes * 60, check=False)
        sys.exit(completed.returncode)
    except subprocess.TimeoutExpired:
        log.write(f"\n[loop] claude timed out after {max_minutes} minutes\n".encode())
        sys.exit(124)
PY
rc=$?
set -e
if [[ "$rc" -eq 124 ]]; then
  log "claude timed out"
elif [[ "$rc" -ne 0 ]]; then
  log "claude exited non-zero: $rc"
fi

if [[ -n "$VERIFY_CMD" ]]; then
  log "running verify: $VERIFY_CMD"
  if bash -c "$VERIFY_CMD" >"$RUN_DIR/verify.log" 2>&1; then
    log "verify passed"
  else
    log "verify failed; see $RUN_DIR/verify.log"
  fi
fi

log "done; run_dir=$RUN_DIR"
