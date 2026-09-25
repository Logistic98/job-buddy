from pathlib import Path

import pytest
import yaml

from app.core.capability.models import CapabilityCard
from app.core.capability.registry import CapabilityRegistry
from app.core.prompt.loader import PromptTemplateLoader
from app.core.workflow.models import WorkflowDefinition
from app.core.workflow.registry import WorkflowRegistry


def workflow(**changes):
    return {
        "id": "answer",
        "name": "Answer",
        "entry_capability": "general.chat",
        "owner": "runtime",
        "steps": [{"id": "respond", "name": "Respond", "runtime_node": "generate"}],
        **changes,
    }


@pytest.mark.parametrize(
    "changes",
    [
        {"tool_scope": "none", "allowed_tools": ["echo"]},
        {"tool_scope": "unrestricted", "required_tools": ["echo"]},
        {"tool_scope": "allowlist"},
    ],
)
def test_capability_rejects_contradictory_tool_scope(changes):
    with pytest.raises(ValueError, match="tool_scope"):
        CapabilityCard(id="chat", name="Chat", intent="chat", **changes)


@pytest.mark.parametrize("changes", [{"id": " "}, {"entry_capability": " "}, {"steps": []}])
def test_workflow_requires_identity_entry_and_steps(changes):
    with pytest.raises(ValueError, match="不能为空"):
        WorkflowDefinition.model_validate(workflow(**changes))


def test_profiles_skip_invalid_files_and_keep_default_lookup(tmp_path):
    (tmp_path / "broken.yaml").write_text("id: [", encoding="utf-8")
    registry = CapabilityRegistry(str(tmp_path))
    assert [profile.id for profile in registry.list_profiles()] == ["default"]
    assert registry.get_profile("absent").id == "default"
    assert registry.find_capability("default", "general.chat").intent == "chat"
    assert registry.find_capability(None, intent="chat").id == "general.chat"
    assert registry.find_capability(None, "missing", "missing") is None
    assert registry.find_capability(None) is None


@pytest.mark.parametrize("registry_type", [CapabilityRegistry, WorkflowRegistry])
def test_config_paths_resolve_relative_to_module_from_other_directory(tmp_path, monkeypatch, registry_type):
    monkeypatch.chdir(tmp_path)
    registry = registry_type()
    directory = registry.profiles_dir if registry_type is CapabilityRegistry else registry.workflows_dir
    assert directory.is_absolute()
    assert directory.exists()
    missing = registry_type("nonexistent")
    assert not Path("nonexistent").exists()
    if registry_type is WorkflowRegistry:
        assert missing.match("general.chat") is None


@pytest.mark.parametrize(
    ("second", "message"),
    [(workflow(), "id 重复"), (workflow(id="another"), "entry_capability 重复")],
)
def test_workflow_duplicate_reload_preserves_previous_index(tmp_path, second, message):
    (tmp_path / "one.yaml").write_text(yaml.safe_dump(workflow()), encoding="utf-8")
    profiles = CapabilityRegistry(str(tmp_path / "profiles"))
    registry = WorkflowRegistry(str(tmp_path), profiles)
    previous = registry.get("answer")
    assert previous is not None
    assert registry.match(None) is None
    (tmp_path / "two.yaml").write_text(yaml.safe_dump(second), encoding="utf-8")
    with pytest.raises(ValueError, match=message):
        registry.reload()
    assert registry.get("answer") is previous
    assert registry.match("general.chat", "unknown") is previous


@pytest.mark.parametrize(
    ("data", "message"),
    [("[invalid", "配置校验失败"), (yaml.safe_dump(workflow(entry_capability="missing")), "entry_capability 不存在")],
)
def test_workflow_invalid_configuration_is_not_silently_accepted(tmp_path, data, message):
    (tmp_path / "invalid.yaml").write_text(data, encoding="utf-8")
    with pytest.raises(ValueError, match=message):
        WorkflowRegistry(str(tmp_path), CapabilityRegistry(str(tmp_path / "profiles")))


def test_prompt_fallback_rendering_and_file_cache(tmp_path):
    loader = PromptTemplateLoader(str(tmp_path))
    assert loader.load("missing.md", " fallback ") == "fallback"
    (tmp_path / "prompt.md").write_text("Hello {{ user.name }} {{ missing }}", encoding="utf-8")
    prompt = loader.load("prompt.md")
    assert loader.render(prompt, {"user.name": "User"}) == "Hello User "
    assert loader.render("{{ count }}", {"count": 0}) == "0"
    (tmp_path / "prompt.md").write_text("changed", encoding="utf-8")
    assert loader.load("prompt.md") == prompt
