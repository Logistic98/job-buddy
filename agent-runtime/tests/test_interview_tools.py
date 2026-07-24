import json

import pytest

from app.core.tool.base import ToolExecutionContext
from app.models.schemas import ToolCall
from app.tools_builtin.interview_tools import InterviewQuestionGenerateTool


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
