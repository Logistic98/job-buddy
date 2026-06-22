#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

APPLY=0
CLEAN_BUILD=0
LOG_RETENTION_DAYS="${LOG_RETENTION_DAYS:-14}"
HARNESS_RETENTION_DAYS="${HARNESS_RUN_RETENTION_DAYS:-30}"
RUNTIME_RETENTION_DAYS="${RUNTIME_RETENTION_DAYS:-14}"

usage() {
  cat <<'EOF'
Usage: scripts/clean-artifacts.sh [options]

默认只做 dry-run，不删除文件。需要真正删除时显式传 --apply。

Options:
  --apply                    执行删除；不传时只打印候选项
  --dry-run                  只打印候选项（默认）
  --build                    同时清理可再生成的构建产物，如 target/、dist/
  --logs-days N              .run/logs 保留天数，默认 14
  --harness-days N           .agent-harness/runs 保留天数，默认 30
  --runtime-days N           .run 下零散过程日志保留天数，默认 14
  -h, --help                 显示帮助

Examples:
  scripts/clean-artifacts.sh --dry-run
  scripts/clean-artifacts.sh --apply
  scripts/clean-artifacts.sh --apply --build --logs-days 14 --harness-days 30
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply) APPLY=1; shift ;;
    --dry-run) APPLY=0; shift ;;
    --build) CLEAN_BUILD=1; shift ;;
    --logs-days) LOG_RETENTION_DAYS="${2:?missing value for --logs-days}"; shift 2 ;;
    --harness-days) HARNESS_RETENTION_DAYS="${2:?missing value for --harness-days}"; shift 2 ;;
    --runtime-days) RUNTIME_RETENTION_DAYS="${2:?missing value for --runtime-days}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$LOG_RETENTION_DAYS:$HARNESS_RETENTION_DAYS:$RUNTIME_RETENTION_DAYS" in
  (*[!0-9:]*|'') echo "retention days must be non-negative integers" >&2; exit 2 ;;
esac

TOTAL=0

say_mode() {
  if [[ "$APPLY" -eq 1 ]]; then
    echo "[clean] mode=apply"
  else
    echo "[clean] mode=dry-run, pass --apply to delete"
  fi
  echo "[clean] logs_retention_days=$LOG_RETENTION_DAYS harness_retention_days=$HARNESS_RETENTION_DAYS runtime_retention_days=$RUNTIME_RETENTION_DAYS"
}

remove_path() {
  local path="$1"
  [[ -e "$path" || -L "$path" ]] || return 0
  TOTAL=$((TOTAL + 1))
  if [[ "$APPLY" -eq 1 ]]; then
    rm -rf -- "$path"
    echo "[clean] removed $path"
  else
    echo "[clean] would remove $path"
  fi
}

clean_python_and_os_cache() {
  echo "[clean] scanning python/test/os caches"
  while IFS= read -r path; do
    remove_path "$path"
  done < <(
    find . \
      -path './.git' -prune -o \
      -path '*/.venv' -prune -o \
      -path '*/node_modules' -prune -o \
      -type d \( -name '__pycache__' -o -name '.pytest_cache' -o -name '.mypy_cache' -o -name '.ruff_cache' \) -print \
      | sort
  )

  while IFS= read -r path; do
    remove_path "$path"
  done < <(
    find . \
      -path './.git' -prune -o \
      -path '*/.venv' -prune -o \
      -path '*/node_modules' -prune -o \
      -path '*/__pycache__/*' -prune -o \
      -type f \( -name '*.pyc' -o -name '.coverage' -o -name '.DS_Store' -o -name 'npm-debug.log*' -o -name 'yarn-debug.log*' -o -name 'pnpm-debug.log*' \) -print \
      | sort
  )
}

clean_build_outputs() {
  [[ "$CLEAN_BUILD" -eq 1 ]] || return 0
  echo "[clean] scanning build outputs"
  while IFS= read -r path; do
    remove_path "$path"
  done < <(
    find . -maxdepth 2 \
      -path './.git' -prune -o \
      -path './.external' -prune -o \
      -path '*/.venv' -prune -o \
      -path '*/node_modules' -prune -o \
      -type d \( -name 'target' -o -name 'dist' -o -name 'build' \) -print \
      | sort
  )
}

clean_dated_logs() {
  echo "[clean] scanning dated runtime logs"
  if [[ -d .run/logs ]]; then
    while IFS= read -r path; do
      remove_path "$path"
    done < <(find .run/logs -mindepth 1 -maxdepth 1 -type d -name '20[0-9][0-9][0-9][0-9][0-9][0-9]' -mtime +"$LOG_RETENTION_DAYS" -print | sort)
    while IFS= read -r path; do
      remove_path "$path"
    done < <(find .run/logs -maxdepth 1 -type f -name '*.log' -mtime +"$LOG_RETENTION_DAYS" -print | sort)
  fi

  if [[ -d .agent-harness/runs ]]; then
    while IFS= read -r path; do
      remove_path "$path"
    done < <(find .agent-harness/runs -mindepth 1 -maxdepth 1 -type d -mtime +"$HARNESS_RETENTION_DAYS" -print | sort)
  fi

  if [[ -d .run ]]; then
    while IFS= read -r path; do
      remove_path "$path"
    done < <(find .run -maxdepth 1 -type f -name '*.log' -mtime +"$RUNTIME_RETENTION_DAYS" -print | sort)
  fi
}

say_mode
clean_python_and_os_cache
clean_dated_logs
clean_build_outputs

if [[ "$TOTAL" -eq 0 ]]; then
  echo "[clean] no candidates found"
else
  echo "[clean] candidates=$TOTAL"
fi
