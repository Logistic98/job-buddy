#!/usr/bin/env python3
"""Require at least 80% production line coverage; preserve branch reporting."""

import json
import sys
from pathlib import Path


def check_report(path: Path) -> None:
    report = json.loads(path.read_text())
    totals = report["totals"]
    if not report["files"] or totals["num_statements"] <= 0:
        raise ValueError("coverage report contains no production statements")
    missing = totals["missing_lines"]
    statements = totals["num_statements"]
    covered = statements - missing
    if covered * 100 < statements * 80:
        raise ValueError(f"line coverage must be at least 80%: {missing} of {statements} lines uncovered")
    print(
        f"Line coverage: {covered / statements:.2%} ({statements} statements); "
        f"missing branches: {totals.get('missing_branches', 0)}"
    )


if __name__ == "__main__":
    check_report(Path(sys.argv[1]))
