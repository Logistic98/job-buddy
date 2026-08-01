import json

import pytest

from app.core.intent.task_understanding import TaskUnderstandingService
from app.models.schemas import AgentRunRequest, ChatMessage


class _CapturingIntentLLM:
    async def chat(self, messages, **kwargs):
        self.messages = messages
        return {
            "content": json.dumps(
                {
                    "resolved_query": "解释缓存击穿",
                    "retrieval_query": "缓存击穿",
                    "planner_query": "解释缓存击穿",
                    "context_dependency": "none",
                    "context_type": [],
                    "selected_capability_id": "open_domain.technical_qa",
                    "confidence": 0.95,
                    "secondary": [],
                    "slots": {},
                    "missing_required": [],
                    "needs_clarification": False,
                    "clarification_question": None,
                    "risk_level": "low",
                    "answer": None,
                    "reason": "技术问答",
                },
                ensure_ascii=False,
            )
        }


def test_recent_messages_omit_current_duplicate_and_empty_optional_fields():
    service = TaskUnderstandingService(llm_client=None)
    request = AgentRunRequest(
        messages=[ChatMessage(role="assistant", content=f"history-{index}") for index in range(9)]
        + [ChatMessage(role="user", content="current question")],
        metadata={"profile": "job-buddy"},
    )

    recent = service._recent_messages_for_prompt(request, "current question")

    assert len(recent) == 7
    assert [item["content"] for item in recent] == [
        "history-2",
        "history-3",
        "history-4",
        "history-5",
        "history-6",
        "history-7",
        "history-8",
    ]
    assert all(set(item) == {"role", "content"} for item in recent)


@pytest.mark.asyncio
async def test_capability_catalog_prompt_is_compact_and_keeps_routing_semantics():
    llm = _CapturingIntentLLM()
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="解释缓存击穿")],
        metadata={"profile": "job-buddy"},
    )

    await service.understand(request, "session-1", "run-1", "trace-1")

    catalog_json = llm.messages[1].content.split("\n", 1)[1]
    catalog = json.loads(catalog_json)
    allowed_fields = {
        "id",
        "domain",
        "intent",
        "description",
        "examples",
        "negative_examples",
        "required_slots",
        "optional_slots",
        "risk",
        "allowed_tools",
    }
    assert catalog_json == json.dumps(catalog, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    assert len(catalog_json) <= 4500
    assert all(set(item).issubset(allowed_fields) for item in catalog)
    assert all({"id", "domain", "intent", "description", "risk"}.issubset(item) for item in catalog)
    assert any(item.get("examples") for item in catalog)
    assert any(item.get("negative_examples") for item in catalog)
    technical_qa = next(item for item in catalog if item["id"] == "open_domain.technical_qa")
    assert "web_search" in technical_qa["allowed_tools"]
