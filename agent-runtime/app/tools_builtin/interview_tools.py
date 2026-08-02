"""面试题结构化生成工具。

该工具只调用模型生成待审核草稿，不访问外部题库，也不执行任何持久化。业务题目规则放在
Prompt 资产中，Runtime Core 只负责加载、调用与结构校验。
"""

import asyncio
import json
import re
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

from app.core.common.constants import ToolRiskLevel
from app.core.common.settings import settings
from app.core.llm.openai_client import LLMServiceError, OpenAICompatibleClient
from app.core.prompt.loader import PromptTemplateLoader
from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult
from app.models.schemas import ChatMessage

MAX_SOURCE_TEXT_CHARS = 20000
MAX_GENERATION_TOKENS = 16384
MAX_SMART_PAPER_CANDIDATES = 200
MAX_SMART_PAPER_REQUIREMENT_CHARS = 1000
MAX_SMART_PAPER_TOKENS = 8192
SUPPORTED_BANK_TYPES = {"leetcode", "qa"}
SUPPORTED_LANGUAGES = {"python", "java", "javascript"}
SUPPORTED_DIFFICULTIES = {"简单", "中等", "困难"}
LEETCODE_HOSTS = {"leetcode.com", "www.leetcode.com", "leetcode.cn", "www.leetcode.cn"}
FUNCTION_NAME_PATTERN = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")


def _generation_batches(count: int, concurrency: int) -> List[tuple[int, int]]:
    """把候选题数量均匀拆成有序批次，返回每批的起始序号和题量。"""

    worker_count = min(count, max(1, concurrency))
    base_size, remainder = divmod(count, worker_count)
    batches: List[tuple[int, int]] = []
    start_index = 1
    for worker_index in range(worker_count):
        batch_size = base_size + (1 if worker_index < remainder else 0)
        batches.append((start_index, batch_size))
        start_index += batch_size
    return batches


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


def _normalize_paper_candidates(value: Any) -> List[Dict[str, Any]]:
    if not isinstance(value, list) or not value:
        raise ValueError("智能组卷至少需要 1 道候选题")
    if len(value) > MAX_SMART_PAPER_CANDIDATES:
        raise ValueError(f"智能组卷候选题不能超过 {MAX_SMART_PAPER_CANDIDATES} 道")
    candidates: List[Dict[str, Any]] = []
    seen_ids: set[str] = set()
    for index, row in enumerate(value):
        if not isinstance(row, dict):
            raise ValueError(f"第 {index + 1} 道候选题结构不正确")
        question_id = _required_text(row.get("question_id"), f"candidates[{index}].question_id")
        if question_id in seen_ids:
            raise ValueError("智能组卷候选题不能包含重复题号")
        seen_ids.add(question_id)
        candidates.append(
            {
                "question_id": question_id,
                "bank_type": str(row.get("bank_type") or "").strip(),
                "title": _required_text(row.get("title"), f"candidates[{index}].title")[:120],
                "category": str(row.get("category") or "").strip()[:64],
                "difficulty": str(row.get("difficulty") or "").strip()[:32],
                "question_type": str(row.get("question_type") or "").strip()[:32],
                "tags": _normalize_tags(row.get("tags"), ""),
                "content_summary": str(row.get("content_summary") or "").strip()[:240],
            }
        )
    return candidates


