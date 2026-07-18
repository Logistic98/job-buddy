from __future__ import annotations

from pathlib import Path


def is_within_workspace(path: Path, workspace: Path) -> bool:
    """Return whether a resolved path is inside the resolved workspace."""
    try:
        path.resolve().relative_to(workspace.resolve())
        return True
    except (OSError, ValueError):
        return False


def resolve_workspace_path(raw_path: str, workspace_dir: str) -> Path:
    workspace = Path(workspace_dir).expanduser().resolve()
    path = Path(raw_path).expanduser()
    if not path.is_absolute():
        path = workspace / path
    resolved = path.resolve()
    if not is_within_workspace(resolved, workspace):
        raise ValueError(f"文件路径超出工作区: {resolved}")
    return resolved
