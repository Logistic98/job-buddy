"""面试题结构化生成工具。

该工具只调用模型生成待审核草稿，不访问外部题库，也不执行任何持久化。业务题目规则放在
Prompt 资产中，Runtime Core 只负责加载、调用与结构校验。
"""

import json
import re
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

from app.core.common.constants import ToolRiskLevel
from app.core.llm.openai_client import LLMServiceError, OpenAICompatibleClient
from app.core.prompt.loader import PromptTemplateLoader
from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult
from app.models.schemas import ChatMessage

MAX_SOURCE_TEXT_CHARS = 20000
MAX_GENERATION_TOKENS = 16384
SUPPORTED_BANK_TYPES = {"leetcode", "qa"}
SUPPORTED_LANGUAGES = {"python", "java", "javascript"}
SUPPORTED_DIFFICULTIES = {"简单", "中等", "困难"}
LEETCODE_HOSTS = {"leetcode.com", "www.leetcode.com", "leetcode.cn", "www.leetcode.cn"}
FUNCTION_NAME_PATTERN = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")


def _extract_json_object(content: str) -> Dict[str, Any]:
    text = str(content or "").strip()
    if not text:
        raise ValueError("模型未返回候选题")
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        fenced = re.search(r"```(?:json)?\s*(\{.*\})\s*```", text, re.DOTALL)
        candidate = fenced.group(1) if fenced else text[text.find("{") : text.rfind("}") + 1]
        try:
            value = json.loads(candidate)
        except (json.JSONDecodeError, TypeError) as exc:
            raise ValueError("模型返回内容不是完整 JSON，请重新生成") from exc
    if not isinstance(value, dict):
        raise ValueError("模型返回的候选题必须是 JSON 对象")
    return value


def _validate_source_url(value: str) -> str:
    source_url = str(value or "").strip()
    if not source_url:
        return ""
    parsed = urlparse(source_url)
    if parsed.scheme != "https" or parsed.hostname not in LEETCODE_HOSTS:
        raise ValueError("LeetCode 来源链接仅支持 leetcode.com 或 leetcode.cn 的 HTTPS 题目地址")
    if not re.fullmatch(r"/problems/[^/?#]+/?", parsed.path):
        raise ValueError("请输入标准 LeetCode 题目链接，例如 https://leetcode.com/problems/two-sum/")
    return source_url


def _required_text(value: Any, field: str) -> str:
    text = str(value or "").strip()
    if not text:
        raise ValueError(f"模型生成结果缺少 {field}")
    return text


def _normalize_tags(value: Any, category: str) -> List[str]:
    rows = value if isinstance(value, list) else []
    tags: List[str] = []
    for row in rows:
        label = row.get("label") if isinstance(row, dict) else row
        text = str(label or "").strip()
        if text and text not in tags:
            tags.append(text[:32])
    if category and category not in tags:
        tags.insert(0, category[:32])
    return tags[:12]


def _normalize_tests(value: Any) -> tuple[List[Dict[str, Any]], int]:
    if not isinstance(value, list) or len(value) < 3:
        raise ValueError("每道算法候选题至少需要 3 条可复核测试用例")
    tests: List[Dict[str, Any]] = []
    parameter_count: Optional[int] = None
    for index, row in enumerate(value[:12]):
        if not isinstance(row, dict) or not isinstance(row.get("args"), list) or "expected" not in row:
            raise ValueError(f"第 {index + 1} 条测试用例必须包含 args 数组和 expected")
        args = row["args"]
        if not 1 <= len(args) <= 10:
            raise ValueError(f"第 {index + 1} 条测试用例需包含 1-10 个函数参数")
        if parameter_count is None:
            parameter_count = len(args)
        elif parameter_count != len(args):
            raise ValueError("同一道题的测试用例参数数量必须一致")
        tests.append(
            {
                "name": str(row.get("name") or f"用例 {index + 1}").strip()[:80],
                "args": args,
                "expected": row["expected"],
                "sample": bool(row.get("sample")),
            }
        )
    if not any(item["sample"] for item in tests):
        tests[0]["sample"] = True
    return tests, int(parameter_count or 0)


