import pytest

from app.core.capability.registry import CapabilityRegistry
from app.core.intent.task_understanding import TaskUnderstandingService
from app.models.schemas import AgentRunRequest, ChatMessage


class FakeIntentLLM:
    def __init__(self, payload):
        self.payload = payload
        self.calls = 0

    async def chat(self, messages, tools=None, temperature=None, max_tokens=None, **kwargs):
        self.last_messages = messages
        self.last_kwargs = kwargs
        self.calls += 1
        import json

        return {"content": json.dumps(self.payload, ensure_ascii=False)}


@pytest.mark.asyncio
async def test_profile_capability_cards_are_loaded_from_yaml():
    registry = CapabilityRegistry()
    profile = registry.get_profile("job-buddy")

    assert profile.directive_type == "job_buddy_directive"
    assert profile.default_capability_id == "open_domain.general_qa"
    assert profile.capability_by_id("job.recommend") is not None
    assert profile.capability_by_id("runtime.code_generation_task").next_action == "run_runtime_planner"


@pytest.mark.asyncio
async def test_task_understanding_routes_code_task_to_runtime_planner_without_job_search():
    service = TaskUnderstandingService(llm_client=None, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="帮我写一个 Python 脚本处理 CSV")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.intent.domain == "runtime"
    assert result.intent.intent == "code_generation_task"
    assert result.next_action == "run_runtime_planner"
    assert result.planner_constraints.planner_needed is True


@pytest.mark.asyncio
async def test_task_understanding_open_domain_does_not_force_job_context():
    service = TaskUnderstandingService(llm_client=None, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="解释一下现金流折现法")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.intent.domain == "open_domain"
    assert result.intent.intent == "general_qa"
    assert result.next_action == "run_runtime_planner"


@pytest.mark.asyncio
async def test_task_understanding_missing_required_slot_blocks_job_search():
    service = TaskUnderstandingService(llm_client=None, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="帮我找上海高薪岗位")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.intent.intent == "job.recommend"
    assert result.clarification.needed is True
    assert "role" in result.slots.missing_required


@pytest.mark.asyncio
async def test_task_understanding_without_llm_does_not_semantic_route_by_default():
    service = TaskUnderstandingService(llm_client=None)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="分析当前简历是否匹配 Agent 应用开发岗位")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.router == "llm_unavailable"
    assert result.next_action == "clarify"
    assert result.intent.intent != "job.recommend"
    assert result.clarification.needed is True


@pytest.mark.asyncio
async def test_task_understanding_uses_llm_result_before_semantic_fallback():
    llm = FakeIntentLLM(
        {
            "resolved_query": "分析当前简历是否匹配 Agent 应用开发岗位",
            "retrieval_query": "简历匹配分析",
            "planner_query": "对当前简历和 Agent 应用开发岗位做匹配分析",
            "context_dependency": "required",
            "context_type": ["resume"],
            "selected_capability_id": "resume.match",
            "confidence": 0.91,
            "secondary": [],
            "slots": {"role": "Agent 应用开发"},
            "missing_required": [],
            "needs_clarification": False,
            "clarification_question": None,
            "risk_level": "low",
            "answer": None,
            "reason": "用户要求做简历与岗位方向匹配分析，不是岗位搜索",
        }
    )
    service = TaskUnderstandingService(llm_client=llm, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="分析当前简历是否匹配 Agent 应用开发岗位")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert llm.calls == 1
    assert llm.last_kwargs.get("disable_thinking") is True
    assert result.router == "llm"
    assert result.intent.intent == "resume.match"
    assert result.next_action == "run_resume_match"
    assert result.slots.filled["role"] == "Agent 应用开发"


