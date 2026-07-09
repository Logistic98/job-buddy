#!/usr/bin/env bash
# Unified verification entry for job-buddy.
# Returns 0 on success, non-zero on failure. Goal and loop scripts should treat
# this script's exit code as the primary machine-verifiable acceptance signal.
#
# Usage:
#   verify.sh                         # verify all known modules
#   verify.sh agent-runtime           # verify one module
#   verify.sh agent-frontend --quick  # skip slow checks when supported
#   verify.sh --list                  # list known modules
#
# Note: verify.sh is the test/build layer only. Use gate.sh when a task must
# pass both tests and behavioral evals before handoff.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"

QUICK=0
TARGET=""
LIST=0

for arg in "$@"; do
  case "$arg" in
    --quick) QUICK=1 ;;
    --list) LIST=1 ;;
    -h|--help)
      sed -n '1,18p' "$0"
      exit 0
      ;;
    *) TARGET="$arg" ;;
  esac
done

MODULES=(
  agent-backend
  agent-runtime
  agent-intent
  agent-sandbox
  agent-eval
  agent-memory
  agent-tool
  agent-frontend
)

if [[ "$LIST" -eq 1 ]]; then
  printf '%s\n' "${MODULES[@]}"
  exit 0
fi

log() { printf "[verify] %s\n" "$*"; }
fail() { printf "[verify] FAIL: %s\n" "$*" >&2; exit 1; }

need_cmd() {
  local cmd="$1"
  local context="$2"
  command -v "$cmd" >/dev/null 2>&1 || fail "$context requires '$cmd' but it is not in PATH"
}

has_npm_script() {
  local script_name="$1"
  node -e "const s = require('./package.json').scripts || {}; process.exit(Object.prototype.hasOwnProperty.call(s, process.argv[1]) ? 0 : 1)" "$script_name" 2>/dev/null
}

run_python_module() {
  local module="$1"
  log "python module: $module"
  pushd "$module" >/dev/null

  if [[ ! -f pyproject.toml ]]; then
    log "$module: no pyproject.toml, skipping Python verification"
    popd >/dev/null
    return
  fi

  need_cmd uv "$module verification"

  if grep -q '^dev[[:space:]]*=' pyproject.toml; then
    uv sync --extra dev --quiet || fail "$module: uv sync --extra dev failed"
  else
    uv sync --quiet || fail "$module: uv sync failed"
  fi

  local lint_targets=()
  for candidate in app tests server.py main.py; do
    if [[ -e "$candidate" ]]; then
      lint_targets+=("$candidate")
    fi
  done
  if [[ "${#lint_targets[@]}" -gt 0 ]]; then
    uv run ruff check "${lint_targets[@]}" || fail "$module: ruff lint failed"
  fi

  if [[ -d tests ]]; then
    env -u JOB_BUDDY_RUNTIME_USE_LLM_PLANNER uv run python -m pytest -q || fail "$module: pytest failed"
  else
    log "$module: no tests directory, skipping pytest"
  fi

  popd >/dev/null
}

run_node_module() {
  local module="$1"
  log "node module: $module"
  pushd "$module" >/dev/null

  if [[ ! -f package.json ]]; then
    log "$module: no package.json yet, skipping Node verification"
    popd >/dev/null
    return
  fi

  need_cmd npm "$module verification"

  if [[ -f package-lock.json ]]; then
    npm ci --silent || fail "$module: npm ci failed"
  elif [[ ! -d node_modules ]]; then
    npm install --silent || fail "$module: npm install failed"
  fi

  if has_npm_script lint; then
    npm run lint --silent || fail "$module: npm run lint failed"
  fi

  if has_npm_script test; then
    npm test || fail "$module: npm test failed"
  fi

  if [[ "$QUICK" -eq 0 ]] && has_npm_script build; then
    npm run build --silent || fail "$module: npm run build failed"
  fi

  popd >/dev/null
}

run_java_module() {
  local module="$1"
  log "java module: $module"
  pushd "$module" >/dev/null

  if [[ -x ./mvnw ]]; then
    if [[ "$QUICK" -eq 1 ]]; then
      ./mvnw -q test || fail "$module: mvnw test failed"
    else
      ./mvnw -q verify || fail "$module: mvnw verify failed"
    fi
  elif [[ -f pom.xml ]]; then
    need_cmd mvn "$module verification"
    if [[ "$QUICK" -eq 1 ]]; then
      mvn -q test || fail "$module: mvn test failed"
    else
      mvn -q verify || fail "$module: mvn verify failed"
    fi
  elif [[ -x ./gradlew ]]; then
    if [[ "$QUICK" -eq 1 ]]; then
      ./gradlew test || fail "$module: gradlew test failed"
    else
      ./gradlew build || fail "$module: gradlew build failed"
    fi
  elif [[ -f build.gradle || -f build.gradle.kts ]]; then
    need_cmd gradle "$module verification"
    if [[ "$QUICK" -eq 1 ]]; then
      gradle test || fail "$module: gradle test failed"
    else
      gradle build || fail "$module: gradle build failed"
    fi
  else
    log "$module: no Maven/Gradle build file yet, skipping Java verification"
  fi

  popd >/dev/null
}

run_flyway_migration_check() {
  log "flyway migrations"
  need_cmd python3 "Flyway migration verification"
  python3 .agent-harness/scripts/check_flyway_migrations.py || fail "Flyway migration check failed"
}

run_auto_module() {
  local module="$1"
  if [[ -f "$module/pyproject.toml" ]]; then
    run_python_module "$module"
  elif [[ -f "$module/package.json" ]]; then
    run_node_module "$module"
  elif [[ -f "$module/pom.xml" || -f "$module/build.gradle" || -f "$module/build.gradle.kts" || -x "$module/mvnw" || -x "$module/gradlew" ]]; then
    run_java_module "$module"
  else
    log "$module: no recognized build file yet, skipping"
  fi
}

verify_module() {
  local module="$1"
  if [[ ! -d "$module" ]]; then
    log "$module: directory missing, skipping"
    return
  fi

  case "$module" in
    agent-backend)
      run_java_module "$module" ;;
    agent-frontend)
      run_node_module "$module" ;;
    agent-runtime|agent-sandbox|agent-eval|agent-memory|agent-tool|agent-intent)
      run_auto_module "$module" ;;
    *)
      fail "unknown module: $module. Use --list to show supported modules." ;;
  esac
}

if [[ -z "$TARGET" || "$TARGET" == "agent-backend" ]]; then
  run_flyway_migration_check
fi

if [[ -n "$TARGET" ]]; then
  verify_module "$TARGET"
else
  for module in "${MODULES[@]}"; do
    verify_module "$module"
  done
fi

log "all checks passed"
