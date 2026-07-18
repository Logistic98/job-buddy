import pytest

from app.core.common.constants import StopReason
from app.core.planner.planner import RuntimePlanner
from app.core.tool.registry import ToolRegistry
from app.models.schemas import ToolDefinition
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


def test_fallback_matches_arbitrary_tool_from_self_describing_metadata():
    planner = RuntimePlanner(llm_client=None)
    tools = [
        ToolDefinition(
            name="generic_reader",
            description="读取通用资源",
            search_hint="读取资源",
            read_only=True,
        ),
        ToolDefinition(
            name="payload_mirror",
            aliases=["repeat_content"],
            search_hint="复述内容 原样返回",
            tags=["utility"],
            description="将输入内容原样返回给调用方",
            input_schema={
                "type": "object",
                "properties": {"text": {"type": "string"}},
                "required": ["text"],
            },
            read_only=True,
        ),
    ]

    plan, call = planner._fallback_plan("请原样返回这段内容", [], tools, None)

    assert planner._is_deterministic_tool_request("请原样返回这段内容", tools) is True
    assert call is not None
    assert call.name == "payload_mirror"
    assert call.arguments == {"text": "请原样返回这段内容"}
    assert plan.steps[0].tool_name == "payload_mirror"


def test_deterministic_match_ignores_single_weak_description_overlap():
    planner = RuntimePlanner(llm_client=None)
    tools = [ToolDefinition(name="generic_processor", description="处理内容", read_only=True)]

    assert planner._is_deterministic_tool_request("内容", tools) is False


def test_fallback_preserves_tool_search_order_when_metadata_does_not_match():
    planner = RuntimePlanner(llm_client=None)
    tools = [
        ToolDefinition(name="first_readonly", description="甲类能力", read_only=True),
        ToolDefinition(name="second_readonly", description="乙类能力", read_only=True),
    ]

    selected = planner._select_fallback_tool("完全无关目标", tools)

    assert selected is tools[0]


def test_fallback_never_selects_writable_or_destructive_tool():
    planner = RuntimePlanner(llm_client=None)
    tools = [
        ToolDefinition(name="writer", description="写入内容", read_only=False),
        ToolDefinition(name="destroyer", description="删除内容", read_only=False, destructive=True),
    ]

    plan, call = planner._fallback_plan("写入内容", [], tools, None)

    assert call is None
    assert plan.is_complete is True
    assert plan.stop_reason == StopReason.TOOL_UNAVAILABLE.value


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
            {"id": "first", "goal": "第一步", "tool_name": "echo", "tool_arguments": {"text": "hi"}},
            {"id": "second", "goal": "第二步", "depends_on": [0, 1], "tool_arguments": ["bad"]},
        ],
        "tool_calls": [{"name": "echo", "arguments": "not-a-dict"}],
    }
    plan, calls = planner._build_plan_and_calls("目标", data, tool_defs)
    assert [step.id for step in plan.steps] == ["first", "second"]
    assert plan.steps[1].depends_on == ["first", "second"]
    assert plan.steps[1].tool_arguments == {}
    assert all(isinstance(c.arguments, dict) for c in calls)
    assert calls[0].plan_step_id == "first"


def test_build_plan_assigns_deterministic_ids_and_normalizes_numeric_dependencies(tool_defs):
    planner = RuntimePlanner(llm_client=None)
    plan, calls = planner._build_plan_and_calls(
        "目标",
        {
            "plan_steps": [
                {"goal": "第一步", "tool_name": "echo", "tool_arguments": {"text": "one"}},
                {"goal": "第二步", "tool_name": "echo", "tool_arguments": {"text": "two"}, "depends_on": [0]},
            ]
        },
        tool_defs,
    )

    assert [step.id for step in plan.steps] == ["step_1", "step_2"]
    assert plan.steps[1].depends_on == ["step_1"]
    assert [call.plan_step_id for call in calls] == ["step_1", "step_2"]


def test_build_plan_keeps_out_of_range_numeric_dependency_for_graph_validation(tool_defs):
    planner = RuntimePlanner(llm_client=None)
    plan, _ = planner._build_plan_and_calls(
        "目标",
        {"plan_steps": [{"goal": "唯一步骤", "depends_on": [9]}]},
        tool_defs,
    )

    assert plan.steps[0].id == "step_1"
    assert plan.steps[0].depends_on == ["9"]


def test_build_plan_keeps_same_tool_call_for_distinct_dependency_steps(tool_defs):
    planner = RuntimePlanner(llm_client=None)
    plan, calls = planner._build_plan_and_calls(
        "目标",
        {
            "plan_steps": [
                {"id": "root", "goal": "第一步", "tool_name": "echo", "tool_arguments": {"text": "same"}},
                {
                    "id": "child",
                    "goal": "第二步",
                    "tool_name": "echo",
                    "tool_arguments": {"text": "same"},
                    "depends_on": "root",
                },
            ],
            "tool_calls": [{"name": "echo", "arguments": {"text": "same"}}],
        },
        tool_defs,
    )

    assert plan.steps[1].depends_on == ["root"]
    assert [call.plan_step_id for call in calls] == ["root", "child"]


@pytest.mark.asyncio
async def test_fallback_does_not_mark_failed_observation_complete(tool_defs):
    planner = RuntimePlanner(llm_client=None)
    plan, call = await planner.create_or_update_plan(
        objective="请回显 hello",
        messages=[],
        observations=["工具 echo 执行失败：temporary error"],
        tools=tool_defs,
    )

    assert plan.is_complete is False
    assert call is not None
    assert call.name == "echo"


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
