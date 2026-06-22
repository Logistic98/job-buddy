#!/usr/bin/env bash
# Run a single /goal-style task with budget enforcement and independent judging.
#
# Usage:
#   run_goal.sh <goal_file> [--max-turns N] [--max-minutes M] [--no-judge] [--model MODEL]
#
# Reads optional metadata from goal front matter:
#   max_turns: <int>
#   max_minutes: <int>
#   max_diff_lines: <int, 0 = unlimited>
#   verify_cmd: <shell command>
#   model: <claude model name>
#
# Environment:
#   CLAUDE_MODEL            default model override
#   CLAUDE_PERMISSION_MODE  headless permission mode, default acceptEdits

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

if [[ $# -lt 1 ]]; then
  echo "usage: run_goal.sh <goal_file> [--max-turns N] [--max-minutes M] [--no-judge] [--model MODEL]" >&2
  exit 2
fi

GOAL_FILE="$1"
shift

if [[ ! -f "$GOAL_FILE" ]]; then
  echo "goal file not found: $GOAL_FILE" >&2
  exit 2
fi

MAX_TURNS=20
MAX_MINUTES=60
MAX_DIFF_LINES=0
RUN_JUDGE=1
MODEL="${CLAUDE_MODEL:-}"
PERMISSION_MODE="${CLAUDE_PERMISSION_MODE:-acceptEdits}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --max-turns) MAX_TURNS="$2"; shift 2 ;;
    --max-minutes) MAX_MINUTES="$2"; shift 2 ;;
    --no-judge) RUN_JUDGE=0; shift ;;
    --model) MODEL="$2"; shift 2 ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
done

extract_meta() {
  local key="$1"
  awk -v key="$key" '
    BEGIN { in_front=0; seen=0 }
    /^---$/ { seen++; in_front=(seen==1); if (seen==2) exit; next }
    in_front && $0 ~ "^" key ":" { sub("^" key ":[[:space:]]*", ""); print; exit }
  ' "$GOAL_FILE"
}

META_TURNS="$(extract_meta max_turns)"
META_MINUTES="$(extract_meta max_minutes)"
META_DIFF="$(extract_meta max_diff_lines)"
META_VERIFY="$(extract_meta verify_cmd)"
META_MODEL="$(extract_meta model)"

[[ -n "$META_TURNS" ]] && MAX_TURNS="$META_TURNS"
[[ -n "$META_MINUTES" ]] && MAX_MINUTES="$META_MINUTES"
[[ -n "$META_DIFF" ]] && MAX_DIFF_LINES="$META_DIFF"
[[ -n "$META_MODEL" ]] && MODEL="$META_MODEL"

VERIFY_CMD="${META_VERIFY:-./.agent-harness/scripts/gate.sh all --quick}"

RUN_ROOT="$REPO_ROOT/.agent-harness/runs"
HARNESS_RUN_RETENTION_DAYS="${HARNESS_RUN_RETENTION_DAYS:-30}"
cleanup_old_harness_runs() {
  [[ "${HARNESS_CLEANUP_ENABLED:-1}" == "1" ]] || return 0
  [[ -d "$RUN_ROOT" ]] || return 0
  find "$RUN_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime +"$HARNESS_RUN_RETENTION_DAYS" -exec rm -rf {} +
}
cleanup_old_harness_runs

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
SLUG="$(basename "$GOAL_FILE" .md)"
RUN_DIR="$RUN_ROOT/${TIMESTAMP}-${SLUG}"
mkdir -p "$RUN_DIR"

TRANSCRIPT="$RUN_DIR/transcript.log"
VERIFY_LOG="$RUN_DIR/verify.log"
DIFF_LOG="$RUN_DIR/diff.patch"
SUMMARY="$RUN_DIR/summary.md"

log() { printf "[goal:%s] %s\n" "$SLUG" "$*" | tee -a "$TRANSCRIPT"; }

log "start at $TIMESTAMP"
log "max_turns=$MAX_TURNS max_minutes=$MAX_MINUTES max_diff_lines=$MAX_DIFF_LINES model=${MODEL:-default} permission_mode=$PERMISSION_MODE"
log "verify_cmd=$VERIFY_CMD"
log "goal_file=$GOAL_FILE"

if ! command -v claude >/dev/null 2>&1; then
  log "claude CLI not found in PATH; aborting"
  exit 127
fi

if command -v uuidgen >/dev/null 2>&1; then
  SESSION_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
else
  SESSION_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
fi
log "claude_session_id=$SESSION_ID"

START_EPOCH=$(date +%s)
DEADLINE=$((START_EPOCH + MAX_MINUTES * 60))

base_goal="$(cat "$GOAL_FILE")"
PROMPT_BODY="$base_goal

当前你正运行在 job-buddy 仓库的 headless 自动化任务中。请围绕上面的目标推进。
强约束：
1. 优先阅读 CLAUDE.md、相关 README 与相关代码后再修改。
2. 严格遵守 goal 中的允许修改范围和禁止事项。
3. 每轮修改后必须运行验证命令：${VERIFY_CMD}
4. 该命令会同时跑测试和评估；如果验证失败，根据输出继续修复，不得提前声明完成。
5. 预算用尽时停止并输出软着陆报告，包含：已完成内容、当前失败原因、尝试方案、下一步建议、未提交 diff 摘要。
"

