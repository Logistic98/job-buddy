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
  if [[ "$MODE" == "check" ]]; then
    (cd "$module" && uv run ruff check . && uv run ruff format --check .)
  else
    (cd "$module" && uv run ruff check --fix . && uv run ruff format .)
  fi
done

PYTHON_HARNESS="agent-backend/src/main/resources/code-runner/python-harness.py.tpl"
JAVASCRIPT_HARNESS="agent-backend/src/main/resources/code-runner/javascript-harness.js.tpl"
python_harness_formatted="$(mktemp)"
javascript_harness_formatted="$(mktemp)"
(cd agent-runtime && uv run ruff check --stdin-filename python-harness.py -) < "$PYTHON_HARNESS"
(cd agent-runtime && uv run ruff format --stdin-filename python-harness.py -) \
  < "$PYTHON_HARNESS" > "$python_harness_formatted"
(cd agent-frontend && ./node_modules/.bin/prettier --stdin-filepath javascript-harness.js --parser babel) \
  < "$JAVASCRIPT_HARNESS" > "$javascript_harness_formatted"
if [[ "$MODE" == "check" ]]; then
  if ! cmp -s "$PYTHON_HARNESS" "$python_harness_formatted"; then
    printf 'Would reformat: %s\n' "$PYTHON_HARNESS" >&2
    rm -f "$python_harness_formatted" "$javascript_harness_formatted"
    exit 1
  fi
  if ! cmp -s "$JAVASCRIPT_HARNESS" "$javascript_harness_formatted"; then
    printf 'Would reformat: %s\n' "$JAVASCRIPT_HARNESS" >&2
    rm -f "$python_harness_formatted" "$javascript_harness_formatted"
    exit 1
  fi
  rm -f "$python_harness_formatted" "$javascript_harness_formatted"
  (cd agent-frontend && npm run format:check)
else
  mv "$python_harness_formatted" "$PYTHON_HARNESS"
  mv "$javascript_harness_formatted" "$JAVASCRIPT_HARNESS"
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
