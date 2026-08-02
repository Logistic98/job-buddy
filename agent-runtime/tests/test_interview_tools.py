import asyncio
import json

import pytest

from app.core.tool.base import ToolExecutionContext
from app.models.schemas import ToolCall
from app.tools_builtin.interview_tools import (
    InterviewPaperComposeTool,
    InterviewQuestionGenerateTool,
    _generation_batches,
)


class _StubLLM:
    def __init__(self, content):
        self.content = content
        self.calls = []

    async def chat(self, messages, temperature=None, max_tokens=None, disable_thinking=False):
        self.calls.append(
            {
                "messages": messages,
                "temperature": temperature,
                "max_tokens": max_tokens,
                "disable_thinking": disable_thinking,
            }
        )
        return {"content": self.content}


class _ParallelStubLLM:
    def __init__(self):
        self.calls = []
        self.active_calls = 0
        self.max_active_calls = 0

    async def chat(self, messages, temperature=None, max_tokens=None, disable_thinking=False):
        self.calls.append({"messages": messages, "max_tokens": max_tokens})
        self.active_calls += 1
        self.max_active_calls = max(self.max_active_calls, self.active_calls)
        try:
            await asyncio.sleep(0.02)
            generation_input = json.loads(messages[1].content)
            start_index = generation_input["candidate_start_index"]
            items = []
            for offset in range(generation_input["count"]):
                item = json.loads(json.dumps(_algorithm_item(), ensure_ascii=False))
                item["title"] = f"并行候选题 {start_index + offset}"
                items.append(item)
            return {"content": json.dumps({"items": items}, ensure_ascii=False)}
        finally:
            self.active_calls -= 1


def _context():
    return ToolExecutionContext(
        run_id="run_interview",
        trace_id="trace_interview",
        session_id="session_interview",
        workspace_dir=".",
    )


def _algorithm_item():
    return {
        "title": "区间合并计数",
        "bankType": "leetcode",
        "category": "数组",
        "difficulty": "中等",
        "questionType": "编程题",
        "content": "给定区间数组，合并重叠区间并返回合并后的数量。说明输入、输出与约束。",
        "answer": "排序后线性扫描，时间复杂度 O(n log n)。",
        "tags": ["数组", "排序"],
        "codingMeta": {
            "language": "python",
            "functionName": "merge_count",
            "signature": "merge_count(intervals)",
            "template": "def merge_count(intervals):\n    # TODO: implement\n    pass\n",
            "parameterCount": 1,
            "tests": [
                {"name": "公开样例", "args": [[[1, 3], [2, 4]]], "expected": 1, "sample": True},
                {"name": "空数组", "args": [[]], "expected": 0, "sample": False},
                {"name": "互不重叠", "args": [[[1, 2], [3, 4]]], "expected": 2, "sample": False},
            ],
        },
    }


@pytest.mark.asyncio
async def test_generate_algorithm_candidates_without_persistence():
    stub = _StubLLM(json.dumps({"items": [_algorithm_item()]}, ensure_ascii=False))
    tool = InterviewQuestionGenerateTool(llm_client=stub)
    result = await tool.safe_run(
        ToolCall(
            id="call_generate",
            name=tool.name,
            arguments={
                "topic": "区间问题",
                "bank_type": "leetcode",
                "category": "数组",
                "difficulty": "中等",
                "question_type": "编程题",
                "language": "python",
                "count": 1,
                "source_url": "https://leetcode.com/problems/merge-intervals/",
                "source_text": "用户粘贴的参考题面",
            },
        ),
        _context(),
    )

    assert result.success is True
    assert result.output["count"] == 1
    assert result.output["items"][0]["codingMeta"]["parameterCount"] == 1
    assert len(result.output["items"][0]["codingMeta"]["tests"]) == 3
    assert result.output["notice"].startswith("算法候选题尚未入库")
    assert len(stub.calls) == 1
    user_input = json.loads(stub.calls[0]["messages"][1].content)
    assert user_input["source_url"] == "https://leetcode.com/problems/merge-intervals/"
    assert user_input["source_text"] == "用户粘贴的参考题面"
    system_prompt = stub.calls[0]["messages"][0].content
    assert "## 1. 解题思路" in system_prompt
    assert "## 2. 口述要点" in system_prompt
    assert "## 3. 代码示例" in system_prompt
    assert "参考实现必须能通过 codingMeta.tests" in system_prompt


