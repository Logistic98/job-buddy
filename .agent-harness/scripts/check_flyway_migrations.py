#!/usr/bin/env python3
"""Validate Flyway migration structure and data boundaries."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

MIGRATION_RE = re.compile(r"^V(?P<version>\d+_\d+_\d+)__(?P<description>[A-Za-z][A-Za-z0-9_]*)\.sql$")
DESCRIPTION_PREFIXES = ("Create", "Insert", "Alter", "Update", "Delete", "Drop", "Rename")
DEFAULT_MIGRATION_DIR = "agent-backend/src/main/resources/db/migration"
SYSTEM_DATA_TABLES = {
    "tenant",
    "permission_definition",
    "rbac_role",
    "rbac_menu",
    "role_menu",
    "blacklist_item",
}
DEFAULT_IDENTITY_TABLES = {"app_user", "user_role", "user_login_session"}
DEFAULT_IDENTITY_LITERAL_RE = re.compile(r"'(?:job_buddy_admin|job_buddy_user|admin|user)'", re.I)
DML_TABLE_RE = re.compile(
    r'\b(?P<operation>INSERT\s+INTO|UPDATE|DELETE\s+FROM)\s+(?:ONLY\s+)?'
    r'(?:(?:"?[A-Za-z_][A-Za-z0-9_]*"?)\.)?"?(?P<table>[A-Za-z_][A-Za-z0-9_]*)"?',
    re.I,
)
TEMP_TABLE_RE = re.compile(
    r'\bCREATE\s+(?:LOCAL\s+)?TEMP(?:ORARY)?\s+TABLE\s+'
    r'(?:(?:"?[A-Za-z_][A-Za-z0-9_]*"?)\.)?"?(?P<table>[A-Za-z_][A-Za-z0-9_]*)"?',
    re.I,
)


@dataclass(frozen=True)
class Migration:
    path: Path
    rel_path: str
    version_text: str


def collect(root: Path, directory: Path) -> tuple[list[Migration], list[str]]:
    errors: list[str] = []
    migrations: list[Migration] = []
    seen: dict[str, str] = {}
    for path in sorted(directory.glob("*.sql")):
        match = MIGRATION_RE.match(path.name)
        rel = path.relative_to(root).as_posix()
        if not match:
            errors.append(f"{rel}: invalid Flyway filename")
            continue
        version_text = match.group("version")
        description = match.group("description")
        if not description.startswith(DESCRIPTION_PREFIXES):
            errors.append(f"{rel}: description must start with a SQL action verb")
            continue
        if any(len(part) > 1 and part.startswith("0") for part in version_text.split("_")):
            errors.append(f"{rel}: version segments must not have leading zeroes")
            continue
        if version_text in seen:
            errors.append(f"duplicate Flyway version V{version_text}: {seen[version_text]} and {rel}")
            continue
        seen[version_text] = rel
        migrations.append(Migration(path, rel, version_text))
    return migrations, errors


def dml_targets(sql: str) -> list[tuple[str, str]]:
    targets: list[tuple[str, str]] = []
    for match in DML_TABLE_RE.finditer(sql):
        operation = " ".join(match.group("operation").upper().split())
        table = match.group("table").lower()
        if operation == "UPDATE" and table == "set":
            # PostgreSQL upserts contain "DO UPDATE SET", which is not a
            # second table target.
            continue
        targets.append((operation, table))
    return targets


def validate_migration_policy(migrations: list[Migration]) -> list[str]:
    errors: list[str] = []
    for migration in migrations:
        sql = migration.path.read_text(encoding="utf-8")
        temporary_tables = {
            match.group("table").lower() for match in TEMP_TABLE_RE.finditer(sql)
        }
        for operation, table in dml_targets(sql):
            if table in SYSTEM_DATA_TABLES or table in temporary_tables:
                continue
            if table in DEFAULT_IDENTITY_TABLES and DEFAULT_IDENTITY_LITERAL_RE.search(sql):
                continue
            errors.append(f"{migration.rel_path}: {operation} on non-system table {table} is forbidden")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--migration-dir", default=DEFAULT_MIGRATION_DIR)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    directory = root / args.migration_dir
    if not directory.is_dir():
        print(f"[flyway] FAIL: migration directory missing: {directory}", file=sys.stderr)
        return 1

    migrations, errors = collect(root, directory)
    errors.extend(validate_migration_policy(migrations))

    if errors:
        print("[flyway] FAIL", file=sys.stderr)
        for error in errors:
            print(f"[flyway] - {error}", file=sys.stderr)
        return 1
    print(
        f"[flyway] OK: {len(migrations)} migrations; naming, uniqueness, and data boundaries validated"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