class InterviewPaperComposeTool(BaseTool):
    """根据自然语言要求从 Backend 提供的现有题目候选中选择一套试卷。"""

    name = "interview_paper_compose"
    aliases = ["compose_interview_paper", "smart_interview_practice"]
    search_hint = "练习中心 智能组卷 自然语言 现有题库 选题 试卷"
    description = "理解自然语言练习要求，从 Backend 提供的现有题目候选中返回结构化试卷方案；只读且不创建练习。"
    input_schema = {
        "type": "object",
        "properties": {
            "requirements": {"type": "string", "minLength": 10, "maxLength": MAX_SMART_PAPER_REQUIREMENT_CHARS},
            "candidates": {
                "type": "array",
                "minItems": 1,
                "maxItems": MAX_SMART_PAPER_CANDIDATES,
                "items": {"type": "object"},
            },
        },
        "required": ["requirements", "candidates"],
    }
    output_schema = {
        "type": "object",
        "properties": {
            "title": {"type": "string"},
            "duration_minutes": {"type": "integer"},
            "show_answer": {"type": "boolean"},
            "question_ids": {"type": "array", "items": {"type": "string"}},
            "selection_summary": {"type": "string"},
        },
        "required": ["title", "duration_minutes", "show_answer", "question_ids", "selection_summary"],
    }
    tags = ["interview", "question-bank", "paper-composition"]
    timeout_seconds = 120
    max_result_size_chars = 12000
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
        requirements = str(arguments.get("requirements") or "").strip()
        if not 10 <= len(requirements) <= MAX_SMART_PAPER_REQUIREMENT_CHARS:
            return ValidationResult(
                result=False,
                message=f"智能组卷要求需为 10-{MAX_SMART_PAPER_REQUIREMENT_CHARS} 个字符",
                error_code=400,
            )
        try:
            _normalize_paper_candidates(arguments.get("candidates"))
        except ValueError as exc:
            return ValidationResult(result=False, message=str(exc), error_code=400)
        return ValidationResult(result=True)

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        requirements = str(arguments["requirements"]).strip()
        candidates = _normalize_paper_candidates(arguments["candidates"])
        prompt = self._prompt_loader.load("artifacts/interview_paper_composition.md")
        if not prompt:
            raise RuntimeError("智能组卷 Prompt 未配置")
        try:
            response = await self._client().chat(
                messages=[
                    ChatMessage(role="system", content=prompt),
                    ChatMessage(
                        role="user",
                        content=json.dumps(
                            {"requirements": requirements, "candidates": candidates},
                            ensure_ascii=False,
                        ),
                    ),
                ],
                temperature=0.1,
                max_tokens=MAX_SMART_PAPER_TOKENS,
                disable_thinking=True,
            )
        except LLMServiceError as exc:
            raise RuntimeError(f"智能组卷调用模型失败：{exc}") from exc

        payload = _extract_json_object(response.get("content") or "")
        title = _required_text(payload.get("title"), "title")[:120]
        try:
            duration_minutes = int(payload.get("duration_minutes"))
        except (TypeError, ValueError) as exc:
            raise ValueError("智能组卷结果的 duration_minutes 必须是整数") from exc
        if not 1 <= duration_minutes <= 240:
            raise ValueError("智能组卷时长需在 1-240 分钟之间")
        show_answer = payload.get("show_answer")
        if not isinstance(show_answer, bool):
            raise ValueError("智能组卷结果的 show_answer 必须是布尔值")
        question_ids_value = payload.get("question_ids")
        if not isinstance(question_ids_value, list) or not 1 <= len(question_ids_value) <= 50:
            raise ValueError("智能组卷至少选择 1 道题且不能超过 50 道")
        question_ids = [str(item or "").strip() for item in question_ids_value]
        if any(not item for item in question_ids):
            raise ValueError("智能组卷结果包含空题号")
        if len(set(question_ids)) != len(question_ids):
            raise ValueError("智能组卷结果不能包含重复题目")
        candidate_ids = {item["question_id"] for item in candidates}
        if any(question_id not in candidate_ids for question_id in question_ids):
            raise ValueError("智能组卷结果包含候选集之外的题目")
        selection_summary = _required_text(payload.get("selection_summary"), "selection_summary")[:500]
        return {
            "title": title,
            "duration_minutes": duration_minutes,
            "show_answer": show_answer,
            "question_ids": question_ids,
            "selection_summary": selection_summary,
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
        # 校验阶段已限制枚举和数量，这里只构造受限模型输入。
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

        async def generate_batch(start_index: int, batch_size: int) -> List[Dict[str, Any]]:
            batch_input = {
                **generation_input,
                "count": batch_size,
                "candidate_start_index": start_index,
                "candidate_total": count,
            }
            try:
                response = await self._client().chat(
                    messages=[
                        ChatMessage(role="system", content=prompt),
                        ChatMessage(role="user", content=json.dumps(batch_input, ensure_ascii=False)),
                    ],
                    temperature=0.2,
                    max_tokens=MAX_GENERATION_TOKENS,
                    disable_thinking=True,
                )
            except LLMServiceError as exc:
                raise RuntimeError(f"第 {start_index}-{start_index + batch_size - 1} 道候选题生成失败：{exc}") from exc
            payload = _extract_json_object(response.get("content") or "")
            batch_rows = payload.get("items")
            if not isinstance(batch_rows, list) or len(batch_rows) != batch_size:
                raise ValueError(f"模型应返回 {batch_size} 道候选题，请重新生成")
            return batch_rows

        concurrency = settings.config.runtime.interview_generation_concurrency
        batches = _generation_batches(count, concurrency)
        generated_batches = await asyncio.gather(
            *(generate_batch(start_index, batch_size) for start_index, batch_size in batches)
        )
        rows = [row for batch_rows in generated_batches for row in batch_rows]
        # 并行批次按起始序号合并后仍必须与请求题量完全一致，再逐题规范化。
        if len(rows) != count:
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
