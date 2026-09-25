from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.core.common.settings import settings
from app.core.planner.planner import RuntimePlanner
from app.models.schemas import TaskUnderstandingResult, ToolCall, ToolDefinition


@pytest.mark.asyncio
async def test_planner_clarification_precedes_model_and_tool_execution():
    client = SimpleNamespace(chat=AsyncMock())
    task = TaskUnderstandingResult(original_query="goal", clarification={"needed": True, "question": "Which file?"})
    plan, call = await RuntimePlanner(client).create_or_update_plan("goal", [], [], [], task_understanding=task)
    assert plan.need_clarification and plan.clarification_question == "Which file?"
    assert call is None
    client.chat.assert_not_awaited()


@pytest.mark.asyncio
async def test_planner_llm_failure_has_actionable_complex_task_fallback(monkeypatch):
    monkeypatch.setattr(settings.config.llm_service, "prompt_cache_enabled", False)
    client = SimpleNamespace(chat=AsyncMock(side_effect=TimeoutError("model timed out")))
    task = TaskUnderstandingResult(
        original_query="goal", profile="custom", planner_constraints={"planner_needed": True}
    )
    plan, call = await RuntimePlanner(client).create_or_update_plan("goal", [], [], [], task_understanding=task)
    assert call is None and plan.stop_reason == "tool_unavailable"
    assert "model timed out" in plan.final_answer
    assert "候选工具" in client.chat.call_args.args[0][-1].content
    offline, _ = await RuntimePlanner().create_or_update_plan("goal", [], [], [], task_understanding=task)
    assert "未配置" in offline.final_answer


@pytest.mark.asyncio
async def test_planner_completes_existing_answer_or_reports_missing_capability():
    task = TaskUnderstandingResult(original_query="goal", answer="verified")
    plan, call = await RuntimePlanner().create_or_update_plan("goal", [], [], [], task_understanding=task)
    assert plan.final_answer == "verified" and call is None
    task.answer = None
    task.planner_constraints.planner_needed = True
    plan, call = await RuntimePlanner().create_or_update_plan("goal", [], [], [], task_understanding=task)
    assert "缺少可用工具或模型能力" in plan.final_answer and call is None


@pytest.mark.parametrize(
    ("objective", "language"),
    [("write Java", "java"), ("write bash", "shell"), ("write Python", "python"), ("write Node.js", "javascript")],
)
def test_sandbox_plan_infers_explicit_requested_language(objective, language):
    args = RuntimePlanner()._normalize_tool_arguments("sandbox_code_execute", {"code": "sample"}, objective)
    assert args == {"code": "sample", "language": language}


def test_plan_rejects_unknown_tools_normalizes_arguments_and_clarification():
    tool = ToolDefinition(name="echo", description="echo", input_schema={})
    plan, calls = RuntimePlanner()._build_plan_and_calls(
        "goal",
        {
            "plan_steps": [{"tool_name": "unknown", "tool_arguments": "invalid"}],
            "tool_calls": [None, {"name": "echo", "arguments": "invalid"}],
            "need_clarification": True,
        },
        [tool],
    )
    assert plan.steps[0].tool_name is None
    assert plan.tool_calls[0].arguments == {}
    assert calls == [] and plan.stop_reason == "need_clarification"


def test_default_arguments_keep_schema_defaults_and_bound_keyword_length():
    planner = RuntimePlanner()
    tool = ToolDefinition(
        name="grep",
        description="",
        input_schema={"properties": {"limit": {"default": 10}}, "required": ["limit", "pattern"]},
    )
    assert planner._build_default_arguments(tool, "x" * 100) == {"limit": 10, "pattern": "x" * 80}
    assert planner._tool_metadata_match_score("", tool) == 0
    task = TaskUnderstandingResult(
        original_query="latest engineering blog",
        rewritten_query={
            "retrieval_query": "official engineering",
            "selection_mode": "latest",
            "as_of_date": "2026-01-01",
        },
    )
    args = planner._build_default_arguments(
        ToolDefinition(name="web_search", description="", input_schema={"required": ["query"]}),
        "latest engineering blog",
        task,
    )
    assert args["query"] == "official engineering"
    assert args["content_scope"] == "engineering_blog"
    assert args["as_of_date"] == "2026-01-01"


def test_dependency_parser_preserves_unknown_references_and_unbound_calls_are_deduplicated():
    planner = RuntimePlanner()
    assert planner._dependency_numeric_base(["0"]) == 0
    assert planner._normalize_step_reference("missing", ["first"]) == "missing"
    assert (
        planner._match_prior_tool_dependency(
            "echo success", step_ids=["first", "second"], raw_steps=[{}, {"tool_name": "echo"}], before_index=2
        )
        == "second"
    )
    calls = [
        ToolCall(id=str(index), name="echo", arguments={"text": "same"}, plan_step_id="first") for index in range(2)
    ]
    assert planner._dedupe_calls(calls) == calls[:1]
    assert planner._parse_json('```json\n{"is_complete": true}\n```') == {"is_complete": True}
    assert not planner._observations_are_sufficient(None, [])


@pytest.mark.asyncio
async def test_deterministic_tool_request_avoids_model_and_reuses_sufficient_evidence():
    from app.tools_builtin.echo_tool import EchoTool

    client = SimpleNamespace(chat=AsyncMock())
    planner = RuntimePlanner(client)
    plan, call = await planner.create_or_update_plan("echo hello", [], [], [EchoTool().definition()])
    assert call.name == "echo"
    client.chat.assert_not_awaited()
    complete, call = planner._fallback_plan("goal", ["工具 echo 执行成功：hello"], [], None)
    assert complete.is_complete and call is None
    assert planner._web_search_selection_arguments("ordinary question", None) == {}
    tool = EchoTool().definition()
    tool.aliases = ["echo", "echo"]
    duplicate_score = planner._tool_metadata_match_score("echo", tool)
    tool.aliases = ["echo"]
    assert planner._tool_metadata_match_score("echo", tool) == duplicate_score
