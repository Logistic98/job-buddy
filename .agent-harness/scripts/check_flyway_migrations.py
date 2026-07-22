#!/usr/bin/env python3
"""Validate the canonical Flyway baseline and append-only migrations."""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
from dataclasses import dataclass
from pathlib import Path

MIGRATION_RE = re.compile(r"^V(?P<version>\d+_\d+_\d+)__(?P<description>[A-Za-z][A-Za-z0-9_]*)\.sql$")
DESCRIPTION_PREFIXES = ("Create", "Insert", "Alter", "Update", "Delete", "Drop", "Rename")
DEFAULT_MIGRATION_DIR = "agent-backend/src/main/resources/db/migration"
BASELINE_CHECKSUM_FILE = ".agent-harness/flyway-baseline.sha256"
BASELINE_CHECKSUM_MANIFEST_SHA256 = "9a724411a53881d69de32090194d5ecab76945aa6dd6c759c581c64544e462e2"
CANONICAL_BASELINE = {
    "V1_0_0__Create_identity_and_access_schema.sql",
    "V1_0_1__Create_resume_and_storage_schema.sql",
    "V1_0_2__Create_chat_and_agent_schema.sql",
    "V1_0_3__Create_job_and_journey_schema.sql",
    "V1_0_4__Create_interview_schema.sql",
    "V1_0_5__Create_project_schema.sql",
    "V1_0_6__Create_analysis_and_platform_schema.sql",
    "V1_0_7__Insert_default_authorization_data.sql",
}
BASELINE_MAX_VERSION = (1, 0, 7)
EVOLUTION_RE = re.compile(r"\b(?:ALTER\s+TABLE|RENAME\s+(?:TABLE|COLUMN)|DROP\s+(?:TABLE|COLUMN))\b", re.I)
DML_RE = re.compile(r"\b(?:INSERT\s+INTO|UPDATE|DELETE\s+FROM)\b", re.I)
BASELINE_DATA_TABLES = {
    "tenant", "permission_definition", "rbac_role", "rbac_menu", "role_menu", "app_user", "user_role"
}
CONTROLLED_SYSTEM_SEED_MIGRATIONS = {
    "V1_0_8__Insert_default_job_blacklist.sql": {"blacklist_item"},
}
INSERT_TABLE_RE = re.compile(r"\bINSERT\s+INTO\s+(?:public\.)?\"?(?P<table>[A-Za-z_][A-Za-z0-9_]*)\"?", re.I)
PRIVATE_BUSINESS_TABLES = {
    "agent_run_checkpoint", "analysis_task", "app_user", "auth_state", "blacklist_item",
    "boss_qr_login_session", "chat_message_log", "chat_session_state", "interview_exam",
    "interview_exam_question", "interview_question", "job_favorite", "journey_record",
    "journey_target", "platform_setting", "profile_document", "project_deep_dive_material",
    "project_deep_dive_project", "project_deep_dive_question", "resume_asset", "resume_record",
    "resume_writer_version", "user_login_session", "user_permission", "user_role",
    "user_workspace_state",
}
PRIVATE_DML_RE = re.compile(
    r'\b(?P<operation>INSERT\s+INTO|UPDATE|DELETE\s+FROM)\s+(?:ONLY\s+)?'
    r'(?:(?:"?[A-Za-z_][A-Za-z0-9_]*"?)\.)?"?(?P<table>[A-Za-z_][A-Za-z0-9_]*)"?',
    re.I,
)


@dataclass(frozen=True)
class Migration:
    path: Path
    rel_path: str
    version_text: str
    version: tuple[int, int, int]


def parse_version(text: str) -> tuple[int, int, int]:
    return tuple(int(part) for part in text.split("_"))  # type: ignore[return-value]


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
        migrations.append(Migration(path, rel, version_text, parse_version(version_text)))
    return migrations, errors


