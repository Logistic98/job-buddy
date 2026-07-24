#!/usr/bin/env python3
"""检查或同步根目录 .env 与 .env.example 的配置键集合。"""

from __future__ import annotations

import argparse
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXAMPLE = ROOT / ".env.example"
ACTUAL = ROOT / ".env"
RENAMED_KEYS = {
    "JOB_BUDDY_TOOL_SEARCH_ENABLED": "JOB_BUDDY_LLM_TOOL_SEARCH_ENABLED",
}


def _entries(path: Path) -> tuple[list[str], dict[str, str]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    values: dict[str, str] = {}
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key:
            values[key] = value
    return lines, values


def _differences() -> tuple[list[str], list[str]]:
    _, expected = _entries(EXAMPLE)
    if not ACTUAL.exists():
        return list(expected), []
    _, actual = _entries(ACTUAL)
    return [key for key in expected if key not in actual], [key for key in actual if key not in expected]


def _sync() -> None:
    if not ACTUAL.exists():
        raise SystemExit(".env 不存在；请先执行 cp .env.example .env 并填写真实值")
    template_lines, defaults = _entries(EXAMPLE)
    _, current = _entries(ACTUAL)
    output: list[str] = []
    for raw in template_lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            output.append(raw)
            continue
        key = line.split("=", 1)[0].strip()
        value = current.get(key)
        if value is None and key in RENAMED_KEYS:
            value = current.get(RENAMED_KEYS[key])
        if value is None and key == "AGENT_RUNTIME_DATABASE_URL":
            value = current.get("AGENT_MEMORY_DATABASE_URL")
        output.append(f"{key}={defaults[key] if value is None else value}")
    ACTUAL.write_text("\n".join(output) + "\n", encoding="utf-8")
    os.chmod(ACTUAL, 0o600)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="按模板顺序同步键，保留已有值")
    args = parser.parse_args()
    if args.write:
        _sync()
    missing, extra = _differences()
    if missing or extra:
        if missing:
            print(".env 缺少配置项：" + ", ".join(missing))
        if extra:
            print(".env 存在模板外配置项：" + ", ".join(extra))
        return 1
    print(".env 与 .env.example 配置项一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
