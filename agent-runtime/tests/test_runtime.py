import pytest

from app.core.agent.executor import AgentExecutor
from app.core.common.constants import RuntimeStatus
from app.models.schemas import AgentRunRequest, ChatMessage


@pytest.mark.asyncio
async def test_agent_runtime_echo():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="hello runtime")])
    response = await executor.execute(request)
    assert response.status in {RuntimeStatus.SUCCESS, RuntimeStatus.PAUSED}
    assert response.tool_results


def test_direct_synthesis_uses_upstream_rewritten_query():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="现在这个6年的简历呢")],
        metadata={
            "upstream_directive": {
                "task": {
                    "rewritten_query": {
                        "resolved_query": "使用当前6年经验简历评估上一轮岗位",
                        "planner_query": "读取当前简历并重新评估上一轮选中的美团AI大模型应用工程师岗位",
                    }
                }
            }
        },
    )

    messages = executor._build_synthesis_messages_direct(request)
    content = messages[-1].content

    assert "用户原始问题：\n现在这个6年的简历呢" in content
    assert "已解析的独立任务：\n读取当前简历并重新评估上一轮选中的美团AI大模型应用工程师岗位" in content


def test_direct_synthesis_includes_governed_attachment_content():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="总结我上传的文件")],
        metadata={
            "runtime_execute": True,
            "attachments": [
                {
                    "attachmentId": "att-proof",
                    "fileName": "project.md",
                    "contentType": "text/markdown",
                    "characterCount": 43,
                    "truncated": False,
                    "content": "ATTACHMENT_SENTINEL_42：系统使用 Java 与 Python 协同开发。",
                }
            ],
        },
    )

    messages = executor._build_synthesis_messages_direct(request)
    content = messages[-1].content

    assert "ATTACHMENT_SENTINEL_42" in content
    assert '"file_name": "project.md"' in content
    assert "必须在答案中实际使用其内容" in content