def test_generation_batches_are_bounded_and_keep_order():
    assert _generation_batches(1, 4) == [(1, 1)]
    assert _generation_batches(10, 4) == [(1, 3), (4, 3), (7, 2), (9, 2)]


@pytest.mark.asyncio
async def test_generate_multiple_candidates_in_parallel(monkeypatch):
    stub = _ParallelStubLLM()
    tool = InterviewQuestionGenerateTool(llm_client=stub)
    monkeypatch.setattr("app.tools_builtin.interview_tools.settings.config.runtime.interview_generation_concurrency", 3)

    result = await tool.safe_run(
        ToolCall(
            id="call_parallel_generate",
            name=tool.name,
            arguments={
                "topic": "数组",
                "bank_type": "leetcode",
                "category": "数组",
                "difficulty": "中等",
                "question_type": "编程题",
                "language": "python",
                "count": 3,
            },
        ),
        _context(),
    )

    assert result.success is True
    assert [item["title"] for item in result.output["items"]] == ["并行候选题 1", "并行候选题 2", "并行候选题 3"]
    assert len(stub.calls) == 3
    assert stub.max_active_calls == 3
    assert all(json.loads(call["messages"][1].content)["count"] == 1 for call in stub.calls)


@pytest.mark.asyncio
async def test_generate_qa_choice_candidates_from_requirements_only():
    item = {
        "title": "HashMap 线程安全判断",
        "bankType": "qa",
        "category": "Java 基础",
        "difficulty": "中等",
        "questionType": "单选",
        "content": "以下关于 HashMap 的描述，正确的是哪一项？\n\nA. 默认线程安全\nB. 默认线程不安全",
        "answer": "B",
        "tags": ["Java", "集合"],
    }
    stub = _StubLLM(json.dumps({"items": [item]}, ensure_ascii=False))
    tool = InterviewQuestionGenerateTool(llm_client=stub)
    result = await tool.safe_run(
        ToolCall(
            id="call_generate_qa",
            name=tool.name,
            arguments={
                "bank_type": "qa",
                "category": "Java 基础",
                "difficulty": "中等",
                "question_type": "单选",
                "count": 1,
                "requirements": "生成一道考察 Java 集合线程安全性的单选题",
            },
        ),
        _context(),
    )

    assert result.success is True
    assert result.output["count"] == 1
    assert result.output["items"][0]["questionType"] == "单选"
    assert "codingMeta" not in result.output["items"][0]
    assert result.output["notice"].startswith("问答候选题尚未入库")
    user_input = json.loads(stub.calls[0]["messages"][1].content)
    assert user_input["question_type"] == "单选"
    assert user_input["requirements"] == "生成一道考察 Java 集合线程安全性的单选题"


@pytest.mark.asyncio
async def test_rejects_non_leetcode_source_url_before_model_call():
    stub = _StubLLM("{}")
    tool = InterviewQuestionGenerateTool(llm_client=stub)
    result = await tool.safe_run(
        ToolCall(
            id="call_bad_url",
            name=tool.name,
            arguments={
                "bank_type": "leetcode",
                "category": "数组",
                "difficulty": "中等",
                "question_type": "编程题",
                "language": "python",
                "count": 1,
                "source_url": "https://example.com/problems/two-sum/",
            },
        ),
        _context(),
    )

    assert result.success is False
    assert "仅支持 leetcode.com 或 leetcode.cn" in result.error
    assert stub.calls == []


