from app.core.intent.task_understanding import TaskUnderstandingService
from app.models.schemas import AgentRunRequest, ChatMessage


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
