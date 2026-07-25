#!/bin/sh

set -eu

ROOT=$(CDPATH= cd "$(dirname "$0")/.." && pwd -P)
EXAMPLE="$ROOT/.env.example"
ACTUAL="$ROOT/.env"

usage() {
    printf '用法: %s [--write]\n' "$0" >&2
}

sync_env() {
    if [ ! -f "$ACTUAL" ]; then
        printf '%s\n' '.env 不存在；请先执行 cp .env.example .env 并填写真实值' >&2
        exit 1
    fi

    temporary_file=$(mktemp "${ACTUAL}.tmp.XXXXXX")
    trap 'rm -f "$temporary_file"' EXIT HUP INT TERM

    if ! awk -v actual_file="$ACTUAL" '
        function parse_entry(raw, trimmed, separator) {
            trimmed = raw
            sub(/^[[:space:]]+/, "", trimmed)
            sub(/[[:space:]]+$/, "", trimmed)
            if (trimmed == "" || trimmed ~ /^#/ || index(trimmed, "=") == 0) {
                return 0
            }

            separator = index(trimmed, "=")
            parsed_key = substr(trimmed, 1, separator - 1)
            sub(/^[[:space:]]+/, "", parsed_key)
            sub(/[[:space:]]+$/, "", parsed_key)
            if (parsed_key == "") {
                return 0
            }

            parsed_value = substr(trimmed, separator + 1)
            return 1
        }

        FILENAME == actual_file {
            if (parse_entry($0)) {
                current[parsed_key] = parsed_value
            }
            next
        }

        {
            raw = $0
            if (!parse_entry(raw)) {
                print raw
                next
            }

            key = parsed_key
            default_value = parsed_value
            if (key in current) {
                value = current[key]
            } else if (key == "AGENT_RUNTIME_DATABASE_URL" && ("AGENT_MEMORY_DATABASE_URL" in current)) {
                value = current["AGENT_MEMORY_DATABASE_URL"]
            } else {
                value = default_value
            }
            print key "=" value
        }
    ' "$ACTUAL" "$EXAMPLE" >"$temporary_file"; then
        rm -f "$temporary_file"
        trap - EXIT HUP INT TERM
        exit 1
    fi

    chmod 600 "$temporary_file"
    mv "$temporary_file" "$ACTUAL"
    trap - EXIT HUP INT TERM
}

check_env() {
    awk '
        function parse_key(raw, trimmed, separator, key) {
            trimmed = raw
            sub(/^[[:space:]]+/, "", trimmed)
            sub(/[[:space:]]+$/, "", trimmed)
            if (trimmed == "" || trimmed ~ /^#/ || index(trimmed, "=") == 0) {
                return ""
            }

            separator = index(trimmed, "=")
            key = substr(trimmed, 1, separator - 1)
            sub(/^[[:space:]]+/, "", key)
            sub(/[[:space:]]+$/, "", key)
            return key
        }

        FILENAME == ARGV[1] {
            key = parse_key($0)
            if (key != "" && !(key in expected)) {
                expected_order[++expected_count] = key
            }
            if (key != "") {
                expected[key] = 1
            }
            next
        }

        {
            key = parse_key($0)
            if (key != "" && !(key in actual)) {
                actual_order[++actual_count] = key
            }
            if (key != "") {
                actual[key] = 1
            }
        }

        END {
            missing_count = 0
            extra_count = 0

            for (index_value = 1; index_value <= expected_count; index_value++) {
                key = expected_order[index_value]
                if (!(key in actual)) {
                    missing[++missing_count] = key
                }
            }
            for (index_value = 1; index_value <= actual_count; index_value++) {
                key = actual_order[index_value]
                if (!(key in expected)) {
                    extra[++extra_count] = key
                }
            }

            if (missing_count > 0) {
                printf ".env 缺少配置项："
                for (index_value = 1; index_value <= missing_count; index_value++) {
                    separator = index_value == 1 ? "" : ", "
                    printf "%s%s", separator, missing[index_value]
                }
                print ""
            }
            if (extra_count > 0) {
                printf ".env 存在模板外配置项："
                for (index_value = 1; index_value <= extra_count; index_value++) {
                    separator = index_value == 1 ? "" : ", "
                    printf "%s%s", separator, extra[index_value]
                }
                print ""
            }

            if (missing_count > 0 || extra_count > 0) {
                exit 1
            }
            print ".env 与 .env.example 配置项一致"
        }
    ' "$EXAMPLE" "$1"
}

write=false
case "${1-}" in
    "")
        ;;
    --write)
        write=true
        ;;
    *)
        usage
        exit 2
        ;;
esac

if [ "$#" -gt 1 ]; then
    usage
    exit 2
fi

if [ ! -f "$EXAMPLE" ]; then
    printf '%s\n' ".env.example 不存在：$EXAMPLE" >&2
    exit 1
fi

if [ "$write" = true ]; then
    sync_env
fi

if [ -f "$ACTUAL" ]; then
    check_env "$ACTUAL"
else
    check_env /dev/null
fi
