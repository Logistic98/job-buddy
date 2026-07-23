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
    uv sync --frozen --extra dev --quiet || fail "$module: uv sync --frozen --extra dev failed"
  else
    uv sync --frozen --quiet || fail "$module: uv sync --frozen failed"
  fi

  uv run ruff check . || fail "$module: ruff lint failed"
  uv run ruff format --check . || fail "$module: ruff format check failed"

  if [[ -d tests ]]; then
    # Tests must be hermetic and must not inherit real deployment auth or production-mode flags.
    # Internal-auth tests set their own values explicitly with monkeypatch.
    env -u JOB_BUDDY_RUNTIME_USE_LLM_PLANNER \
      AGENT_INTERNAL_SERVICE_TOKEN= \
      JOB_BUDDY_ENVIRONMENT=development \
      uv run python -m pytest -q || fail "$module: pytest failed"
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

  if has_npm_script format:check; then
    npm run format:check --silent || fail "$module: npm run format:check failed"
  fi

  if has_npm_script lint; then
    npm run lint --silent || fail "$module: npm run lint failed"
  fi

  if has_npm_script test; then
    npm test || fail "$module: npm test failed"
  fi

  # Production compilation is a required frontend quality signal even in quick mode.
  if has_npm_script build; then
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

run_persistence_boundary_check() {
  log "persistence boundaries"
  need_cmd python3 "Persistence boundary verification"
  python3 .agent-harness/scripts/check_persistence_boundaries.py || fail "Persistence boundary check failed"
}

run_environment_file_location_check() {
  log "environment file locations"

  local nested_env_files=()
  while IFS= read -r env_file; do
    nested_env_files+=("$env_file")
  done < <(
    find . -mindepth 2 -type f \( -name '.env' -o -name '.env.example' \) \
      -not -path './.git/*' \
      -not -path '*/node_modules/*' \
      -not -path '*/.venv/*' \
      -print | sort
  )

  if [[ "${#nested_env_files[@]}" -gt 0 ]]; then
    printf '[verify] Nested environment files are forbidden:\n' >&2
    printf '  %s\n' "${nested_env_files[@]}" >&2
    fail ".env and .env.example are only allowed at the repository root"
  fi
}

run_deployment_config_check() {
  log "deployment configuration"
  need_cmd python3 "Environment configuration verification"
  python3 -m py_compile scripts/sync-env.py \
    || fail "Environment synchronization script syntax check failed"
  if [[ -f .env ]]; then
    python3 scripts/sync-env.py || fail ".env and .env.example keys differ"
  fi
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    env -u COMPOSE_PROJECT_NAME docker compose --env-file .env.example -f docker-compose-infra.yml config --quiet \
      || fail "Infrastructure Docker Compose configuration is invalid"
    JOB_BUDDY_ENV_FILE=.env.example env -u COMPOSE_PROJECT_NAME \
      docker compose --env-file .env.example -f docker-compose.yml config --quiet \
      || fail "Application Docker Compose configuration is invalid"

    local infrastructure_name application_name infrastructure_services application_services
    infrastructure_name="$(env -u COMPOSE_PROJECT_NAME -u INFRASTRUCTURE_COMPOSE_PROJECT_NAME \
      -u APPLICATION_COMPOSE_PROJECT_NAME docker compose --env-file .env.example \
      -f docker-compose-infra.yml config | awk '/^name:/ { print $2; exit }')"
    application_name="$(JOB_BUDDY_ENV_FILE=.env.example env -u COMPOSE_PROJECT_NAME \
      -u INFRASTRUCTURE_COMPOSE_PROJECT_NAME -u APPLICATION_COMPOSE_PROJECT_NAME \
      docker compose --env-file .env.example -f docker-compose.yml config | awk '/^name:/ { print $2; exit }')"
    [[ "$infrastructure_name" == "job-buddy-infrastructure" ]] \
      || fail "Infrastructure Compose project name is not isolated"
    [[ "$application_name" == "job-buddy" ]] \
      || fail "Application Compose project name is invalid"

    infrastructure_services="$(env -u COMPOSE_PROJECT_NAME -u INFRASTRUCTURE_COMPOSE_PROJECT_NAME \
      -u APPLICATION_COMPOSE_PROJECT_NAME docker compose --env-file .env.example \
      -f docker-compose-infra.yml config --services | sort)"
    application_services="$(JOB_BUDDY_ENV_FILE=.env.example env -u COMPOSE_PROJECT_NAME \
      -u INFRASTRUCTURE_COMPOSE_PROJECT_NAME -u APPLICATION_COMPOSE_PROJECT_NAME \
      docker compose --env-file .env.example -f docker-compose.yml config --services)"
    [[ "$infrastructure_services" == $'minio\npostgres\nredis' ]] \
      || fail "Infrastructure Compose must contain only postgres, redis, and minio"
    if grep -Eq '^(postgres|redis|minio)$' <<<"$application_services"; then
      fail "Application Compose must not contain infrastructure services"
    fi
  else
    log "docker compose unavailable: skipping Compose render check"
  fi
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

run_shell_syntax_check() {
  log "shell syntax"
  need_cmd bash "Shell syntax verification"

  while IFS= read -r -d '' script; do
    bash -n "$script" || fail "Shell syntax check failed: $script"
  done < <(
    find . -type f -name '*.sh' \
      -not -path './.git/*' \
      -not -path '*/node_modules/*' \
      -not -path '*/.venv/*' \
      -print0
  )
}

run_environment_file_location_check
run_persistence_boundary_check
run_shell_syntax_check
if [[ -z "$TARGET" ]]; then
  run_deployment_config_check
fi

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