run_claude_once() {
  local prompt="$1"
  local minutes_left="$2"
  local turn_index="$3"
  local args=(-p "$prompt" --permission-mode "$PERMISSION_MODE")
  if [[ "$turn_index" -eq 1 ]]; then
    args+=(--session-id "$SESSION_ID")
  else
    # 多轮修复必须延续同一会话，保留上一轮的探索与决策上下文。
    args+=(--resume "$SESSION_ID")
  fi
  if [[ -n "$MODEL" ]]; then
    args+=(--model "$MODEL")
  fi
  python3 - "$minutes_left" "$TRANSCRIPT" "${args[@]}" <<'PY'
import subprocess
import sys

max_minutes = max(1, int(sys.argv[1]))
log_path = sys.argv[2]
args = sys.argv[3:]
with open(log_path, "ab") as log:
    try:
        completed = subprocess.run(["claude", *args], stdout=log, stderr=subprocess.STDOUT, timeout=max_minutes * 60, check=False)
        sys.exit(completed.returncode)
    except subprocess.TimeoutExpired:
        log.write(f"\n[goal] claude timed out after {max_minutes} minutes\n".encode())
        sys.exit(124)
PY
}

turn=0
status="pending"
while [[ $turn -lt $MAX_TURNS ]]; do
  turn=$((turn + 1))
  now=$(date +%s)
  if [[ $now -ge $DEADLINE ]]; then
    log "time budget exhausted at turn $turn"
    status="timeout"
    break
  fi

  minutes_left=$(( (DEADLINE - now + 59) / 60 ))
  log "turn $turn / $MAX_TURNS, minutes_left=$minutes_left"

  set +e
  run_claude_once "$PROMPT_BODY" "$minutes_left" "$turn"
  rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    log "claude returned non-zero on turn $turn: $rc"
    if [[ "$rc" -eq 124 ]]; then
      status="timeout"
      break
    fi
  fi

  if [[ "$MAX_DIFF_LINES" -gt 0 ]]; then
    diff_lines=$(git diff | wc -l | tr -d ' ')
    if [[ "$diff_lines" -gt "$MAX_DIFF_LINES" ]]; then
      log "diff budget exceeded on turn $turn: $diff_lines > $MAX_DIFF_LINES lines"
      status="diff_budget_exceeded"
      break
    fi
  fi

  log "running verify"
  if bash -c "$VERIFY_CMD" >"$VERIFY_LOG" 2>&1; then
    log "verify passed on turn $turn"
    status="passed"
    break
  else
    log "verify failed on turn $turn, feeding back to next round"
    PROMPT_BODY="验证命令仍然失败。verify 输出（最后 200 行）：
$(tail -n 200 "$VERIFY_LOG")

请基于本会话已有上下文继续修复。仍须遵守允许修改范围、禁止事项、预算与软着陆条件。修复后再次调用 ${VERIFY_CMD} 自检。"
  fi
done

if [[ "$status" == "pending" ]]; then
  status="exhausted"
fi

git diff > "$DIFF_LOG" || true

{
  echo "# Goal run summary"
  echo
  echo "- slug: $SLUG"
  echo "- status: $status"
  echo "- turns: $turn / $MAX_TURNS"
  echo "- started: $TIMESTAMP"
  echo "- duration_sec: $(( $(date +%s) - START_EPOCH ))"
  echo "- model: ${MODEL:-default}"
  echo "- permission_mode: $PERMISSION_MODE"
  echo "- session_id: $SESSION_ID"
  echo "- max_diff_lines: $MAX_DIFF_LINES"
  echo "- verify_cmd: $VERIFY_CMD"
  echo "- run_dir: $RUN_DIR"
  echo
  echo "## Last verify output (tail)"
  echo
  echo '```'
  tail -n 100 "$VERIFY_LOG" 2>/dev/null || true
  echo '```'
  echo
  echo "## Diff summary"
  echo
  echo '```'
  git status --short || true
  echo '```'
  echo
  echo "## Diff size"
  echo
  echo "- lines: $(wc -l < "$DIFF_LOG" 2>/dev/null || echo 0)"
} > "$SUMMARY"

log "status=$status, summary at $SUMMARY"

JUDGE_RC=0
if [[ "$RUN_JUDGE" -eq 1 ]]; then
  set +e
  "$REPO_ROOT/.agent-harness/scripts/judge.sh" "$GOAL_FILE" "$RUN_DIR"
  JUDGE_RC=$?
  set -e
  case "$JUDGE_RC" in
    0) log "judge verdict: completed" ;;
    2) log "judge verdict: uncertain, manual review required" ;;
    *) log "judge verdict: not_completed" ;;
  esac
fi

# verify 通过且裁判未否决才算成功；uncertain 放行但已在日志标记需人工复核。
if [[ "$status" == "passed" && "$JUDGE_RC" -ne 1 ]]; then
  exit 0
fi
exit 1
