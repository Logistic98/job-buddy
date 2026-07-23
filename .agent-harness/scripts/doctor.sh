#!/usr/bin/env bash
# Check local prerequisites for the job-buddy automation harness.
# This script is intentionally read-only and safe to run before starting goals or loops.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

missing=0
warn=0

ok() { printf "[doctor] OK: %s\n" "$*"; }
warn_msg() { printf "[doctor] WARN: %s\n" "$*"; warn=$((warn + 1)); }
missing_msg() { printf "[doctor] MISSING: %s\n" "$*"; missing=$((missing + 1)); }

need_cmd() {
  local cmd="$1"
  local hint="$2"
  if command -v "$cmd" >/dev/null 2>&1; then
    ok "$cmd found: $(command -v "$cmd")"
  else
    missing_msg "$cmd not found. $hint"
  fi
}

need_optional_cmd() {
  local cmd="$1"
  local hint="$2"
  if command -v "$cmd" >/dev/null 2>&1; then
    ok "$cmd found: $(command -v "$cmd")"
  else
    warn_msg "$cmd not found. $hint"
  fi
}

need_java17() {
  if ! command -v java >/dev/null 2>&1; then
    warn_msg "java not found. JDK 17+ is required for agent-backend verification."
    return
  fi

  local version major remainder
  version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
  major="${version%%.*}"
  if [[ "$major" == "1" ]]; then
    remainder="${version#*.}"
    major="${remainder%%.*}"
  fi

  if [[ "$major" =~ ^[0-9]+$ ]] && (( major >= 17 )); then
    ok "java found: $(command -v java) (version $version)"
  else
    warn_msg "java version $version found at $(command -v java); JDK 17+ is required for agent-backend verification."
  fi
}

need_cmd git "Install Git and ensure it is available in PATH."
need_cmd bash "Install Bash and ensure it is available in PATH."
need_cmd python3 "Install Python 3.10+ for helper scripts and timeout wrappers."
need_optional_cmd claude "Required only for run_goal.sh / loop.sh execution. Install and authenticate Claude Code CLI."
need_optional_cmd uv "Required for Python module verification. Install uv if you work on backend modules."
need_optional_cmd npm "Required for Vue frontend and sandbox-runtime dependencies."
need_java17
need_optional_cmd mvn "Required for agent-backend verification: the repo has no mvnw wrapper, verify.sh/gate.sh fall back to global mvn."

if [[ -d .git ]]; then
  ok "git repository detected"
else
  missing_msg "current directory is not a git repository"
fi

if [[ -f CLAUDE.md ]]; then
  ok "CLAUDE.md project context found"
else
  warn_msg "CLAUDE.md not found; agent runs will have less project context"
fi

for script in .agent-harness/scripts/verify.sh .agent-harness/scripts/evaluate.sh .agent-harness/scripts/gate.sh .agent-harness/scripts/check_flyway_migrations.py agent-backend/scripts/quality-gate.sh; do
  if [[ -x "$script" ]]; then
    ok "$script is executable"
  else
    missing_msg "$script is missing or not executable"
  fi
done

cat <<EOF
[doctor] summary: missing=$missing warnings=$warn
EOF

[[ "$missing" -eq 0 ]]