@pytest.mark.asyncio
async def test_rejects_inconsistent_generated_test_arguments():
    item = _algorithm_item()
    item["codingMeta"]["tests"][2]["args"] = [[1, 2], 3]
    tool = InterviewQuestionGenerateTool(llm_client=_StubLLM(json.dumps({"items": [item]}, ensure_ascii=False)))
    result = await tool.safe_run(
        ToolCall(
            id="call_bad_tests",
            name=tool.name,
            arguments={
                "topic": "数组",
                "bank_type": "leetcode",
                "category": "数组",
                "difficulty": "中等",
                "question_type": "编程题",
                "language": "python",
                "count": 1,
            },
        ),
        _context(),
    )

    assert result.success is False
    assert "参数数量必须一致" in result.error


@pytest.mark.asyncio
async def test_compose_interview_paper_from_existing_candidates():
    candidates = [
        {
            "question_id": "q-java",
            "bank_type": "qa",
            "title": "Java 并发可见性",
            "category": "Java 并发",
            "difficulty": "中等",
            "question_type": "简答",
            "tags": ["Java", "并发"],
            "content_summary": "说明 volatile 的可见性与有序性语义。",
        },
        {
            "question_id": "q-redis",
            "bank_type": "qa",
            "title": "Redis 持久化",
            "category": "Redis",
            "difficulty": "中等",
            "question_type": "单选",
            "tags": ["Redis"],
            "content_summary": "比较 RDB 与 AOF。",
        },
    ]
    stub = _StubLLM(
        json.dumps(
            {
                "title": "Java 与 Redis 专项练习",
                "duration_minutes": 45,
                "show_answer": False,
                "question_ids": ["q-java", "q-redis"],
                "selection_summary": "选择两道中等难度题，覆盖 Java 并发与 Redis。",
            },
            ensure_ascii=False,
        )
    )
    tool = InterviewPaperComposeTool(llm_client=stub)

    result = await tool.safe_run(
        ToolCall(
            id="call_compose",
            name=tool.name,
            arguments={
                "requirements": "选择 Java 并发和 Redis 中等难度题，45 分钟考试模式",
                "candidates": candidates,
            },
        ),
        _context(),
    )

    assert result.success is True
    assert result.output["question_ids"] == ["q-java", "q-redis"]
    assert result.output["duration_minutes"] == 45
    assert result.output["show_answer"] is False
    user_input = json.loads(stub.calls[0]["messages"][1].content)
    assert user_input["requirements"].startswith("选择 Java 并发")
    assert "answer" not in user_input["candidates"][0]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("question_ids", "message"),
    [
        (["q-java", "q-java"], "不能包含重复题目"),
        (["q-java", "q-unknown"], "包含候选集之外的题目"),
        ([], "至少选择 1 道题"),
    ],
)
async def test_rejects_invalid_interview_paper_selection(question_ids, message):
    stub = _StubLLM(
        json.dumps(
            {
                "title": "无效试卷",
                "duration_minutes": 30,
                "show_answer": False,
                "question_ids": question_ids,
                "selection_summary": "测试非法选题结果。",
            },
            ensure_ascii=False,
        )
    )
    tool = InterviewPaperComposeTool(llm_client=stub)

    result = await tool.safe_run(
        ToolCall(
            id="call_invalid_compose",
            name=tool.name,
            arguments={
                "requirements": "选择 Java 并发题组成一套练习",
                "candidates": [
                    {
                        "question_id": "q-java",
                        "bank_type": "qa",
                        "title": "Java 并发可见性",
                        "category": "Java 并发",
                        "difficulty": "中等",
                        "question_type": "简答",
                        "tags": ["Java", "并发"],
                        "content_summary": "说明 volatile 语义。",
                    }
                ],
            },
        ),
        _context(),
    )

    assert result.success is False
    assert message in result.error
