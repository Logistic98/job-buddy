
import pytest

from app.core.agent.executor import AgentExecutor
from app.core.common.constants import RuntimeStatus
from app.models.schemas import AgentRunRequest, ChatMessage


@pytest.mark.asyncio
async def test_job_buddy_profile_routes_job_recommendation_in_runtime():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="帮我筛选上海大模型应用开发 40-50K 岗位")],
        metadata={"profile": "job-buddy", "job_buddy": True},
    )

    response = await executor.execute(request)

    assert response.status == RuntimeStatus.SUCCESS
    directive = response.tool_results[0].output
    assert directive["type"] == "job_buddy_directive"
    assert directive["domain"] == "job"
    assert directive["intent"] == "job.recommend"
    assert directive["slots"]["role"] == "大模型应用开发"
    assert directive["slots"]["city"] == "上海"
    assert directive["next_action"] == "call_get_recommend_jobs"
    assert directive["router"] == "semantic_config"
    assert response.task_understanding.routing.selected_capability.capability_id == "job.recommend"


@pytest.mark.asyncio
async def test_job_buddy_profile_routes_more_jobs_with_previous_slots():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="换一批")],
        metadata={
            "profile": "job-buddy",
            "job_buddy": True,
            "previous_slots": {"role": "大模型应用开发", "city": "上海", "boss_page": 1},
        },
    )

    response = await executor.execute(request)

    directive = response.tool_results[0].output
    assert directive["intent"] == "job.recommend"
    assert directive["slots"]["boss_page"] == 2
    assert directive["slots"]["follow_up"] == "next_batch"
    assert directive["router"] == "semantic_config_shortcut"


@pytest.mark.asyncio
async def test_job_buddy_profile_routes_resume_match_without_boss_search():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="分析当前简历是否匹配 Agent 应用开发岗位")],
        metadata={"profile": "job-buddy", "job_buddy": True, "current_jobs_count": 0},
    )

    response = await executor.execute(request)

    directive = response.tool_results[0].output
    assert directive["domain"] == "job"
    assert directive["intent"] == "resume.match"
    assert directive["next_action"] == "run_resume_match"
    assert directive["slots"]["role"] == "大模型应用开发"
    assert directive["task"]["routing"]["execution_mode"] == "STABLE_WORKFLOW"
    assert response.task_understanding.routing.selected_capability.capability_id == "resume.match"


@pytest.mark.asyncio
async def test_job_buddy_profile_routes_code_generation_to_complex_runtime_task():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="帮我写一个 Python 脚本处理 CSV")],
        metadata={"profile": "job-buddy", "job_buddy": True},
    )

    response = await executor.execute(request)

    directive = response.tool_results[0].output
    assert directive["domain"] == "runtime"
    assert directive["intent"] == "code_generation_task"
    assert directive["task"]["routing"]["execution_mode"] == "COMPLEX_AGENT_TASK"
