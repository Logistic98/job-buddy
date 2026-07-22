#!/usr/bin/env python3
"""Reject known local business-persistence patterns in production source code."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

RULES = [
    ("agent-frontend/src", ("*.js", "*.vue", "*.ts"), re.compile(r"\blocalStorage\b"), "前端业务状态不得写 localStorage"),
    ("agent-backend/src/main", ("*.java", "*.yml", "*.yaml"), re.compile(r"resume-originals|BOSS_CLI_MARKER_FILE|login-marker\.json|\.job-buddy/settings\.json"), "后端不得写本地业务文件"),
    ("agent-runtime/app", ("*.py",), re.compile(r"\.runtime_(?:results|checkpoints|traces)|result_path\.write_text|checkpoint.*write_text", re.I), "Runtime 业务状态不得落本地目录"),
    ("agent-runtime/config", ("*.yml", "*.yaml"), re.compile(r"\.runtime_(?:results|checkpoints|traces)"), "Runtime 配置不得启用本地业务目录"),
    ("agent-tool/app", ("*.py", "*.yml", "*.yaml"), re.compile(r"boss-rate-state\.json|boss-cli-home|_auth\.save_credential\(|CREDENTIAL_FILE\s*=|state_path\.write_text"), "Tool 凭证和限速状态不得落本地文件"),
]


def files(base: Path, patterns: tuple[str, ...]):
    seen: set[Path] = set()
    for pattern in patterns:
        for path in base.rglob(pattern):
            if path.is_file() and path not in seen:
                seen.add(path)
                yield path


def main() -> int:
    errors: list[str] = []
    for rel, patterns, regex, message in RULES:
        base = ROOT / rel
        for path in files(base, patterns):
            for line_number, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
                if regex.search(line):
                    errors.append(f"{path.relative_to(ROOT)}:{line_number}: {message}: {line.strip()[:180]}")
    if errors:
        print("[persistence-boundaries] FAIL", file=sys.stderr)
        for error in errors:
            print(f"[persistence-boundaries] - {error}", file=sys.stderr)
        return 1
    print("[persistence-boundaries] OK: PostgreSQL/MinIO/Redis boundaries enforced")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
