import json

import pytest

from app.core.capability.models import ProfileDefinition
from app.core.capability.registry import CapabilityRegistry
from app.core.intent.task_understanding import TaskUnderstandingService
from app.models.schemas import AgentRunRequest, ChatMessage, QueryRewrite


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
    recommendation = profile.capability_by_id("job.recommend")

    assert profile.directive_type == "job_buddy_directive"
    assert profile.default_capability_id == "open_domain.general_qa"
    assert recommendation is not None
    assert all("java" not in example.lower() for example in recommendation.examples)
    assert "Java" not in recommendation.clarification_question
    assert "Agent 开发岗位" in recommendation.clarification_question
    role_extractor = next(item for item in profile.slot_extractors if item.name == "role")
    agent_role = next(item for item in role_extractor.values if item["value"] == "大模型应用开发")
    assert "Agent 平台开发" in agent_role["aliases"]
    code_capability = profile.capability_by_id("runtime.code_generation_task")
    assert code_capability.next_action == "run_runtime_planner"
    assert code_capability.required_tools == ["sandbox_code_execute"]
    assert profile.intent_hint_fast_path.enabled is True
    assert profile.intent_hint_fast_path.allowed_capability_ids == ["job.recommend", "resume.match"]


def test_intent_hint_fast_path_is_disabled_by_default():
    profile = ProfileDefinition(id="custom", name="Custom")

    assert profile.intent_hint_fast_path.enabled is False
    assert profile.intent_hint_fast_path.allowed_capability_ids == []


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
async def test_exact_markdown_code_rendering_uses_tool_free_content_formatting():
    service = TaskUnderstandingService(llm_client=None, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[
            ChatMessage(
                role="user",
                content=(
                    "请直接输出一个标准 Markdown 示例，不要执行任何工具，也不要解释。"
                    "只包含一个带 javascript 语言标记的代码围栏，代码内容为两行："
                    "const answer = 42; 和 console.log(answer);。"
                ),
            )
        ],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.intent.domain == "open_domain"
    assert result.intent.intent == "content_formatting"
    assert result.metadata["capability_contract"]["tool_scope"] == "none"
    assert result.metadata["capability_contract"]["required_tools"] == []
    assert result.planner_constraints.planner_needed is True


@pytest.mark.asyncio
async def test_new_code_request_cannot_bypass_sandbox_by_prohibiting_tools():
    service = TaskUnderstandingService(llm_client=None, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[
            ChatMessage(
                role="user",
                content=(
                    "帮我写一个新的 JavaScript 重试函数，只输出 Markdown 代码围栏，"
                    "代码内容为完整可用的实现，但不要执行工具"
                ),
            )
        ],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.intent.intent == "code_generation_task"
    assert result.metadata["capability_contract"]["required_tools"] == ["sandbox_code_execute"]
    assert result.router == "code_generation_rule"


@pytest.mark.asyncio
async def test_new_code_request_with_creation_synonym_cannot_use_rendering_fast_path():
    service = TaskUnderstandingService(llm_client=None, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[
            ChatMessage(
                role="user",
                content=(
                    "请直接输出一个 Markdown 代码块，不要执行任何工具，只包含 Python 代码，"
                    "代码内容为：创建一个读取 CSV 并统计每列缺失率的脚本。"
                ),
            )
        ],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.intent.intent == "code_generation_task"
    assert result.metadata["capability_contract"]["required_tools"] == ["sandbox_code_execute"]


@pytest.mark.asyncio
async def test_natural_language_steps_with_semicolon_are_not_treated_as_literal_code():
    service = TaskUnderstandingService(llm_client=None, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[
            ChatMessage(
                role="user",
                content=("请只输出 Markdown 代码块，代码内容为：读取 CSV; 统计每列缺失率，不要执行工具。"),
            )
        ],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.intent.intent == "code_generation_task"
    assert result.metadata["capability_contract"]["required_tools"] == ["sandbox_code_execute"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "content",
    [
        (
            "请直接输出一个 Markdown 代码块，不要执行任何工具，只包含 Python 代码，"
            "代码内容为：用 pandas 读取 CSV 并统计每列缺失率。"
        ),
        (
            "请直接输出一个 Markdown 代码块，不要执行任何工具，只包含 JavaScript 代码，"
            "代码内容为：排序数组并返回前 10 个元素。"
        ),
        "请直接输出一个 Markdown 代码块，不要执行任何工具，Python 代码：用 pandas 读取 CSV 并统计每列缺失率。",
        "请输出 Markdown 代码块，内容为：用 pandas 读取 CSV 并统计每列缺失率。",
        "请输出 Markdown 代码块，内容为：调用 fetch(url) 获取 JSON 并打印 status。",
        "请输出 Markdown 代码块，内容为：用 request.get(url) 下载页面并提取标题。",
        "请只输出 Markdown 代码块，内容为：配置 db.host = localhost 并读取环境变量。",
        "请把读取 CSV 并统计缺失率的 Python 脚本放进 Markdown 代码块，只输出代码块。",
        "请把一个重试函数放进 Markdown 代码块，只输出代码块。",
        "请把调用 fetch(url) 获取 JSON 并打印 status 的 JavaScript 代码放进 Markdown 代码块。",
        "请输出 Markdown 代码块，内容为：调用 fetch(url); 获取 JSON 并打印 status。",
        "请输出 Markdown 代码块，内容为：用 request.get(url); 下载页面并提取标题。",
        "请输出 Markdown 代码块，内容为：db.host = localhost; 读取环境变量并打印。",
        "请输出 Markdown 代码块，内容为：request.get(url)\n下载页面并提取标题。",
        "请输出 Markdown 代码块，内容为：fetch(url); parse json and print status.",
        "请输出 Markdown 代码块，内容为：request.get(url); extract title.",
        "请输出 Markdown 代码块，内容为：fetch(url)\nparse(json) and print status",
        "请输出 Markdown 代码块，内容为：request.get(url)\nextract(title) from html",
    ],
)
async def test_natural_language_code_goal_cannot_fall_back_to_tool_free_formatting(content):
    service = TaskUnderstandingService(llm_client=None, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content=content)],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.intent.intent == "code_generation_task"
    assert result.router == "code_generation_rule"
    assert result.metadata["capability_contract"]["required_tools"] == ["sandbox_code_execute"]


@pytest.mark.asyncio
async def test_explicit_existing_text_can_still_use_tool_free_formatting():
    service = TaskUnderstandingService(llm_client=None, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[
            ChatMessage(
                role="user",
                content="请把 hello world 原样放进 Markdown 代码块，只输出代码块。",
            )
        ],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.intent.intent == "content_formatting"
    assert result.metadata["capability_contract"]["required_tools"] == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "content",
    [
        "请直接输出 Markdown 代码块，不要执行任何工具，代码内容为：def add(a, b):\n    return a + b",
        (
            "请直接输出 Markdown 代码块，不要执行任何工具，代码内容为："
            "import pandas as pd\ndf = pd.read_csv(path)\nprint(df.head())"
        ),
        "请输出 Markdown 代码块，内容为：for item in items:\n    print(item)",
    ],
)
async def test_multiline_literal_code_can_use_tool_free_formatting(content):
    service = TaskUnderstandingService(llm_client=None, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content=content)],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.intent.intent == "content_formatting"
    assert result.metadata["capability_contract"]["required_tools"] == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "content",
    [
        ("请直接输出 Markdown 代码块，不要执行任何工具，代码内容为：print('hello')  # greeting\nprint('done')"),
        "请直接输出 Markdown 代码块，不要执行任何工具，代码内容为：print('你好')\nprint('完成')",
        ("请直接输出 Markdown 代码块，不要执行任何工具，代码内容为：const msg = '你好';\nconsole.log(msg);"),
        ("请直接输出 Markdown 代码块，不要执行任何工具，代码内容为：const a = 1; // value\nconsole.log(a);"),
    ],
)
async def test_literal_code_with_strings_and_comments_can_use_tool_free_formatting(content):
    service = TaskUnderstandingService(llm_client=None, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content=content)],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.intent.intent == "content_formatting"
    assert result.metadata["capability_contract"]["required_tools"] == []


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
async def test_explicit_web_search_request_promotes_web_search_to_required_tool(monkeypatch):
    monkeypatch.setattr(
        "app.core.intent.task_understanding.TimeUtils.get_current_date",
        lambda: "2026-07-31",
    )
    llm = FakeIntentLLM(
        {
            "resolved_query": "联网查找 OpenAI 最新模型",
            "retrieval_query": "OpenAI 最新模型 发布 2025",
            "planner_query": "联网查找 OpenAI 最新模型并引用来源",
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
            "reason": "用户要求联网查询最新技术信息",
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="联网查找 OpenAI 最新模型")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")
    directive = service.build_directive(service.get_profile("job-buddy"), result)

    assert result.intent.intent == "technical_qa"
    assert result.rewritten_query.retrieval_query == "OpenAI 最新模型 发布 2026"
    assert result.metadata["capability_contract"]["required_tools"] == ["web_search"]
    assert result.metadata["explicit_tool_requirements"] == ["web_search"]
    assert directive["capability_contract"]["required_tools"] == ["web_search"]
    understanding_payload = json.loads(llm.last_messages[-1].content)
    assert understanding_payload["runtime_context"]["current_date"] == "2026-07-31"


@pytest.mark.asyncio
async def test_volatile_external_fact_autonomously_promotes_web_search_to_required_tool(monkeypatch):
    monkeypatch.setattr(
        "app.core.intent.task_understanding.TimeUtils.get_current_date",
        lambda: "2026-08-01",
    )
    llm = FakeIntentLLM(
        {
            "resolved_query": "查找 OpenAI 最新模型并给出来源",
            "retrieval_query": "OpenAI 最新模型 发布 2025",
            "planner_query": "查找 OpenAI 最新模型并核验来源",
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
            "reason": "问题要求核验具有时效性的外部事实",
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="查找 OpenAI 最新模型，并给出来源链接")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")
    directive = service.build_directive(service.get_profile("job-buddy"), result)

    assert result.metadata["capability_contract"]["required_tools"] == ["web_search"]
    assert result.metadata["autonomous_tool_requirements"] == ["web_search"]
    assert result.metadata["web_search_decision"]["mode"] == "required"
    assert result.metadata["web_search_decision"]["trigger"] == "autonomous"
    assert "explicit_tool_requirements" not in result.metadata
    assert directive["capability_contract"]["required_tools"] == ["web_search"]


@pytest.mark.asyncio
async def test_explicit_web_search_prohibition_overrides_volatile_external_fact_signal():
    llm = FakeIntentLLM(
        {
            "resolved_query": "不联网说明 OpenAI 最新模型",
            "retrieval_query": "OpenAI 最新模型",
            "planner_query": "仅根据已有知识说明 OpenAI 最新模型并声明时效边界",
            "context_dependency": "none",
            "context_type": [],
            "selected_capability_id": "open_domain.technical_qa",
            "confidence": 0.94,
            "secondary": [],
            "slots": {},
            "missing_required": [],
            "needs_clarification": False,
            "clarification_question": None,
            "risk_level": "low",
            "answer": None,
            "reason": "用户明确禁止联网",
            "external_information_requirement": {
                "mode": "required",
                "reason": "问题包含最新外部事实",
            },
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="不要搜索网页，说明 OpenAI 最新模型")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert "web_search" not in result.metadata["capability_contract"]["required_tools"]
    assert "explicit_tool_requirements" not in result.metadata
    assert "autonomous_tool_requirements" not in result.metadata


@pytest.mark.asyncio
async def test_model_external_information_decision_can_require_web_search():
    llm = FakeIntentLLM(
        {
            "resolved_query": "判断 Acme 公司管理层信息是否准确",
            "retrieval_query": "Acme 公司管理层",
            "planner_query": "核对 Acme 公司管理层信息",
            "context_dependency": "none",
            "context_type": [],
            "selected_capability_id": "open_domain.technical_qa",
            "confidence": 0.9,
            "secondary": [],
            "slots": {},
            "missing_required": [],
            "needs_clarification": False,
            "clarification_question": None,
            "risk_level": "low",
            "external_information_requirement": {
                "mode": "required",
                "reason": "关键事实需要外部核验",
            },
            "answer": None,
            "reason": "需要核验公开公司信息",
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="判断 Acme 公司管理层信息是否准确")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.metadata["capability_contract"]["required_tools"] == ["web_search"]
    assert result.metadata["web_search_decision"]["trigger"] == "autonomous"
    assert result.metadata["external_information_requirement"]["mode"] == "required"


@pytest.mark.asyncio
async def test_current_job_can_anchor_autonomous_search_for_recent_company_news():
    llm = FakeIntentLLM(
        {
            "resolved_query": "查询当前岗位所在公司的近期新闻",
            "retrieval_query": "当前岗位公司 近期新闻",
            "planner_query": "查询当前岗位所在公司的近期新闻并核验来源",
            "context_dependency": "required",
            "context_type": ["current_jobs"],
            "selected_capability_id": "job.analysis",
            "confidence": 0.93,
            "secondary": [],
            "slots": {},
            "missing_required": [],
            "needs_clarification": False,
            "clarification_question": None,
            "risk_level": "low",
            "answer": None,
            "reason": "当前岗位提供公司实体，新闻仍需联网核验",
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="查询当前岗位所在公司的近期新闻")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.metadata["capability_contract"]["required_tools"] == ["web_search"]
    assert result.metadata["web_search_decision"]["mode"] == "required"
    assert result.metadata["web_search_decision"]["trigger"] == "autonomous"


@pytest.mark.asyncio
async def test_web_search_policy_never_expands_capability_allowlist():
    llm = FakeIntentLLM(
        {
            "resolved_query": "联网搜索今天的新闻",
            "retrieval_query": "今天的新闻",
            "planner_query": "联网搜索今天的新闻",
            "context_dependency": "none",
            "context_type": [],
            "selected_capability_id": "general.chat",
            "confidence": 0.9,
            "secondary": [],
            "slots": {},
            "missing_required": [],
            "needs_clarification": False,
            "clarification_question": None,
            "risk_level": "low",
            "answer": None,
            "reason": "测试未授权能力边界",
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="联网搜索今天的新闻")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.metadata["capability_contract"]["required_tools"] == []
    assert result.metadata["web_search_decision"]["mode"] == "not_allowed"
    assert "explicit_tool_requirements" not in result.metadata


@pytest.mark.asyncio
async def test_ordinary_technical_qa_keeps_web_search_optional():
    llm = FakeIntentLLM(
        {
            "resolved_query": "解释 Spring 事务传播机制",
            "retrieval_query": "Spring 事务传播机制",
            "planner_query": "解释 Spring 事务传播机制",
            "context_dependency": "none",
            "context_type": [],
            "selected_capability_id": "open_domain.technical_qa",
            "confidence": 0.96,
            "secondary": [],
            "slots": {},
            "missing_required": [],
            "needs_clarification": False,
            "clarification_question": None,
            "risk_level": "low",
            "answer": None,
            "reason": "普通技术概念问答",
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="解释一下 Spring 事务传播机制")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.metadata["capability_contract"]["required_tools"] == []
    assert "explicit_tool_requirements" not in result.metadata
    assert "autonomous_tool_requirements" not in result.metadata


@pytest.mark.asyncio
async def test_user_provided_source_material_does_not_trigger_web_search():
    llm = FakeIntentLLM(
        {
            "resolved_query": "总结用户提供的来源材料",
            "retrieval_query": "总结已提供材料",
            "planner_query": "总结用户已经提供的来源材料",
            "context_dependency": "required",
            "context_type": ["recent_dialogue"],
            "selected_capability_id": "open_domain.general_qa",
            "confidence": 0.92,
            "secondary": [],
            "slots": {},
            "missing_required": [],
            "needs_clarification": False,
            "clarification_question": None,
            "risk_level": "low",
            "external_information_requirement": {
                "mode": "not_needed",
                "reason": "用户已经提供所需材料",
            },
            "answer": None,
            "reason": "只需要处理已提供内容",
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="总结我提供的这些来源材料")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.metadata["capability_contract"]["required_tools"] == []
    assert result.metadata["web_search_decision"]["mode"] == "optional"
    assert "autonomous_tool_requirements" not in result.metadata


@pytest.mark.asyncio
async def test_task_understanding_missing_required_slot_blocks_job_search():
    service = TaskUnderstandingService(llm_client=None, allow_semantic_fallback=True)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="帮我找成都高薪岗位")],
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
    assert "web_search" not in result.metadata["capability_contract"]["required_tools"]
    assert "autonomous_tool_requirements" not in result.metadata
    metrics = result.metadata["understanding_metrics"]
    assert metrics["strategy"] == "llm"
    assert metrics["model_called"] is True
    assert isinstance(metrics["duration_ms"], int)
    assert 0 <= metrics["duration_ms"] <= 3_600_000


@pytest.mark.asyncio
async def test_validated_intent_hint_skips_llm_and_derives_result_from_local_profile():
    llm = FakeIntentLLM({"selected_capability_id": "open_domain.general_qa", "confidence": 0.5})
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="搜索并推荐北京 Agent 应用开发招聘岗位，薪资 25-35K")],
        metadata={
            "profile": "job-buddy",
            "intent_hint": {
                "domain": "job",
                "intent": "job.recommend",
                "confidence": 0.9,
                "risk": "low",
                "needs_clarification": False,
                "next_action": "call_get_recommend_jobs",
                "router": "rule",
                "required_tools": ["untrusted_tool"],
            },
        },
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert llm.calls == 0
    assert result.router == "validated_intent_hint"
    assert result.intent.domain == "job"
    assert result.intent.intent == "job.recommend"
    assert result.intent.confidence == pytest.approx(0.95)
    assert result.next_action == "call_get_recommend_jobs"
    assert result.risk_flags.risk_level == "low"
    assert result.slots.filled["city"] == "北京"
    assert result.slots.filled["role"] == "大模型应用开发"
    assert result.metadata["capability_contract"]["required_tools"] == ["boss_browser"]
    assert result.metadata["understanding_metrics"] == {
        "duration_ms": result.metadata["understanding_metrics"]["duration_ms"],
        "strategy": "validated_intent_hint",
        "model_called": False,
    }


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "metadata_override,messages",
    [
        ({"intent_hint": {"router": "scorer"}}, None),
        ({"intent_hint": {"domain": "runtime"}}, None),
        ({"intent_hint": {"next_action": "reject_with_reason"}}, None),
        ({"intent_hint": {"needs_clarification": True}}, None),
        ({"previous_slots": {"role": "云原生后端开发"}}, None),
        ({"attachments": [{"id": "attachment-1"}]}, None),
        (
            {},
            [
                ChatMessage(role="user", content="上一轮"),
                ChatMessage(role="assistant", content="回复"),
                ChatMessage(role="user", content="帮我筛选杭州云原生后端开发 20-30K 岗位"),
            ],
        ),
    ],
)
async def test_untrusted_or_contextual_intent_hint_falls_back_to_llm(metadata_override, messages):
    llm = FakeIntentLLM(
        {
            "selected_capability_id": "job.recommend",
            "confidence": 0.9,
            "needs_clarification": False,
            "slots": {"role": "云原生后端开发"},
        }
    )
    hint = {
        "domain": "job",
        "intent": "job.recommend",
        "confidence": 0.9,
        "risk": "low",
        "needs_clarification": False,
        "next_action": "call_get_recommend_jobs",
        "router": "rule",
    }
    override_hint = metadata_override.get("intent_hint")
    if override_hint:
        hint.update(override_hint)
    metadata = {"profile": "job-buddy", "intent_hint": hint}
    metadata.update({key: value for key, value in metadata_override.items() if key != "intent_hint"})
    request = AgentRunRequest(
        messages=messages or [ChatMessage(role="user", content="帮我筛选杭州云原生后端开发 20-30K 岗位")],
        metadata=metadata,
    )
    service = TaskUnderstandingService(llm_client=llm)

    result = await service.understand(request, "s1", "r1", "t1")

    assert llm.calls == 1
    assert result.router == "llm"


@pytest.mark.asyncio
async def test_intent_hint_requires_local_semantic_top1_agreement():
    llm = FakeIntentLLM({"selected_capability_id": "resume.match", "confidence": 0.9})
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="分析当前简历是否匹配 Agent 应用开发岗位")],
        metadata={
            "profile": "job-buddy",
            "intent_hint": {
                "domain": "job",
                "intent": "job.recommend",
                "confidence": 0.9,
                "risk": "low",
                "needs_clarification": False,
                "next_action": "call_get_recommend_jobs",
                "router": "rule",
            },
        },
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert llm.calls == 1
    assert result.router == "llm"


@pytest.mark.asyncio
async def test_llm_prompt_does_not_duplicate_capability_catalog_in_user_payload():
    llm = FakeIntentLLM({"selected_capability_id": "resume.match", "confidence": 0.9})
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="分析当前简历是否匹配 Agent 应用开发岗位")],
        metadata={"profile": "job-buddy"},
    )

    await service.understand(request, "s1", "r1", "t1")

    user_payload = json.loads(llm.last_messages[-1].content)
    capability_system_messages = [
        message
        for message in llm.last_messages
        if message.role == "system" and "能力卡目录按 id 稳定排序" in message.content
    ]
    assert "capabilities" not in user_payload
    assert len(capability_system_messages) == 1


