#!/usr/bin/env bash
# 仓库提交前检查：校验暂存差异，并对变更模块执行 verify.sh --quick。
# 公共构建、工作流、脚本或 Harness 变更会触发全模块验证。
#
# 用法（先执行 install-git-hooks.sh）：
#   git commit ...   # 自动运行 Hook
# 手工执行：
#   .agent-harness/scripts/pre-commit-hook.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

STAGED_FILES="$(git diff --cached --name-only --diff-filter=ACDMR)"
if [[ -z "$STAGED_FILES" ]]; then
  exit 0
fi

echo "[pre-commit] checking staged diff"
git diff --cached --check

if grep -Eq '^(\.agent-harness/|\.github/workflows/|scripts/|docker-compose[^/]*\.ya?ml$|\.env\.example$|AGENTS\.md$|CLAUDE\.md$)' <<<"$STAGED_FILES"; then
  echo "[pre-commit] shared verification change detected"
  if ! ./.agent-harness/scripts/verify.sh --quick; then
    echo "[pre-commit] repository verify failed, blocking commit"
    exit 1
  fi
  exit 0
fi

declare -a TARGETS=()
while IFS= read -r module; do
  if grep -q "^${module}/" <<<"$STAGED_FILES"; then
    TARGETS+=("$module")
  fi
done < <(./.agent-harness/scripts/verify.sh --list)

if [[ ${#TARGETS[@]} -eq 0 ]]; then
  echo "[pre-commit] no executable module changed; staged diff check passed"
  exit 0
fi

for target in "${TARGETS[@]}"; do
  echo "[pre-commit] verify.sh ${target} --quick"
  if ! ./.agent-harness/scripts/verify.sh "$target" --quick; then
    echo "[pre-commit] ${target} verify failed, blocking commit"
    exit 1
  fi
done