def _normalize_coding_meta(value: Any, language: str) -> Dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("算法候选题缺少 codingMeta")
    model_language = str(value.get("language") or "").strip().lower()
    if model_language != language:
        raise ValueError("模型生成的代码语言与请求不一致，请重新生成")
    function_name = _required_text(value.get("functionName"), "codingMeta.functionName")
    if not FUNCTION_NAME_PATTERN.fullmatch(function_name):
        raise ValueError("模型生成的函数入口名称不合法")
    template = _required_text(value.get("template"), "codingMeta.template")
    if function_name not in template:
        raise ValueError("模型生成的函数入口与代码模板不一致")
    tests, parameter_count = _normalize_tests(value.get("tests"))
    return {
        "language": language,
        "functionName": function_name,
        "signature": str(value.get("signature") or function_name).strip(),
        "template": template,
        "parameterCount": parameter_count,
        "tests": tests,
    }


def _normalize_item(
    value: Any,
    bank_type: str,
    category: str,
    difficulty: str,
    question_type: str,
    language: str,
) -> Dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("模型生成的题目结构不正确")
    normalized_category = str(value.get("category") or category).strip() or category
    normalized_difficulty = str(value.get("difficulty") or difficulty).strip()
    if normalized_difficulty not in SUPPORTED_DIFFICULTIES:
        normalized_difficulty = difficulty
    item: Dict[str, Any] = {
        "title": _required_text(value.get("title"), "title"),
        "bankType": bank_type,
        "category": normalized_category,
        "difficulty": normalized_difficulty,
        "questionType": "编程题" if bank_type == "leetcode" else question_type,
        "content": _required_text(value.get("content"), "content"),
        "answer": str(value.get("answer") or "").strip(),
        "tags": _normalize_tags(value.get("tags"), normalized_category),
    }
    if bank_type == "leetcode":
        item["codingMeta"] = _normalize_coding_meta(value.get("codingMeta"), language)
    return item


