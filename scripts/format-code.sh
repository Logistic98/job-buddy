#!/usr/bin/env bash
# Applies or verifies the repository's language-specific source formatters.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="apply"

if [[ "${1:-}" == "--check" ]]; then
  MODE="check"
elif [[ "$#" -gt 0 ]]; then
  printf 'Usage: %s [--check]\n' "$0" >&2
  exit 2
fi

cd "$REPO_ROOT"

if [[ "$MODE" == "check" ]]; then
  (cd agent-backend && mvn -q spotless:check)
else
  (cd agent-backend && mvn -q spotless:apply)
fi

PYTHON_MODULES=(
  agent-runtime
  agent-intent
  agent-sandbox
  agent-eval
  agent-memory
  agent-tool
)

for module in "${PYTHON_MODULES[@]}"; do
  targets=()
  for candidate in app tests server.py main.py; do
    if [[ -e "$module/$candidate" ]]; then
      targets+=("$candidate")
    fi
  done

  if [[ "${#targets[@]}" -eq 0 ]]; then
    continue
  fi

  if [[ "$MODE" == "check" ]]; then
    (cd "$module" && uv run ruff check "${targets[@]}" && uv run ruff format --check "${targets[@]}")
  else
    (cd "$module" && uv run ruff check --fix "${targets[@]}" && uv run ruff format "${targets[@]}")
  fi
done

if [[ "$MODE" == "check" ]]; then
  (cd agent-frontend && npm run format:check)
else
  (cd agent-frontend && npm run format)
fi

while IFS= read -r -d '' script; do
  bash -n "$script"
done < <(
  find . -type f -name '*.sh' \
    -not -path './.git/*' \
    -not -path '*/node_modules/*' \
    -not -path '*/.venv/*' \
    -print0
)