@pytest.mark.asyncio
async def test_task_understanding_routes_resume_switch_follow_up_without_llm():
    llm = FakeIntentLLM({"selected_capability_id": "open_domain.general_qa", "confidence": 0.5})
    selected_job = {
        "jobName": "云原生后端工程师",
        "company": "示例科技",
        "description": "负责后端平台开发、服务治理与工程化落地",
    }
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[
            ChatMessage(role="user", content="分析此岗位与当前简历的匹配度"),
            ChatMessage(role="assistant", content="已完成当前岗位与简历的匹配分析。"),
            ChatMessage(role="user", content="现在这个5年经验的简历呢"),
        ],
        metadata={
            "profile": "job-buddy",
            "resume_id": "resume-5-years",
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
        messages=[ChatMessage(role="user", content="现在这个5年经验的简历呢")],
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
        "现在用这个5年的简历分析另一个杭州 Go 云原生平台开发岗",
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


@pytest.mark.asyncio
async def test_relative_year_normalization_applies_to_all_rewritten_queries(monkeypatch):
    monkeypatch.setattr(
        "app.core.intent.task_understanding.TimeUtils.get_current_date",
        lambda: "2026-08-01",
    )
    llm = FakeIntentLLM(
        {
            "resolved_query": "查找 Anthropic 2025 年最新发布的工程博客",
            "retrieval_query": "Anthropic 2025 最新工程博客",
            "planner_query": "从 Anthropic 官网查找 2025 年最新工程博客",
            "context_dependency": "none",
            "context_type": [],
            "selected_capability_id": "open_domain.technical_qa",
            "confidence": 0.96,
            "secondary": [],
            "slots": {},
            "missing_required": [],
            "needs_clarification": False,
            "clarification_question": None,
            "risk_level": "low",
            "answer": None,
            "reason": "用户询问最新外部技术资料",
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="查找 Anthropic 最新工程博客")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.rewritten_query.resolved_query == "查找 Anthropic 2026 年最新发布的工程博客"
    assert result.rewritten_query.retrieval_query == "Anthropic 2026 最新工程博客"
    assert result.rewritten_query.planner_query == "从 Anthropic 官网查找 2026 年最新工程博客"


@pytest.mark.asyncio
async def test_explicit_year_is_preserved_in_all_rewritten_queries(monkeypatch):
    monkeypatch.setattr(
        "app.core.intent.task_understanding.TimeUtils.get_current_date",
        lambda: "2026-08-01",
    )
    llm = FakeIntentLLM(
        {
            "resolved_query": "查找 Anthropic 2024 年发布的工程博客",
            "retrieval_query": "Anthropic 2024 工程博客",
            "planner_query": "从 Anthropic 官网查找 2024 年工程博客",
            "context_dependency": "none",
            "context_type": [],
            "selected_capability_id": "open_domain.technical_qa",
            "confidence": 0.96,
            "secondary": [],
            "slots": {},
            "missing_required": [],
            "needs_clarification": False,
            "clarification_question": None,
            "risk_level": "low",
            "answer": None,
            "reason": "用户指定历史年份",
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="查找 Anthropic 2024 年工程博客")],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.rewritten_query.resolved_query == "查找 Anthropic 2024 年发布的工程博客"
    assert result.rewritten_query.retrieval_query == "Anthropic 2024 工程博客"
    assert result.rewritten_query.planner_query == "从 Anthropic 官网查找 2024 年工程博客"


@pytest.mark.parametrize(
    "message",
    [
        "查找 Anthropic 2024 年最新工程博客",
        "查找 Anthropic 2024 年内最新工程博客",
        "查找 Anthropic 2024 年发布的最新工程博客",
    ],
)
def test_latest_selection_uses_explicit_year_end_as_cutoff(monkeypatch, message):
    monkeypatch.setattr(
        "app.core.intent.task_understanding.TimeUtils.get_current_date",
        lambda: "2026-08-01",
    )
    service = TaskUnderstandingService(llm_client=None)
    rewrite = QueryRewrite(resolved_query=message, retrieval_query=message, planner_query=message)

    contracted = service._with_temporal_selection_contract(message, rewrite)

    assert contracted.selection_mode == "latest"
    assert contracted.time_range_start == "2024-01-01"
    assert contracted.as_of_date == "2024-12-31"


def test_latest_selection_caps_current_year_at_runtime_date(monkeypatch):
    monkeypatch.setattr(
        "app.core.intent.task_understanding.TimeUtils.get_current_date",
        lambda: "2026-08-01",
    )
    service = TaskUnderstandingService(llm_client=None)
    message = "查找 Anthropic 2026 年最新工程博客"
    rewrite = QueryRewrite(resolved_query=message, retrieval_query=message, planner_query=message)

    contracted = service._with_temporal_selection_contract(message, rewrite)

    assert contracted.selection_mode == "latest"
    assert contracted.time_range_start == "2026-01-01"
    assert contracted.as_of_date == "2026-08-01"


@pytest.mark.parametrize(
    "message",
    [
        "比较 2024 年 AI 趋势和 Anthropic 最新工程博客",
        "OpenAI gpt-4o-2024-08-06 和最新模型对比",
        "回顾我 2024 年的经历，再查 Anthropic 最新工程博客",
        "比较 OpenAI 2024 年模型和最新模型",
        "Anthropic 2024 年工程博客和最新一篇有什么不同",
        "2024 年旧版本与最新版本对比",
    ],
)
def test_latest_selection_does_not_bind_unrelated_dates(monkeypatch, message):
    monkeypatch.setattr(
        "app.core.intent.task_understanding.TimeUtils.get_current_date",
        lambda: "2026-08-01",
    )
    service = TaskUnderstandingService(llm_client=None)

    assert service._latest_time_bounds(message, require_latest=True) is None


def test_latest_follow_up_does_not_inherit_unrelated_history_year(monkeypatch):
    monkeypatch.setattr(
        "app.core.intent.task_understanding.TimeUtils.get_current_date",
        lambda: "2026-08-01",
    )
    service = TaskUnderstandingService(llm_client=None)
    recent_messages = [
        {
            "role": "user",
            "content": "我 2024 年毕业，请帮我找 Anthropic 工程博客",
        }
    ]

    bounds_with_reference = service._inherited_latest_time_bounds(
        "最新的呢",
        {
            "resolved_references": [
                {
                    "resolved_to": "Anthropic 工程博客",
                    "source": "recent_dialogue",
                    "confidence": 0.98,
                }
            ]
        },
        recent_messages,
    )
    bounds_without_reference = service._inherited_latest_time_bounds(
        "最新的呢",
        {"resolved_references": []},
        recent_messages,
    )
    genuine_bounds = service._inherited_latest_time_bounds(
        "最新的呢",
        {"resolved_references": []},
        [{"role": "user", "content": "查找 Anthropic 2024 年工程博客"}],
    )

    assert bounds_with_reference is None
    assert bounds_without_reference is None
    assert genuine_bounds is None


def test_latest_selection_preserves_explicit_as_of_date(monkeypatch):
    monkeypatch.setattr(
        "app.core.intent.task_understanding.TimeUtils.get_current_date",
        lambda: "2026-08-01",
    )
    service = TaskUnderstandingService(llm_client=None)
    rewrite = QueryRewrite(
        resolved_query="截至 2025-12-31 查找 Anthropic 最新工程博客",
        retrieval_query="Anthropic 最新工程博客 截至 2025-12-31",
        planner_query="从 Anthropic 官网查找截至 2025-12-31 的最新工程博客",
    )

    contracted = service._with_temporal_selection_contract(
        "截至 2025-12-31 查找 Anthropic 最新工程博客",
        rewrite,
    )

    assert contracted.selection_mode == "latest"
    assert contracted.time_range_start == ""
    assert contracted.as_of_date == "2025-12-31"


@pytest.mark.parametrize(
    "message",
    ["查找 Anthropic 最近发布的一篇工程博客", "查找 Anthropic 最近发布的工程博客", "查找 Anthropic 最晚发布的工程博客"],
)
def test_latest_selection_contract_recognizes_release_synonyms(monkeypatch, message):
    monkeypatch.setattr(
        "app.core.intent.task_understanding.TimeUtils.get_current_date",
        lambda: "2026-08-01",
    )
    service = TaskUnderstandingService(llm_client=None)
    rewrite = QueryRewrite(resolved_query=message, retrieval_query=message, planner_query=message)

    contracted = service._with_temporal_selection_contract(message, rewrite)

    assert contracted.selection_mode == "latest"
    assert contracted.as_of_date == "2026-08-01"
    assert contracted.source_preference == "official_first"


@pytest.mark.asyncio
@pytest.mark.parametrize("model_year", ["2024", "2025"])
async def test_latest_follow_up_preserves_year_resolved_from_recent_dialogue(monkeypatch, model_year):
    monkeypatch.setattr(
        "app.core.intent.task_understanding.TimeUtils.get_current_date",
        lambda: "2026-08-01",
    )
    llm = FakeIntentLLM(
        {
            "resolved_query": f"查找 Anthropic {model_year} 年最新工程博客",
            "retrieval_query": f"Anthropic {model_year} 年最新工程博客",
            "planner_query": f"从 Anthropic 官网查找 {model_year} 年最新工程博客",
            "context_dependency": "required",
            "context_type": ["recent_dialogue"],
            "resolved_references": [
                {
                    "text": "最新的呢",
                    "resolved_to": "Anthropic 2024 年工程博客",
                    "source": "recent_dialogue",
                    "confidence": 0.98,
                }
            ],
            "selected_capability_id": "open_domain.technical_qa",
            "confidence": 0.96,
            "secondary": [],
            "slots": {},
            "missing_required": [],
            "needs_clarification": False,
            "risk_level": "low",
            "answer": None,
            "reason": "承接上一轮指定年份",
        }
    )
    service = TaskUnderstandingService(llm_client=llm)
    request = AgentRunRequest(
        messages=[
            ChatMessage(role="user", content="查找 Anthropic 2024 年工程博客"),
            ChatMessage(role="assistant", content="截至 2026-08-01，我先给你列出几篇。"),
            ChatMessage(role="user", content="最新的呢"),
        ],
        metadata={"profile": "job-buddy"},
    )

    result = await service.understand(request, "s1", "r1", "t1")

    assert result.rewritten_query.resolved_query == "查找 Anthropic 2024 年最新工程博客"
    assert result.rewritten_query.retrieval_query == "Anthropic 2024 年最新工程博客"
    assert result.rewritten_query.planner_query == "从 Anthropic 官网查找 2024 年最新工程博客"
    assert result.rewritten_query.selection_mode == "latest"
    assert result.rewritten_query.time_range_start == "2024-01-01"
    assert result.rewritten_query.as_of_date == "2024-12-31"