class InterviewQuestionGenerateTool(BaseTool):
    name = "interview_question_generate"
    aliases = ["generate_interview_questions"]
    search_hint = "面试题 算法题 题库 候选题 结构化生成 人工审核"
    description = "根据主题、用户资料和可选 LeetCode 来源标识生成结构化候选题；只读且不入库。"
    input_schema = {
        "type": "object",
        "properties": {
            "topic": {"type": "string"},
            "bank_type": {"type": "string", "enum": ["leetcode", "qa"]},
            "category": {"type": "string"},
            "difficulty": {"type": "string", "enum": ["简单", "中等", "困难"]},
            "question_type": {"type": "string"},
            "language": {"type": "string", "enum": ["python", "java", "javascript"]},
            "count": {"type": "integer", "minimum": 1, "maximum": 20},
            "requirements": {"type": "string"},
            "source_url": {"type": "string"},
            "source_text": {"type": "string"},
        },
        "required": ["bank_type", "category", "difficulty", "question_type", "count"],
    }
    output_schema = {
        "type": "object",
        "properties": {
            "count": {"type": "integer"},
            "items": {"type": "array"},
            "source_url": {"type": "string"},
            "notice": {"type": "string"},
        },
        "required": ["count", "items", "notice"],
    }
    tags = ["interview", "question-bank", "generation"]
    timeout_seconds = 120
    max_result_size_chars = 100000
    risk_level = ToolRiskLevel.LOW
    read_only = True

    def __init__(
        self,
        llm_client: Optional[OpenAICompatibleClient] = None,
        prompt_loader: Optional[PromptTemplateLoader] = None,
    ):
        self._llm_client = llm_client
        self._prompt_loader = prompt_loader or PromptTemplateLoader()

    def _client(self) -> OpenAICompatibleClient:
        if self._llm_client is None:
            self._llm_client = OpenAICompatibleClient()
        return self._llm_client

    async def validate_input(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> ValidationResult:
        base = await super().validate_input(arguments, context)
        if not base.result:
            return base
        bank_type = str(arguments.get("bank_type") or "").strip()
        if bank_type not in SUPPORTED_BANK_TYPES:
            return ValidationResult(result=False, message="不支持的题库类型", error_code=400)
        try:
            count = int(arguments.get("count"))
        except (TypeError, ValueError):
            return ValidationResult(result=False, message="生成数量必须是整数", error_code=400)
        if not 1 <= count <= 20:
            return ValidationResult(result=False, message="生成数量需在 1-20 之间", error_code=400)
        if bank_type == "leetcode" and str(arguments.get("language") or "") not in SUPPORTED_LANGUAGES:
            return ValidationResult(result=False, message="请选择支持的代码语言", error_code=400)
        if str(arguments.get("difficulty") or "") not in SUPPORTED_DIFFICULTIES:
            return ValidationResult(result=False, message="请选择支持的难度", error_code=400)
        if not any(
            str(arguments.get(key) or "").strip() for key in ("topic", "source_url", "source_text", "requirements")
        ):
            message = (
                "请提供算法主题、LeetCode 链接、题面或算法资料"
                if bank_type == "leetcode"
                else "请提供知识主题、参考文本、出题要求或问答资料"
            )
            return ValidationResult(result=False, message=message, error_code=400)
        try:
            _validate_source_url(arguments.get("source_url"))
        except ValueError as exc:
            return ValidationResult(result=False, message=str(exc), error_code=400)
        return ValidationResult(result=True)

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        bank_type = str(arguments["bank_type"]).strip()
        category = _required_text(arguments.get("category"), "category")
        difficulty = str(arguments["difficulty"]).strip()
        question_type = str(arguments["question_type"]).strip()
        language = str(arguments.get("language") or "python").strip()
        count = int(arguments["count"])
        source_url = _validate_source_url(arguments.get("source_url"))
        source_text = str(arguments.get("source_text") or "").strip()[:MAX_SOURCE_TEXT_CHARS]
        prompt = self._prompt_loader.load("artifacts/interview_question_generation.md")
        if not prompt:
            raise RuntimeError("面试题生成 Prompt 未配置")
        generation_input = {
            "topic": str(arguments.get("topic") or "").strip(),
            "bank_type": bank_type,
            "category": category,
            "difficulty": difficulty,
            "question_type": question_type,
            "language": language,
            "count": count,
            "requirements": str(arguments.get("requirements") or "").strip(),
            "source_url": source_url,
            "source_text": source_text,
        }
        try:
            response = await self._client().chat(
                messages=[
                    ChatMessage(role="system", content=prompt),
                    ChatMessage(role="user", content=json.dumps(generation_input, ensure_ascii=False)),
                ],
                temperature=0.2,
                max_tokens=MAX_GENERATION_TOKENS,
                disable_thinking=True,
            )
        except LLMServiceError as exc:
            raise RuntimeError(f"候选题生成调用模型失败：{exc}") from exc
        payload = _extract_json_object(response.get("content") or "")
        rows = payload.get("items")
        if not isinstance(rows, list) or len(rows) != count:
            raise ValueError(f"模型应返回 {count} 道候选题，请重新生成")
        items = [_normalize_item(row, bank_type, category, difficulty, question_type, language) for row in rows]
        notice = (
            "算法候选题尚未入库，请人工核对题面、代码入口和测试预期后确认导入。"
            if bank_type == "leetcode"
            else "问答候选题尚未入库，请人工核对题干、选项或参考答案后确认导入。"
        )
        return {
            "count": len(items),
            "items": items,
            "source_url": source_url,
            "notice": notice,
        }
