#!/usr/bin/env bash
# 开发交付质量门；Agent 报告任务完成前必须通过。
#
# 用法：
#   gate.sh                         # 快速执行全部模块测试与评估
#   gate.sh agent-backend --quick   # 执行 Backend 测试与评估
#   gate.sh all --full              # 完整验证与全部评估
#   gate.sh agent-backend --no-eval # 仅测试，不推荐

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

TARGET="all"
QUICK=1
RUN_EVAL=1
RUN_DOCTOR=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --quick)
      QUICK=1; shift ;;
    --full)
      QUICK=0; shift ;;
    --no-eval)
      RUN_EVAL=0; shift ;;
    --doctor)
      RUN_DOCTOR=1; shift ;;
    -h|--help)
      sed -n '1,12p' "$0"; exit 0 ;;
    -*)
      echo "unknown argument: $1" >&2; exit 2 ;;
    *)
      TARGET="$1"; shift ;;
  esac
done

is_known_target() {
  local candidate="$1"
  local module
  [[ "$candidate" == "all" ]] && return 0
  while IFS= read -r module; do
    [[ "$module" == "$candidate" ]] && return 0
  done < <(./.agent-harness/scripts/verify.sh --list)
  return 1
}

if ! is_known_target "$TARGET"; then
  echo "unknown target: $TARGET" >&2
  exit 2
fi

RUN_ROOT="$REPO_ROOT/.agent-harness/runs"
HARNESS_RUN_RETENTION_DAYS="${HARNESS_RUN_RETENTION_DAYS:-30}"
cleanup_old_harness_runs() {
  [[ "${HARNESS_CLEANUP_ENABLED:-1}" == "1" ]] || return 0
  [[ -d "$RUN_ROOT" ]] || return 0
  find "$RUN_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime +"$HARNESS_RUN_RETENTION_DAYS" -exec rm -rf {} +
}
cleanup_old_harness_runs

RUN_DIR="$RUN_ROOT/gate-$(date +%Y%m%d-%H%M%S)-${TARGET}"
mkdir -p "$RUN_DIR"
VERIFY_LOG="$RUN_DIR/verify.log"
EVAL_LOG="$RUN_DIR/evaluate.log"
SUMMARY="$RUN_DIR/summary.md"

log() { printf "[gate] %s\n" "$*" | tee -a "$RUN_DIR/gate.log"; }
fail_with_log() {
  local message="$1"
  log "FAIL: $message"
  {
    echo "# Quality gate failed"
    echo
    echo "- target: $TARGET"
    echo "- quick: $QUICK"
    echo "- run_dir: $RUN_DIR"
    echo "- reason: $message"
    echo
    echo "## verify tail"
    echo '```'
    tail -n 120 "$VERIFY_LOG" 2>/dev/null || true
    echo '```'
    echo
    echo "## eval tail"
    echo '```'
    tail -n 120 "$EVAL_LOG" 2>/dev/null || true
    echo '```'
  } > "$SUMMARY"
  cat "$SUMMARY" >&2
  exit 1
}

if [[ "$RUN_DOCTOR" -eq 1 ]]; then
  log "doctor"
  ./.agent-harness/scripts/doctor.sh > "$RUN_DIR/doctor.log" 2>&1 || fail_with_log "doctor failed"
fi

verify_target=()
if [[ "$TARGET" != "all" ]]; then
  verify_target+=("$TARGET")
fi
if [[ "$QUICK" -eq 1 ]]; then
  verify_target+=("--quick")
fi

log "verify: ./.agent-harness/scripts/verify.sh ${verify_target[*]:-}"
# ${arr[@]+...} 保证 macOS Bash 3.2 在 set -u 下安全展开空数组。
if ./.agent-harness/scripts/verify.sh ${verify_target[@]+"${verify_target[@]}"} > "$VERIFY_LOG" 2>&1; then
  log "verify passed"
else
  fail_with_log "verify failed"
fi

if [[ "$RUN_EVAL" -eq 1 ]]; then
  eval_target="$TARGET"
  log "evaluate: ./.agent-harness/scripts/evaluate.sh $eval_target"
  if ./.agent-harness/scripts/evaluate.sh "$eval_target" > "$EVAL_LOG" 2>&1; then
    log "evaluate passed"
  else
    fail_with_log "evaluate failed"
  fi
else
  log "evaluate skipped by --no-eval"
fi

{
  echo "# Quality gate passed"
  echo
  echo "- target: $TARGET"
  echo "- quick: $QUICK"
  echo "- eval: $RUN_EVAL"
  echo "- run_dir: $RUN_DIR"
  echo "- finished_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "## verify tail"
  echo '```'
  tail -n 80 "$VERIFY_LOG" 2>/dev/null || true
  echo '```'
  echo
  echo "## eval tail"
  echo '```'
  tail -n 80 "$EVAL_LOG" 2>/dev/null || true
  echo '```'
} > "$SUMMARY"

log "passed; summary=$SUMMARY"
