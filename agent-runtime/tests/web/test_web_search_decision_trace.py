import pytest

from app.core.agent.executor import AgentExecutor
from app.core.common.constants import RuntimeStatus
from app.models.schemas import AgentRunRequest, ChatMessage, TaskUnderstandingResult


def test_upstream_trace_payloads_preserve_web_search_decision():
    executor = AgentExecutor(use_llm=False)
    decision = {
        "mode": "prohibited",
        "trigger": "user",
        "reason": "用户明确禁止联网搜索",
        "signals": ["explicit_opt_out"],
    }
    directive = {
        "domain": "open_domain",
        "intent": "technical_qa",
        "next_action": "run_runtime_planner",
        "task": {
            "metadata": {"web_search_decision": decision},
            "routing": {"execution_mode": "OPEN_DOMAIN_QA"},
        },
    }

    task_payload = executor._upstream_task_trace_payload(directive)
    route_payload = executor._upstream_route_trace_payload(directive)

    assert task_payload["web_search_decision"] == decision
    assert route_payload["web_search_decision"] == decision


@pytest.mark.asyncio
async def test_required_web_search_takes_priority_over_directive_answer():
    executor = AgentExecutor(use_llm=False)
    task = TaskUnderstandingResult(
        original_query="查找 OpenAI 最新模型",
        metadata={
            "capability_contract": {
                "required_tools": ["web_search"],
                "allowed_tools": ["web_search"],
            }
        },
    )
    request = AgentRunRequest(messages=[ChatMessage(role="user", content=task.original_query)])
    graph_calls = []

    async def execute_required_tools(*args, **kwargs):
        graph_calls.append((args, kwargs))
        return {
            "status": RuntimeStatus.FAIL.value,
            "stop_reason": "tool_execution_failed",
            "answer": "联网搜索未成功，无法给出已验证答案。",
            "task_understanding": task,
            "directive": {"answer": "未经联网验证的答案"},
            "tool_results": [],
        }

    executor._execute_required_tools = execute_required_tools

    prepared = await executor._prepare_task_stream(
        request,
        task,
        {"answer": "未经联网验证的答案"},
        "session-id",
        "run-id",
        "trace-id",
    )

    assert len(graph_calls) == 1
    assert prepared["short_answer"] == "联网搜索未成功，无法给出已验证答案。"
    assert prepared["status"] == RuntimeStatus.FAIL.value
