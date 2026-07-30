#!/usr/bin/env bash
# 输出不含凭据值的 Gate 运行环境与依赖清单摘要。

set -uo pipefail

METADATA_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

first_line_or_unavailable() {
  local command_name="$1"
  shift
  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf '%s' "unavailable"
    return
  fi
  local value
  if [[ "$command_name" == "java" ]]; then
    value="$(
      env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS -u MAVEN_OPTS -u MAVEN_ARGS \
        "$@" 2>&1 | sed -n '1p'
    )" || true
  else
    value="$(
      env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS -u MAVEN_OPTS -u MAVEN_ARGS \
        "$@" 2>/dev/null | sed -n '1p'
    )" || true
  fi
  if [[ -n "$value" ]]; then
    printf '%s' "$value"
  else
    printf '%s' "unavailable"
  fi
}

file_sha256() {
  local path="$1"
  if command -v shasum >/dev/null 2>&1; then
    LC_ALL=C shasum -a 256 "$path" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
  else
    printf '%s\n' "unavailable"
  fi
}

cpu_count() {
  local value=""
  if command -v sysctl >/dev/null 2>&1; then
    value="$(sysctl -n hw.ncpu 2>/dev/null)" || true
  fi
  if [[ -z "$value" ]] && command -v getconf >/dev/null 2>&1; then
    value="$(getconf _NPROCESSORS_ONLN 2>/dev/null)" || true
  fi
  printf '%s' "${value:-unavailable}"
}

memory_bytes() {
  local value=""
  if command -v sysctl >/dev/null 2>&1; then
    value="$(sysctl -n hw.memsize 2>/dev/null)" || true
  fi
  if [[ -z "$value" && -r /proc/meminfo ]]; then
    value="$(awk '/^MemTotal:/ {print $2 * 1024; exit}' /proc/meminfo)" || true
  fi
  printf '%s' "${value:-unavailable}"
}

cd "$METADATA_REPO_ROOT"

git_sha="$(git rev-parse HEAD 2>/dev/null)" || git_sha="unavailable"
git_branch="$(git branch --show-current 2>/dev/null)" || git_branch="unavailable"
git_changed_count="$(git status --porcelain --untracked-files=normal 2>/dev/null | wc -l | tr -d ' ')" || {
  git_changed_count="unavailable"
}
if [[ "$git_changed_count" == "unavailable" ]]; then
  git_dirty="unavailable"
elif [[ "$git_changed_count" == "0" ]]; then
  git_dirty="false"
else
  git_dirty="true"
fi

docker_daemon="unavailable"
docker_server="unavailable"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  docker_daemon="available"
  docker_server="$(docker version --format '{{.Server.Version}}' 2>/dev/null)" || docker_server="unavailable"
  [[ -n "$docker_server" ]] || docker_server="unavailable"
fi

printf '%s\n' "## Run metadata"
printf '%s\n' "- collected_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '%s\n' "- git_sha: $git_sha"
printf '%s\n' "- git_branch: ${git_branch:-detached}"
printf '%s\n' "- git_dirty: $git_dirty"
printf '%s\n' "- git_changed_paths: $git_changed_count"
printf '%s\n' "- os: $(uname -s 2>/dev/null || printf unavailable)"
printf '%s\n' "- os_release: $(uname -r 2>/dev/null || printf unavailable)"
printf '%s\n' "- architecture: $(uname -m 2>/dev/null || printf unavailable)"
printf '%s\n' "- cpu_count: $(cpu_count)"
printf '%s\n' "- memory_bytes: $(memory_bytes)"
printf '%s\n' "- java: $(first_line_or_unavailable java java -version)"
printf '%s\n' "- maven: $(first_line_or_unavailable mvn mvn -version)"
printf '%s\n' "- python: $(first_line_or_unavailable python3 python3 --version)"
printf '%s\n' "- uv: $(first_line_or_unavailable uv uv --version)"
printf '%s\n' "- node: $(first_line_or_unavailable node node --version)"
printf '%s\n' "- npm: $(first_line_or_unavailable npm npm --version)"
printf '%s\n' "- docker_client: $(first_line_or_unavailable docker docker --version)"
printf '%s\n' "- docker_daemon: $docker_daemon"
printf '%s\n' "- docker_server: $docker_server"
printf '%s\n' "- docker_compose: $(first_line_or_unavailable docker docker compose version)"
printf '%s\n' "- live_model: not_used_by_deterministic_gate"
printf '%s\n' "- model_parameters: not_applicable"
printf '%s\n' "- llm_judge: not_invoked_by_deterministic_gate"
printf '\n%s\n' "### Dependency manifests"

manifest_count=0
while IFS= read -r manifest; do
  relative_path="${manifest#"$METADATA_REPO_ROOT"/}"
  printf '%s\n' "- $relative_path: $(file_sha256 "$manifest")"
  manifest_count=$((manifest_count + 1))
done < <(
  find "$METADATA_REPO_ROOT" -maxdepth 3 -type f \
    \( -name 'pom.xml' -o -name 'uv.lock' -o -name 'package-lock.json' \) \
    | sort
)
if [[ "$manifest_count" -eq 0 ]]; then
  printf '%s\n' "- unavailable"
fi
