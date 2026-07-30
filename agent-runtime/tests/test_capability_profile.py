from pathlib import Path

from app.core.capability.registry import CapabilityRegistry


def test_job_recommend_references_registered_boss_browser_tool():
    profiles_dir = Path(__file__).resolve().parents[1] / "config" / "profiles"
    registry = CapabilityRegistry(str(profiles_dir))

    capability = registry.find_capability("job-buddy", capability_id="job.recommend")

    assert capability is not None
    assert capability.required_tools == ["boss_browser"]
    assert capability.tool_scope == "allowlist"


def test_code_generation_capability_uses_explicit_bounded_tool_scope():
    profiles_dir = Path(__file__).resolve().parents[1] / "config" / "profiles"
    registry = CapabilityRegistry(str(profiles_dir))

    capability = registry.find_capability("job-buddy", capability_id="runtime.code_generation_task")

    assert capability is not None
    assert capability.tool_scope == "allowlist"
    assert capability.required_tools == ["sandbox_code_execute"]
    assert set(capability.allowed_tools) == {
        "sandbox_code_execute",
        "file_read",
        "file_write",
        "file_edit",
        "glob",
        "grep",
        "shell_exec",
        "web_search",
        "web_fetch",
    }
