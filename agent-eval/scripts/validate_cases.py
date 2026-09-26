#!/usr/bin/env python3
"""校验整个用例目录并打印覆盖清单；不发起模型或业务请求。"""

import argparse
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.cases import load_suite  # noqa: E402


def validate_directory(directory: Path) -> list[dict]:
    paths = sorted(directory.rglob("*.yaml"))
    if not paths:
        raise ValueError("no YAML suites found")
    if list(directory.rglob("*.json")) or list(directory.rglob("*.yml")):
        raise ValueError("case datasets must use the unified .yaml format")
    suites = [load_suite(path) for path in paths]
    if len({suite["id"] for suite in suites}) != len(suites):
        raise ValueError("duplicate suite id")
    return suites


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=ROOT / "cases")
    args = parser.parse_args()
    try:
        suites = validate_directory(args.directory)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    for suite in suites:
        categories = Counter(case["category"] for case in suite["cases"])
        print(f"{suite['id']} [{suite['kind']}]: {len(suite['cases'])} cases; {dict(sorted(categories.items()))}")
    print(f"Validated {len(suites)} suites / {sum(len(suite['cases']) for suite in suites)} cases")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
