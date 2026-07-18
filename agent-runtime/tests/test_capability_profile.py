from pathlib import Path

from app.core.capability.registry import CapabilityRegistry


def test_job_recommend_references_registered_boss_browser_tool():
    profiles_dir = Path(__file__).resolve().parents[1] / "config" / "profiles"
    registry = CapabilityRegistry(str(profiles_dir))

    capability = registry.find_capability("job-buddy", capability_id="job.recommend")

    assert capability is not None
    assert capability.required_tools == ["boss_browser"]
