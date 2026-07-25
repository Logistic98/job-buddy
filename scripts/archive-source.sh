#!/usr/bin/env bash
# 按根目录 .gitignore 规则打包仓库源码。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_NAME="$(basename "$REPO_ROOT")"
GITIGNORE_FILE="$REPO_ROOT/.gitignore"

# 默认规则覆盖项，可通过重复传入 --extra-ignore 和 --remove-ignore 扩展。
EXTRA_IGNORE_PATTERNS=(
  ".git"
)
REMOVE_IGNORE_PATTERNS=(
  ".env"
)

OUTPUT_PATH=""
TEMP_DIR=""

usage() {
  cat <<'EOF'
Usage: scripts/archive-source.sh [options]

Create a ZIP source archive using the repository root .gitignore rules.
By default, .git is additionally ignored and .env is removed from the ignore
rules, so the root .env file is included when it exists.

Options:
  -o, --output PATH          ZIP output path
  --extra-ignore PATTERN     Append an ignore pattern; may be repeated
  --remove-ignore PATTERN    Remove an ignore pattern by appending a negation;
                             may be repeated
  -h, --help                 Show this help

Default output:
  ../job-buddy-YYYYmmdd-HHMMSS.zip

Examples:
  scripts/archive-source.sh
  scripts/archive-source.sh --output ../job-buddy-source.zip
  scripts/archive-source.sh --extra-ignore "*.bak"
  scripts/archive-source.sh --remove-ignore ".env.local"
EOF
}

cleanup() {
  if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
    rm -rf "$TEMP_DIR"
  fi
}
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    -o|--output)
      [[ $# -ge 2 ]] || {
        echo "missing value for $1" >&2
        exit 2
      }
      OUTPUT_PATH="$2"
      shift 2
      ;;
    --extra-ignore)
      [[ $# -ge 2 ]] || {
        echo "missing value for $1" >&2
        exit 2
      }
      EXTRA_IGNORE_PATTERNS+=("$2")
      shift 2
      ;;
    --remove-ignore)
      [[ $# -ge 2 ]] || {
        echo "missing value for $1" >&2
        exit 2
      }
      REMOVE_IGNORE_PATTERNS+=("$2")
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

for command_name in git zip mktemp; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "required command not found: $command_name" >&2
    exit 1
  }
done

[[ -f "$GITIGNORE_FILE" ]] || {
  echo "root .gitignore not found: $GITIGNORE_FILE" >&2
  exit 1
}
git -C "$REPO_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
  echo "not a Git work tree: $REPO_ROOT" >&2
  exit 1
}

if [[ -z "$OUTPUT_PATH" ]]; then
  OUTPUT_PATH="$(dirname "$REPO_ROOT")/${PROJECT_NAME}-$(date +%Y%m%d-%H%M%S).zip"
elif [[ "$OUTPUT_PATH" != /* ]]; then
  OUTPUT_PATH="$(pwd)/$OUTPUT_PATH"
fi

OUTPUT_DIR="$(dirname "$OUTPUT_PATH")"
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd -P)"
OUTPUT_PATH="$OUTPUT_DIR/$(basename "$OUTPUT_PATH")"

[[ "$OUTPUT_PATH" == *.zip ]] || {
  echo "output path must end with .zip: $OUTPUT_PATH" >&2
  exit 2
}
[[ ! -e "$OUTPUT_PATH" ]] || {
  echo "output file already exists: $OUTPUT_PATH" >&2
  exit 1
}

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/${PROJECT_NAME}-archive.XXXXXX")"
COMBINED_IGNORE_FILE="$TEMP_DIR/combined.gitignore"
RULES_ROOT="$TEMP_DIR/rules"
SELECTION_FILE="$TEMP_DIR/selection"
STAGING_ROOT="$TEMP_DIR/$PROJECT_NAME"
mkdir -p "$RULES_ROOT" "$STAGING_ROOT"

cp "$GITIGNORE_FILE" "$COMBINED_IGNORE_FILE"
printf '\n# Additional archive ignore rules\n' >> "$COMBINED_IGNORE_FILE"
for pattern in "${EXTRA_IGNORE_PATTERNS[@]}"; do
  printf '%s\n' "$pattern" >> "$COMBINED_IGNORE_FILE"
done
printf '\n# Archive rules removed from ignore handling\n' >> "$COMBINED_IGNORE_FILE"
for pattern in "${REMOVE_IGNORE_PATTERNS[@]}"; do
  printf '!%s\n' "$pattern" >> "$COMBINED_IGNORE_FILE"
done

cp "$COMBINED_IGNORE_FILE" "$RULES_ROOT/.gitignore"
git -C "$RULES_ROOT" init -q

# ls-files 对未跟踪文件应用忽略规则；check-ignore --no-index 再处理已跟踪文件，
# 确保附加忽略规则对整个归档一致生效。
set +e
git -C "$REPO_ROOT" ls-files \
  --cached \
  --others \
  --exclude-from="$COMBINED_IGNORE_FILE" \
  -z \
  | git -C "$RULES_ROOT" check-ignore \
      --no-index \
      --verbose \
      --non-matching \
      -z \
      --stdin \
      > "$SELECTION_FILE"
PIPELINE_STATUSES=("${PIPESTATUS[@]}")
set -e

[[ "${PIPELINE_STATUSES[0]}" -eq 0 ]] || {
  echo "failed to enumerate repository files" >&2
  exit 1
}
# 没有路径命中忽略规则时，check-ignore 返回 1。
[[ "${PIPELINE_STATUSES[1]}" -eq 0 || "${PIPELINE_STATUSES[1]}" -eq 1 ]] || {
  echo "failed to evaluate archive ignore rules" >&2
  exit 1
}

FILE_COUNT=0
while IFS= read -r -d '' rule_source \
  && IFS= read -r -d '' rule_line \
  && IFS= read -r -d '' matched_pattern \
  && IFS= read -r -d '' relative_path; do
# 空规则表示未命中；前导 ! 表示取消忽略。
  [[ -z "$matched_pattern" || "$matched_pattern" == \!* ]] || continue

  source_path="$REPO_ROOT/$relative_path"
  destination_path="$STAGING_ROOT/$relative_path"

# 已跟踪文件可能已从工作区删除。
  [[ -e "$source_path" || -L "$source_path" ]] || continue
  if [[ -d "$source_path" && ! -L "$source_path" ]]; then
    echo "unsupported Git directory entry (possible submodule): $relative_path" >&2
    exit 1
  fi

  mkdir -p "$(dirname "$destination_path")"
  cp -pP "$source_path" "$destination_path"
  FILE_COUNT=$((FILE_COUNT + 1))
done < "$SELECTION_FILE"

[[ "$FILE_COUNT" -gt 0 ]] || {
  echo "no files selected for archive" >&2
  exit 1
}

(
  cd "$TEMP_DIR"
  zip -qry "$OUTPUT_PATH" "$PROJECT_NAME"
)

ARCHIVE_SIZE="$(du -h "$OUTPUT_PATH" | awk '{print $1}')"
echo "Archive created: $OUTPUT_PATH"
echo "Files packaged: $FILE_COUNT"
echo "Archive size: $ARCHIVE_SIZE"
