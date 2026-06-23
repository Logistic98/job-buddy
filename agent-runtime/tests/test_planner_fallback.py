
import pytest

from app.core.common.constants import StopReason
from app.core.planner.planner import RuntimePlanner
from app.core.tool.registry import ToolRegistry
from app.tools_builtin import register_builtin_tools


@pytest.fixture
def tool_defs():
    registry = ToolRegistry()
    register_builtin_tools(registry)
    return registry.list_definitions()


@pytest.mark.asyncio
async def test_fallback_picks_echo_for_echo_keyword(tool_defs):
    planner = RuntimePlanner(llm_client=None)
    plan, call = await planner.create_or_update_plan(
        objective="请回显 hello",
        messages=[],
        observations=[],
        tools=tool_defs,
    )
    assert call is not None
    assert call.name == "echo"
    assert call.arguments.get("text") == "请回显 hello"
    assert not plan.is_complete


@pytest.mark.asyncio
async def test_fallback_completes_when_observations_present(tool_defs):
    planner = RuntimePlanner(llm_client=None)
    plan, call = await planner.create_or_update_plan(
        objective="读取文件",
        messages=[],
        observations=["第一次工具结果：内容是 abc"],
        tools=tool_defs,
    )
    assert plan.is_complete
    assert plan.stop_reason == StopReason.TASK_COMPLETE.value
    assert call is None
    assert "abc" in (plan.final_answer or "")


def test_build_plan_coerces_non_string_depends_on_and_arguments(tool_defs):
    planner = RuntimePlanner(llm_client=None)
    data = {
        "plan_steps": [
            {"goal": "第一步", "tool_name": "echo", "tool_arguments": {"text": "hi"}},
            {"goal": "第二步", "depends_on": [0, 1], "tool_arguments": ["bad"]},
        ],
        "tool_calls": [{"name": "echo", "arguments": "not-a-dict"}],
    }
    plan, calls = planner._build_plan_and_calls("目标", data, tool_defs)
    assert plan.steps[1].depends_on == ["0", "1"]
    assert plan.steps[1].tool_arguments == {}
    assert all(isinstance(c.arguments, dict) for c in calls)


@pytest.mark.asyncio
async def test_fallback_when_no_tools_available():
    planner = RuntimePlanner(llm_client=None)
    plan, call = await planner.create_or_update_plan(
        objective="任意目标",
        messages=[],
        observations=[],
        tools=[],
    )
    assert plan.is_complete
    assert call is None
    assert plan.stop_reason == StopReason.TOOL_UNAVAILABLE.value
