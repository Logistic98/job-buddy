#!/usr/bin/env bash
# Independent judge for a goal run. Reads goal + transcript + verify log,
# asks a cheaper model whether the goal is truly completed, writes verdict.md.
#
# Usage:
#   judge.sh <goal_file> <run_dir>
#
# Exit codes:
#   0 = completed
#   1 = not_completed
#   2 = uncertain or verdict unparseable (manual review required)
#   3 = judge skipped (claude CLI missing)

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: judge.sh <goal_file> <run_dir>" >&2
  exit 2
fi

GOAL_FILE="$1"
RUN_DIR="$2"

if [[ ! -d "$RUN_DIR" ]]; then
  echo "run dir not found: $RUN_DIR" >&2
  exit 2
fi

JUDGE_MODEL="${JUDGE_MODEL:-claude-haiku-4-5-20251001}"
VERDICT="$RUN_DIR/verdict.md"
TRANSCRIPT="$RUN_DIR/transcript.log"
VERIFY_LOG="$RUN_DIR/verify.log"

if ! command -v claude >/dev/null 2>&1; then
  echo "claude CLI not found, skipping judge" >&2
  exit 3
fi

PROMPT="你是一个独立的任务验收裁判，只根据下方证据判断目标是否完成。
你不会调用任何工具，也不会读取文件，只能依据提供的文本。
注意：执行者的自述不可信，只采信 verify 输出等客观证据。

== 目标定义 ==
$(cat "$GOAL_FILE")

== 最近一次 verify 输出（最后 150 行）==
$(tail -n 150 "$VERIFY_LOG" 2>/dev/null || echo '(no verify log)')

== 执行 transcript（最后 300 行）==
$(tail -n 300 "$TRANSCRIPT" 2>/dev/null || echo '(no transcript)')

输出要求（严格遵守）：
第一行必须是且仅是以下三种之一，不带其他内容：
VERDICT: completed
VERDICT: not_completed
VERDICT: uncertain

随后用纯中文输出：
- 理由：列举 3 条以内具体依据
- 未满足的条件：逐项列出，无则写 无
- 下一步建议：1-3 条
"

claude -p "$PROMPT" --model "$JUDGE_MODEL" > "$VERDICT" 2>&1 || true
echo "[judge] verdict written to $VERDICT"

VERDICT_LINE="$(grep -m1 '^VERDICT:' "$VERDICT" | tr -d '[:space:]' || true)"
case "$VERDICT_LINE" in
  VERDICT:completed)
    exit 0
    ;;
  VERDICT:not_completed)
    exit 1
    ;;
  *)
    echo "[judge] verdict uncertain or unparseable: ${VERDICT_LINE:-empty}" >&2
    exit 2
    ;;
esac
