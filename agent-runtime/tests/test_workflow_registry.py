from pathlib import Path

import pytest
from pydantic import ValidationError

from app.core.agent.executor import AgentExecutor
from app.core.capability.registry import CapabilityRegistry
from app.core.workflow.models import WorkflowDefinition
from app.core.workflow.registry import WorkflowRegistry
from app.models.schemas import AgentRunRequest, ChatMessage

RUNTIME_ROOT = Path(__file__).resolve().parent.parent


def test_workflow_registry_loads_and_matches_entry_capability():
    registry = WorkflowRegistry(str(RUNTIME_ROOT / "config" / "workflows"))

    workflow = registry.match("job.recommend", "job-buddy")

    assert workflow is not None
    assert workflow.id == "job_recommendation"
    assert workflow.model_config.get("frozen") is True
    assert any(step.external_action == "call_get_recommend_jobs" for step in workflow.steps)


def test_workflow_model_rejects_ambiguous_step_kind():
    with pytest.raises(ValidationError):
        WorkflowDefinition.model_validate(
            {
                "id": "invalid",
                "name": "invalid",
                "entry_capability": "sample.run",
                "owner": "agent-runtime",
                "steps": [
                    {
                        "id": "step",
                        "name": "step",
                        "runtime_node": "plan",
                        "external_action": "must_not_execute",
                    }
                ],
            }
        )


def test_workflow_registry_fails_fast_on_invalid_yaml(tmp_path):
    (tmp_path / "broken.yaml").write_text(
        """
id: broken
name: Broken
entry_capability: sample.run
owner: agent-runtime
steps:
  - id: duplicate
    name: one
    runtime_node: plan
  - id: duplicate
    name: two
    external_action: call_external
""".strip(),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="Workflow 配置校验失败"):
        WorkflowRegistry(str(tmp_path))


@pytest.mark.parametrize(
    ("profile", "entry_capability", "error"),
    [
        ("missing-profile", "job.recommend", "profile 不存在"),
        ("job-buddy", "missing.capability", "不存在 entry_capability"),
    ],
)
def test_workflow_registry_cross_validates_profile_and_capability(tmp_path, profile, entry_capability, error):
    (tmp_path / "cross-invalid.yaml").write_text(
        f"""
id: cross_invalid
name: Cross invalid
profile: {profile}
entry_capability: {entry_capability}
owner: agent-runtime
steps:
  - id: understand
    name: understand
    runtime_node: task_understanding
""".strip(),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match=error):
        WorkflowRegistry(
            str(tmp_path),
            capability_registry=CapabilityRegistry(str(RUNTIME_ROOT / "config" / "profiles")),
        )


@pytest.mark.asyncio
async def test_workflow_metadata_reaches_task_directive_and_trace_without_external_execution():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="帮我找上海 Java 后端岗位")],
        metadata={"profile": "job-buddy", "entrypoint": "chat.ask"},
    )

    response = await executor.execute(request)

    workflow = response.task_understanding.metadata.get("workflow")
    assert workflow["id"] == "job_recommendation"
    assert response.directive["workflow"]["entry_capability"] == "job.recommend"
    real_tool_results = [item for item in response.tool_results if not item.metadata.get("synthetic")]
    assert real_tool_results == []
    route_event = next(event for event in response.trace_events if event.event == "capability_route")
    assert route_event.payload["workflow"]["id"] == "job_recommendation"


@pytest.mark.asyncio
async def test_stream_returns_external_workflow_metadata_without_running_required_tool():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="帮我找上海 Java 后端岗位")],
        metadata={"profile": "job-buddy", "entrypoint": "chat.ask"},
    )

    events = [event async for event in executor.execute_stream(request)]
    done = next(event["data"] for event in events if event["event"] == "done")

    assert done["directive"]["workflow"]["id"] == "job_recommendation"
    assert done["task_understanding"]["metadata"]["workflow"]["entry_capability"] == "job.recommend"
    assert done["tool_results"] == []
    route_event = next(event for event in done["trace_events"] if event["event"] == "capability_route")
    assert route_event["payload"]["workflow"]["id"] == "job_recommendation"
