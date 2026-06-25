#!/usr/bin/env python3
"""Validate Flyway migration naming, uniqueness, and append-only changes."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


MIGRATION_RE = re.compile(
    r"^V(?P<version>\d+_\d+_\d+)__(?P<description>[A-Za-z][A-Za-z0-9_]*)\.sql$"
)
DEFAULT_MIGRATION_DIR = "agent-backend/src/main/resources/db/migration"


@dataclass(frozen=True)
class Migration:
    rel_path: str
    version_text: str
    version: tuple[int, int, int]


def run_git(repo_root: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=repo_root,
        text=True,
        capture_output=True,
        check=False,
    )


def has_git_ref(repo_root: Path, ref: str) -> bool:
    result = run_git(repo_root, ["rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"])
    return result.returncode == 0


def has_head(repo_root: Path) -> bool:
    return has_git_ref(repo_root, "HEAD")


def merge_base(repo_root: Path, ref: str) -> str:
    result = run_git(repo_root, ["merge-base", "HEAD", ref])
    if result.returncode == 0:
        return result.stdout.strip()
    return ref


def resolve_base_ref(repo_root: Path, explicit_ref: str | None) -> str | None:
    if explicit_ref:
        if not has_git_ref(repo_root, explicit_ref):
            raise ValueError(f"base ref does not exist: {explicit_ref}")
        return merge_base(repo_root, explicit_ref) if has_head(repo_root) else explicit_ref

    env_ref = os.environ.get("FLYWAY_BASE_REF")
    if env_ref:
        if not has_git_ref(repo_root, env_ref):
            raise ValueError(f"FLYWAY_BASE_REF does not exist: {env_ref}")
        return merge_base(repo_root, env_ref) if has_head(repo_root) else env_ref

    for candidate in ("origin/main", "origin/master", "main", "master"):
        if has_git_ref(repo_root, candidate):
            return merge_base(repo_root, candidate) if has_head(repo_root) else candidate

    return "HEAD" if has_head(repo_root) else None


def version_from_text(version_text: str) -> tuple[int, int, int]:
    return tuple(int(part) for part in version_text.split("_"))  # type: ignore[return-value]


def validate_filename(path: Path, repo_root: Path) -> tuple[Migration | None, str | None]:
    rel_path = path.relative_to(repo_root).as_posix()
    match = MIGRATION_RE.match(path.name)
    if not match:
        return (
            None,
            (
                f"{rel_path}: filename must match "
                "V<major>_<minor>_<patch>__<English_description>.sql"
            ),
        )

    version_text = match.group("version")
    for part in version_text.split("_"):
        if len(part) > 1 and part.startswith("0"):
            return None, f"{rel_path}: version segment must not use leading zero: {part}"

    return Migration(rel_path, version_text, version_from_text(version_text)), None


def collect_current_migrations(
    repo_root: Path, migration_dir: Path
) -> tuple[list[Migration], list[str]]:
    errors: list[str] = []
    migrations: list[Migration] = []

    if not migration_dir.is_dir():
        errors.append(f"{migration_dir.relative_to(repo_root).as_posix()}: migration directory missing")
        return migrations, errors

    for path in sorted(migration_dir.glob("*.sql")):
        migration, error = validate_filename(path, repo_root)
        if error:
            errors.append(error)
        elif migration:
            migrations.append(migration)

    seen: dict[str, str] = {}
    for migration in migrations:
        existing = seen.get(migration.version_text)
        if existing:
            errors.append(
                f"duplicate Flyway version V{migration.version_text}: "
                f"{existing} and {migration.rel_path}"
            )
        else:
            seen[migration.version_text] = migration.rel_path

    return migrations, errors


def list_base_migrations(repo_root: Path, migration_rel_dir: str, base_ref: str) -> list[Migration]:
    result = run_git(repo_root, ["ls-tree", "-r", "--name-only", base_ref, "--", migration_rel_dir])
    if result.returncode != 0:
        return []

    migrations: list[Migration] = []
    for rel_path in result.stdout.splitlines():
        name = Path(rel_path).name
        match = MIGRATION_RE.match(name)
        if not match:
            continue
        version_text = match.group("version")
        migrations.append(Migration(rel_path, version_text, version_from_text(version_text)))
    return migrations


def parse_changed_paths(diff_output: str) -> tuple[list[str], list[str]]:
    blocked_changes: list[str] = []
    new_paths: list[str] = []

    for line in diff_output.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        status = parts[0]

        if status.startswith("R"):
            blocked_changes.append(f"renamed migration is not allowed: {parts[1]} -> {parts[2]}")
        elif status.startswith("D"):
            blocked_changes.append(f"deleted migration is not allowed: {parts[1]}")
        elif status.startswith("M") or status.startswith("T"):
            blocked_changes.append(f"modified migration is not allowed: {parts[1]}")
        elif status.startswith("A") or status.startswith("C"):
            new_paths.append(parts[-1])
        elif status.startswith("U"):
            blocked_changes.append(f"unmerged migration is not allowed: {parts[-1]}")

    return blocked_changes, new_paths


def validate_append_only(
    repo_root: Path,
    migration_rel_dir: str,
    base_ref: str | None,
    migrations_by_path: dict[str, Migration],
) -> list[str]:
    if not base_ref:
        return []

    errors: list[str] = []
    base_migrations = list_base_migrations(repo_root, migration_rel_dir, base_ref)
    max_base_version = max((migration.version for migration in base_migrations), default=None)

    diff = run_git(
        repo_root,
        ["diff", "--name-status", "--find-renames", "--find-copies", base_ref, "--", migration_rel_dir],
    )
    if diff.returncode != 0:
        errors.append(diff.stderr.strip() or f"failed to diff Flyway migrations against {base_ref}")
        return errors

    blocked_changes, new_paths = parse_changed_paths(diff.stdout)
    errors.extend(blocked_changes)

    others = run_git(repo_root, ["ls-files", "--others", "--exclude-standard", "--", migration_rel_dir])
    if others.returncode == 0:
        new_paths.extend(path for path in others.stdout.splitlines() if path.endswith(".sql"))

    for rel_path in sorted(set(new_paths)):
        migration = migrations_by_path.get(rel_path)
        if not migration:
            continue
        if max_base_version is not None and migration.version <= max_base_version:
            errors.append(
                f"{rel_path}: new migration version V{migration.version_text} must be greater than "
                f"base max V{'_'.join(str(part) for part in max_base_version)}"
            )

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--migration-dir",
        default=DEFAULT_MIGRATION_DIR,
        help=f"Flyway migration directory, relative to repository root. Default: {DEFAULT_MIGRATION_DIR}",
    )
    parser.add_argument(
        "--base-ref",
        default=None,
        help="Git ref used as append-only baseline. Defaults to FLYWAY_BASE_REF, origin/main, or HEAD.",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    migration_dir = repo_root / args.migration_dir
    migration_rel_dir = migration_dir.relative_to(repo_root).as_posix()

    errors: list[str] = []
    migrations, filename_errors = collect_current_migrations(repo_root, migration_dir)
    errors.extend(filename_errors)

    migrations_by_path = {migration.rel_path: migration for migration in migrations}

    try:
        base_ref = resolve_base_ref(repo_root, args.base_ref)
    except ValueError as exc:
        errors.append(str(exc))
        base_ref = None

    errors.extend(validate_append_only(repo_root, migration_rel_dir, base_ref, migrations_by_path))

    if errors:
        print("[flyway] FAIL", file=sys.stderr)
        for error in errors:
            print(f"[flyway] - {error}", file=sys.stderr)
        return 1

    base_label = base_ref or "none"
    print(
        f"[flyway] OK: {len(migrations)} migration files checked; "
        f"versions unique; append-only baseline={base_label}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
