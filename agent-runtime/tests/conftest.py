
import sys
from pathlib import Path
from uuid import uuid4

import pytest

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))


@pytest.fixture
def workspace(tmp_path, monkeypatch):
    from app.core.common.settings import settings

    monkeypatch.setattr(settings.config.runtime, "workspace_dir", str(tmp_path))
    monkeypatch.setattr(settings.config.runtime, "result_storage_dir", str(tmp_path / ".runtime_results"))
    return tmp_path


@pytest.fixture(autouse=True)
def trace_persist_dir(tmp_path, monkeypatch):
    from app.core.common.settings import settings

    target = tmp_path / ".runtime_traces"
    monkeypatch.setattr(settings.config.observability, "persist_dir", str(target))
    return target


@pytest.fixture
def checkpoint_dir(tmp_path, monkeypatch):
    from app.core.common.settings import settings

    target = tmp_path / ".runtime_checkpoints"
    target.mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(settings.config.checkpoint, "dir", str(target))
    return target


@pytest.fixture
def tool_context(workspace):
    from app.core.tool.base import ToolExecutionContext

    return ToolExecutionContext(
        run_id=f"run_{uuid4().hex[:12]}",
        trace_id=f"trace_{uuid4().hex[:12]}",
        session_id=f"session_{uuid4().hex[:12]}",
        workspace_dir=str(workspace),
    )


@pytest.fixture
def fresh_registry():
    from app.core.tool.registry import ToolRegistry
    from app.tools_builtin import register_builtin_tools

    registry = ToolRegistry()
    register_builtin_tools(registry)
    return registry