def validate_canonical_baseline(migrations: list[Migration]) -> list[str]:
    errors: list[str] = []
    names = {migration.path.name for migration in migrations}
    missing = sorted(CANONICAL_BASELINE - names)
    if missing:
        errors.append("canonical baseline files missing: " + ", ".join(missing))
    for migration in migrations:
        sql = migration.path.read_text(encoding="utf-8")
        if migration.version <= BASELINE_MAX_VERSION:
            if migration.path.name not in CANONICAL_BASELINE:
                errors.append(f"{migration.rel_path}: unexpected migration inside canonical baseline range")
            if EVOLUTION_RE.search(sql):
                errors.append(f"{migration.rel_path}: canonical baseline must not ALTER/RENAME/DROP schema")
            if migration.version < BASELINE_MAX_VERSION and DML_RE.search(sql):
                errors.append(f"{migration.rel_path}: schema baseline files must not contain DML")
            if migration.version == BASELINE_MAX_VERSION:
                inserted = {match.group("table").lower() for match in INSERT_TABLE_RE.finditer(sql)}
                forbidden = inserted - BASELINE_DATA_TABLES
                if forbidden:
                    errors.append(f"{migration.rel_path}: baseline data inserts forbidden tables: {sorted(forbidden)}")
        if migration.version > BASELINE_MAX_VERSION:
            for match in PRIVATE_DML_RE.finditer(sql):
                table = match.group("table").lower()
                operation = " ".join(match.group("operation").upper().split())
                allowed_seed_tables = CONTROLLED_SYSTEM_SEED_MIGRATIONS.get(migration.path.name, set())
                if operation == "INSERT INTO" and table in allowed_seed_tables:
                    continue
                if table in PRIVATE_BUSINESS_TABLES:
                    errors.append(f"{migration.rel_path}: {operation} on private table {table} is forbidden")
    return errors


def validate_baseline_checksums(
    migrations: list[Migration], checksum_file: Path,
    expected_manifest_digest: str = BASELINE_CHECKSUM_MANIFEST_SHA256,
) -> list[str]:
    if not checksum_file.is_file():
        return [f"baseline checksum manifest missing: {checksum_file}"]
    manifest_bytes = checksum_file.read_bytes()
    if hashlib.sha256(manifest_bytes).hexdigest() != expected_manifest_digest:
        return ["canonical baseline checksum manifest digest mismatch"]
    expected: dict[str, str] = {}
    for raw_line in manifest_bytes.decode("utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != 2 or not re.fullmatch(r"[a-f0-9]{64}", parts[0]):
            return [f"invalid baseline checksum entry: {raw_line}"]
        expected[parts[1]] = parts[0]
    errors: list[str] = []
    if set(expected) != CANONICAL_BASELINE:
        errors.append("baseline checksum manifest must list exactly the canonical baseline files")
        return errors
    by_name = {migration.path.name: migration for migration in migrations}
    for name in sorted(CANONICAL_BASELINE):
        migration = by_name.get(name)
        if migration is None:
            continue
        actual = hashlib.sha256(migration.path.read_bytes()).hexdigest()
        if actual != expected[name]:
            errors.append(f"{migration.rel_path}: canonical baseline checksum mismatch")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--migration-dir", default=DEFAULT_MIGRATION_DIR)
    parser.add_argument("--checksum-file", default=BASELINE_CHECKSUM_FILE)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    directory = root / args.migration_dir
    if not directory.is_dir():
        print(f"[flyway] FAIL: migration directory missing: {directory}", file=sys.stderr)
        return 1

    migrations, errors = collect(root, directory)
    errors.extend(validate_canonical_baseline(migrations))
    errors.extend(validate_baseline_checksums(migrations, root / args.checksum_file))

    if errors:
        print("[flyway] FAIL", file=sys.stderr)
        for error in errors:
            print(f"[flyway] - {error}", file=sys.stderr)
        return 1
    print(
        f"[flyway] OK: {len(migrations)} migrations; canonical baseline and checksums validated; "
        "migration naming and private-data policy enforced"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