@pytest.mark.asyncio
async def test_task_understanding_routes_resume_switch_follow_up_without_llm():
    llm = FakeIntentLLM({"selected_capability_id": "open_domain.general_qa", "confidence": 0.5})
    selected_job = {
        "jobName": "AI大模型应用工程师",
        "company": "美团",
        "description": "负责大模型应用开发、Agent 工程化与业务落地",
    }
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[
            ChatMessage(role="user", content="分析此岗位与当前简历的匹配度"),
            ChatMessage(role="assistant", content="已完成当前岗位与简历的匹配分析。"),
            ChatMessage(role="user", content="现在这个6年的简历呢"),
        ],
        metadata={
            "profile": "job-buddy",
            "resume_id": "resume-6-years",
            "previous_slots": {"_selected_job": selected_job},
        },
    )

    result = await service.understand(request, "s1", "r1", "t1")
    directive = service.build_directive(service.get_profile("job-buddy"), result)

    assert llm.calls == 0
    assert result.router == "semantic_config_shortcut"
    assert result.intent.intent == "resume.match"
    assert result.next_action == "run_resume_match"
    assert result.clarification.needed is False
    assert result.rewritten_query.resolved_query == "使用当前选择的简历重新评估上一轮明确选中的岗位"
    assert result.rewritten_query.planner_query.startswith("读取当前简历，并复用上一轮")
    assert result.context.dependency == "required"
    assert result.context.context_type == ["recent_dialogue", "resume", "current_jobs"]
    assert result.context.resolved_references[0].source == "previous_slots"
    assert result.intent.secondary == ["needs_resume", "target_job_analysis", "resume_switched", "reuse_selected_job"]
    assert result.slots.filled["_selected_job"] == selected_job
    assert result.slots.filled["follow_up"] == "resume_switch_rematch"
    assert result.metadata["reuse_previous_slots"] is True
    assert directive["task"]["metadata"]["reuse_previous_slots"] is True
    assert directive["slots"]["_selected_job"] == selected_job


@pytest.mark.asyncio
async def test_resume_switch_shortcut_requires_selected_job_context():
    llm = FakeIntentLLM(
        {
            "selected_capability_id": "open_domain.general_qa",
            "confidence": 0.6,
            "needs_clarification": True,
            "reason": "缺少可解析的上一轮岗位",
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="现在这个6年的简历呢")],
        metadata={"profile": "job-buddy", "previous_slots": {"role": "Java 后端"}},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert llm.calls == 1
    assert result.router == "llm"
    assert result.intent.intent != "resume.match"


def test_resume_switch_shortcut_does_not_override_explicit_new_target():
    service = TaskUnderstandingService(llm_client=None)
    profile = service.get_profile("job-buddy")
    selected_job = {"_selected_job": {"jobName": "上一轮岗位", "company": "上一家公司"}}

    shortcut = service._match_shortcut(
        profile,
        "现在用这个6年的简历分析另一个上海 Java 大模型应用开发岗",
        selected_job,
    )

    assert shortcut is None


@pytest.mark.asyncio
async def test_conversation_shortcut_skips_llm_call():
    llm = FakeIntentLLM({"selected_capability_id": "open_domain.general_qa", "confidence": 0.5})
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="换一批")],
        metadata={"profile": "job-buddy", "previous_slots": {"role": "Java 后端", "boss_page": 1}},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert llm.calls == 0
    assert result.router == "semantic_config_shortcut"
    assert result.intent.intent == "job.recommend"
    assert result.slots.filled["boss_page"] == 2
    assert result.slots.filled["role"] == "Java 后端"


@pytest.mark.asyncio
async def test_understanding_prompt_truncates_long_recent_messages():
    service = TaskUnderstandingService(llm_client=None)
    long_message = ChatMessage(role="assistant", content="字" * 1000)

    compact = service._compact_message(long_message)

    assert len(compact["content"]) < 1000
    assert compact["content"].endswith("...(truncated)")
    short = service._compact_message(ChatMessage(role="user", content="你好"))
    assert short["content"] == "你好"


@pytest.mark.asyncio
async def test_task_understanding_routes_interview_capability_to_runtime_planner():
    llm = FakeIntentLLM(
        {
            "resolved_query": "围绕我的大模型应用项目生成面试深挖问题",
            "retrieval_query": "面试准备",
            "planner_query": "生成大模型应用项目面试问题",
            "context_dependency": "required",
            "context_type": ["resume"],
            "selected_capability_id": "interview.prepare",
            "confidence": 0.93,
            "secondary": [],
            "slots": {},
            "missing_required": [],
            "needs_clarification": False,
            "clarification_question": None,
            "risk_level": "low",
            "answer": None,
            "reason": "用户请求面试准备",
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="围绕我的大模型应用项目生成面试深挖问题")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")
    directive = service.build_directive(service.get_profile("job-buddy"), result)

    assert result.intent.intent == "interview.prepare"
    assert result.next_action == "run_runtime_planner"
    assert result.planner_constraints.planner_needed is True
    assert result.clarification.needed is False
    assert result.answer is None
    assert directive["capability_contract"]["evidence_requirements"]
    assert directive["capability_contract"]["allowed_tools"] == ["web_search", "web_fetch"]
