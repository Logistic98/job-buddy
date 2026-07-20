"""简历解析与岗位匹配工具。

第一版面向 Boss 直聘求职场景:
- resume_parse: 读取本地 PDF 简历,调用 LLM 抽取为结构化对象
- resume_analyze: 基于简历原文和结构化结果,调用 LLM 输出优势、风险、内容质量、经历价值和面试深挖点
- resume_match: 给定已解析简历和候选岗位列表,调用 LLM 输出匹配度评分与差距

设计要点:
- 文件路径限制在 workspace_dir 之下,避免越权读取
- LLM 输出强制 JSON,带容错解析与字段兜底
- 简历文本和岗位标签都会被截断到合理长度,控制 prompt 体积
- llm_client 可注入,便于单测 mock
"""

import json
import re
from pathlib import Path
from typing import Any, Dict, List, Optional

from loguru import logger

from app.core.common.constants import ToolRiskLevel
from app.core.common.settings import settings
from app.core.llm.openai_client import LLMServiceError, OpenAICompatibleClient
from app.core.tool.base import BaseTool, ToolExecutionContext
from app.models.schemas import ChatMessage


MAX_RESUME_TEXT_CHARS = 9000
MAX_RESUME_PARSE_TOKENS = 3072
MAX_RESUME_ANALYSIS_TOKENS = 4096
MAX_RESUME_MATCH_TOKENS = 8192
MAX_PROFILE_SUMMARY_TOKENS = 1024
MAX_JOBS_PER_MATCH = 80

RESUME_SCORE_DIMENSIONS = {
    "content_completeness": {"label": "内容完整性", "weight": 15},
    "achievement_evidence": {"label": "成果证据", "weight": 25},
    "experience_impact": {"label": "经历影响力", "weight": 20},
    "complexity": {"label": "业务与技术复杂度", "weight": 15},
    "ownership": {"label": "个人贡献", "weight": 15},
    "consistency": {"label": "一致性与可信度", "weight": 10},
}


def _resolve_workspace_path(file_path: str, workspace_dir: str) -> Path:
    raw = Path(file_path).expanduser()
    base = Path(workspace_dir).expanduser().resolve()
    target = raw if raw.is_absolute() else (base / raw)
    target = target.resolve()
    try:
        target.relative_to(base)
    except ValueError:
        raise ValueError(f"路径越界,必须位于 workspace 下: {file_path}")
    if not target.exists():
        raise ValueError(f"文件不存在: {target}")
    if not target.is_file():
        raise ValueError(f"路径不是文件: {target}")
    return target


def _read_resume_text(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        return _extract_pdf_text(path)
    raise ValueError(f"不支持的简历格式: {suffix}; 仅支持 .pdf")


def _extract_pdf_text(path: Path) -> str:
    try:
        from pypdf import PdfReader
    except ImportError as e:
        raise RuntimeError(f"未安装 pypdf,无法解析 PDF: {e}")

    reader = PdfReader(str(path))
    chunks: List[str] = []
    for page in reader.pages:
        try:
            chunks.append(page.extract_text() or "")
        except Exception as e:
            logger.warning(f"PDF 页面提取失败：error={e}")
            chunks.append("")
    text = "\n".join(chunks).strip()
    if not text:
        raise RuntimeError("PDF 文本抽取为空,可能是扫描件或加密文件")
    return text


def _extract_json(text: str) -> Any:
    """容错解析 LLM 输出的 JSON,允许 ```json fences 包裹。"""

    if not text:
        raise ValueError("LLM 返回为空，请检查模型服务 content")
    stripped = text.strip()
    fence_match = re.search(r"```(?:json)?\s*(.+?)\s*```", stripped, re.DOTALL)
    candidate = fence_match.group(1) if fence_match else stripped
    try:
        return json.loads(candidate)
    except json.JSONDecodeError as first_error:
        object_start = candidate.find("{")
        object_end = candidate.rfind("}")
        array_start = candidate.find("[")
        array_end = candidate.rfind("]")

        if object_start >= 0 and object_end > object_start:
            try:
                return json.loads(candidate[object_start : object_end + 1])
            except json.JSONDecodeError:
                pass
        if array_start >= 0 and array_end > array_start:
            try:
                return json.loads(candidate[array_start : array_end + 1])
            except json.JSONDecodeError:
                pass

        preview = candidate[:200].replace("\n", " ")
        raise ValueError(f"LLM 输出不是完整 JSON：{first_error.msg}; preview={preview}")


def _truncate(text: str, max_chars: int) -> str:
    if len(text) <= max_chars:
        return text
    return text[:max_chars] + "\n... (内容超出截断)"


def _normalize_resume_score_breakdown(value: Any) -> tuple[int, Dict[str, Dict[str, Any]]]:
    """校验模型的分维度评分，并由程序计算最终综合分。"""

    if not isinstance(value, dict):
        raise ValueError("简历评分缺少 score_breakdown 对象")
    normalized: Dict[str, Dict[str, Any]] = {}
    weighted_total = 0.0
    for key, definition in RESUME_SCORE_DIMENSIONS.items():
        item = value.get(key)
        if not isinstance(item, dict):
            raise ValueError(f"简历评分缺少维度: {key}")
        raw_score = item.get("score")
        try:
            numeric_score = float(raw_score)
        except (TypeError, ValueError):
            raise ValueError(f"简历评分维度 {key} 的 score 不是数字")
        if numeric_score != numeric_score or numeric_score in (float("inf"), float("-inf")):
            raise ValueError(f"简历评分维度 {key} 的 score 不是有限数字")
        score = int(max(0, min(100, numeric_score)) + 0.5)
        # 对模型常见的保守打分做有限校准，保留档位差异且不突破 100 分。
        if score >= 65:
            score = min(100, score + 3)
        elif score >= 45:
            score = min(100, score + 2)
        raw_evidence = item.get("evidence")
        if isinstance(raw_evidence, list):
            evidence = "；".join(str(entry).strip() for entry in raw_evidence if str(entry).strip())
        else:
            evidence = str(raw_evidence or "").strip()
        # 没有可核验依据的维度不能进入优秀档，但保留对已呈现基础信息的基础评价。
        if not evidence and score > 70:
            score = 70
        weight = int(definition["weight"])
        normalized[key] = {
            "label": definition["label"],
            "score": score,
            "weight": weight,
            "evidence": evidence or "简历未提供可核验依据",
        }
        weighted_total += score * weight / 100
    return int(weighted_total + 0.5), normalized


class ResumeParseTool(BaseTool):
    name = "resume_parse"
    aliases = ["parse_resume"]
    search_hint = "解析 简历 PDF Markdown 求职"
    description = (
        "读取 workspace 下的 PDF 简历,使用大模型抽取为结构化对象,字段包含基本信息、技能、教育、工作经历、项目。"
    )
    input_schema = {
        "type": "object",
        "properties": {
            "file_path": {"type": "string", "description": "相对于 workspace 的简历文件路径,仅支持 .pdf"},
        },
        "required": ["file_path"],
    }
    tags = ["resume", "job"]
    timeout_seconds = 60
    risk_level = ToolRiskLevel.LOW
    read_only = True

    def __init__(self, llm_client: Optional[OpenAICompatibleClient] = None):
        self._llm_client = llm_client

    def _client(self) -> OpenAICompatibleClient:
        if self._llm_client is None:
            self._llm_client = OpenAICompatibleClient()
        return self._llm_client

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        file_path = arguments["file_path"]
        workspace = context.workspace_dir or settings.workspace_dir
        target = _resolve_workspace_path(file_path, workspace)
        raw_text = _read_resume_text(target)
        truncated = _truncate(raw_text, MAX_RESUME_TEXT_CHARS)

        messages = [
            ChatMessage(
                role="system",
                content=(
                    "你是一名严谨的简历信息抽取器。"
                    "请把下面的简历原文抽取为 JSON,字段固定如下:"
                    "name(str), contact{email,phone,city}, summary(str), years_experience(int 估算), "
                    "current_title(str), expected_titles[str], skills[str], "
                    "education[{school,degree,major,period}], "
                    "experiences[{company,title,period,highlights[str]}], "
                    "projects[{name,role,period,highlights[str],skills[str]}]。"
                    "未知字段填空字符串或空数组；仅基于原文抽取，只返回 JSON。"
                ),
            ),
            ChatMessage(role="user", content=truncated),
        ]

        try:
            response = await self._client().chat(
                messages=messages, temperature=0.0, max_tokens=MAX_RESUME_PARSE_TOKENS, disable_thinking=True
            )
        except LLMServiceError as e:
            raise RuntimeError(f"简历抽取调用 LLM 失败：{e}")

        content = response.get("content") or ""
        data = _extract_json(content)
        if not isinstance(data, dict):
            raise ValueError("LLM 输出的简历结构不是 JSON 对象")

        data.setdefault("name", "")
        data.setdefault("contact", {})
        data.setdefault("summary", "")
        data.setdefault("years_experience", 0)
        data.setdefault("current_title", "")
        data.setdefault("expected_titles", [])
        data.setdefault("skills", [])
        data.setdefault("education", [])
        data.setdefault("experiences", [])
        data.setdefault("projects", [])

        return {
            "resume": data,
            "source_path": str(target),
            "raw_text_chars": len(raw_text),
        }


class ResumeAnalyzeTool(BaseTool):
    name = "resume_analyze"
    aliases = ["analyze_resume"]
    search_hint = "简历 分析 优势 风险 问题 面试 深挖 内容质量 经历价值 量化成果"
    description = "读取 PDF 简历原文,基于大模型输出优势、风险、内容质量、经历价值和面试可能深挖点。"
    input_schema = {
        "type": "object",
        "properties": {
            "file_path": {"type": "string", "description": "相对于 workspace 的简历文件路径,仅支持 .pdf"},
            "parsed": {"type": "object", "description": "可选,已抽取的结构化简历"},
            "sections": {
                "type": "array",
                "items": {"type": "string"},
                "description": "可选,仅生成指定分析字段,用于分段流式报告",
            },
        },
        "required": ["file_path"],
    }
    tags = ["resume", "analysis", "job"]
    timeout_seconds = 90
    risk_level = ToolRiskLevel.LOW
    read_only = True

    def __init__(self, llm_client: Optional[OpenAICompatibleClient] = None):
        self._llm_client = llm_client

    def _client(self) -> OpenAICompatibleClient:
        if self._llm_client is None:
            self._llm_client = OpenAICompatibleClient()
        return self._llm_client

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        file_path = arguments["file_path"]
        workspace = context.workspace_dir or settings.workspace_dir
        target = _resolve_workspace_path(file_path, workspace)
        raw_text = _read_resume_text(target)
        truncated = _truncate(raw_text, MAX_RESUME_TEXT_CHARS)
        parsed = arguments.get("parsed") if isinstance(arguments.get("parsed"), dict) else {}
        all_sections = [
            "overall_score",
            "score_breakdown",
            "summary",
            "advantages",
            "disadvantages",
            "problems",
            "interview_deep_dive_points",
            "content_quality",
            "experience_value",
            "action_items",
        ]
        requested = [str(x) for x in (arguments.get("sections") or []) if str(x) in all_sections]
        sections = requested or all_sections
        model_sections = [key for key in sections if key != "overall_score"]
        if "overall_score" in sections and "score_breakdown" not in model_sections:
            model_sections.insert(0, "score_breakdown")
        score_rubric = (
            "评分维度与权重固定为: content_completeness 内容完整性15%，achievement_evidence 成果证据25%，"
            "experience_impact 经历影响力20%，complexity 业务与技术复杂度15%，ownership 个人贡献15%，"
            "consistency 一致性与可信度10%。每个维度输出 {score,evidence}，score 为0-100整数，evidence 必须引用简历事实。"
            "统一档位锚点: 95-100 表示证据非常充分且竞争力突出；85-94 表示内容扎实、证据较充分，属于常规优秀简历；"
            "75-84 表示整体良好但仍有局部证据或影响描述缺口；65-74 表示核心信息可用但说服力有限；"
            "45-64 表示关键内容明显缺失；0-44 表示几乎无有效证据或存在严重矛盾。"
            "评分应衡量简历当前呈现质量，以正常可投递简历为基准，不要以完美简历或理想候选人为默认基准；"
            "简历已清楚呈现职责、项目和成果时应积极使用85分以上档位，但不得因为工作年限、名企名称或技能词数量直接给高分。"
            "完全缺少量化或可验证成果时 achievement_evidence 不得高于74，个人职责与团队成果无法区分时 ownership 不得高于74，"
            "存在明确时间或技能矛盾时 consistency 不得高于70。"
            "不要输出 overall_score，最终综合分由系统按上述权重计算。"
        )

        messages = [
            ChatMessage(
                role="system",
                content=(
                    "你是一名资深技术招聘面试官和简历内容诊断专家。请严格基于简历原文和结构化信息做分析。"
                    "输出 JSON 对象,字段固定如下:"
                    "score_breakdown{content_completeness:{score,evidence},achievement_evidence:{score,evidence},"
                    "experience_impact:{score,evidence},complexity:{score,evidence},ownership:{score,evidence},"
                    "consistency:{score,evidence}}, summary(str 总体判断), advantages[{title,detail,evidence}], "
                    "disadvantages[{title,detail,evidence}], problems[{type,detail,suggestion}], "
                    "interview_deep_dive_points[{topic,question,reason,preparation}], "
                    "content_quality[{title,detail,evidence,suggestion}], "
                    "experience_value[{title,detail,evidence}], action_items[str]。"
                    "重点分析: 1优势和竞争力; 2短板和风险; 3内容是否完整、具体且有说服力,职责、行动、成果是否形成闭环,"
                    "是否有量化结果和事实证据; 4经历的业务或技术复杂度、候选人个人贡献、实际影响和可迁移能力; "
                    "5经历与技能表述是否自洽、是否存在真实性风险; 6面试官可能深挖的问题。"
                    "不得分析排版、字体、错别字、术语写法或标点。不得编造简历中不存在的成果、数据和职责。"
                    "证据不足时应明确指出缺失了什么信息,不要将推测写成事实。"
                    f"{score_rubric}"
                    "每类结果保持精炼：优势最多3项、劣势最多3项、问题最多4项、面试深挖点最多4项、"
                    "内容质量最多4项、经历价值最多4项、行动建议最多5项。"
                    f"本次只生成这些字段: {', '.join(model_sections)}。除指定字段外不要输出其他分析字段。只返回 JSON。"
                ),
            ),
            ChatMessage(role="user", content=json.dumps({"parsed": parsed, "raw_text": truncated}, ensure_ascii=False)),
        ]
        try:
            response = await self._client().chat(
                messages=messages, temperature=0.1, max_tokens=MAX_RESUME_ANALYSIS_TOKENS, disable_thinking=True
            )
        except LLMServiceError as e:
            raise RuntimeError(f"简历分析调用 LLM 失败：{e}")
        data = _extract_json(response.get("content") or "")
        if not isinstance(data, dict):
            raise ValueError("LLM 输出的简历分析不是 JSON 对象")
        list_fields = [
            "advantages",
            "disadvantages",
            "problems",
            "interview_deep_dive_points",
            "content_quality",
            "experience_value",
            "action_items",
        ]
        filtered = {key: data.get(key) for key in model_sections if key in data}
        for key in model_sections:
            if key in list_fields:
                filtered.setdefault(key, [])
            elif key == "summary":
                filtered.setdefault(key, "")
        if "score_breakdown" in model_sections:
            overall_score, score_breakdown = _normalize_resume_score_breakdown(data.get("score_breakdown"))
            filtered["score_breakdown"] = score_breakdown
            if "overall_score" in sections:
                filtered["overall_score"] = overall_score
        data = filtered
        data["raw_text_chars"] = len(raw_text)
        return {"analysis": data, "source_path": str(target)}


class JobProfileSummaryTool(BaseTool):
    name = "job_profile_summary"
    aliases = ["generate_job_profile_summary", "profile_summary"]
    search_hint = "求职画像 摘要 AI 生成"
    description = "基于求职画像结构化信息,使用大模型生成用于岗位推荐、匹配和问答上下文的简洁画像摘要。"
    input_schema = {
        "type": "object",
        "properties": {
            "profile": {"type": "object", "description": "求职画像结构化对象"},
        },
        "required": ["profile"],
    }
    tags = ["resume", "job", "profile"]
    timeout_seconds = 45
    risk_level = ToolRiskLevel.LOW
    read_only = True

    def __init__(self, llm_client: Optional[OpenAICompatibleClient] = None):
        self._llm_client = llm_client

    def _client(self) -> OpenAICompatibleClient:
        if self._llm_client is None:
            self._llm_client = OpenAICompatibleClient()
        return self._llm_client

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        profile = arguments.get("profile")
        if not isinstance(profile, dict):
            raise ValueError("profile 参数必须是对象")
        compact = self._compact_profile(profile)
        messages = [
            ChatMessage(
                role="system",
                content=(
                    "你是一名资深技术招聘顾问。请基于候选人的求职画像生成高质量中文画像摘要。"
                    "目标: 摘要将直接用于岗位推荐、岗位匹配和问答上下文，必须帮助系统快速判断候选人的目标方向、能力边界和筛选偏好。"
                    "硬性要求: 1 只基于输入信息，不编造公司、职级、成果或薪资；2 输出自然的一段话，不要项目符号、编号、Markdown、换行或 JSON 原文痕迹；"
                    "3 长度控制在120-180个中文字符；4 优先包含: 工作年限、当前/目标方向、核心技术栈、最有代表性的项目/平台经历、管理或领域经验、期望城市/岗位/薪资、硬性排除项；"
                    "5 使用完整主谓结构和自然衔接，工作年限必须保留明确数字或范围；按'经验与方向—代表经历与能力—求职偏好'组织内容，每部分最多列举两三个高价值信息；"
                    "6 不要堆砌技术名词或所有字段，不要重复姓名，避免'核心技能：'这类字段拼接口吻；7 信息不足时写清已知事实，并把缺失项放入 missing_fields。"
                    "输出严格 JSON 对象,字段为 summary(str), highlights(str数组,最多5项), missing_fields(str数组)。"
                ),
            ),
            ChatMessage(role="user", content=json.dumps(compact, ensure_ascii=False)),
        ]
        try:
            response = await self._client().chat(
                messages=messages, temperature=0.1, max_tokens=MAX_PROFILE_SUMMARY_TOKENS, disable_thinking=True
            )
        except LLMServiceError as e:
            raise RuntimeError(f"画像摘要调用 LLM 失败：{e}")
        data = _extract_json(response.get("content") or "")
        if not isinstance(data, dict):
            raise ValueError("LLM 输出的画像摘要不是 JSON 对象")
        summary = self._clean_summary(str(data.get("summary") or ""))
        if not summary:
            raise ValueError("LLM 输出的画像摘要为空")
        return {
            "summary": summary,
            "highlights": [str(x) for x in (data.get("highlights") or [])][:5],
            "missing_fields": [str(x) for x in (data.get("missing_fields") or [])][:8],
        }

    @staticmethod
    def _clean_summary(value: str) -> str:
        text = re.sub(r"```(?:json)?|```", "", value or "")
        text = re.sub(r"^\s*(?:(?:[-•*]+)|(?:\d+[.、]))\s*", "", text.strip())
        text = re.sub(r"[\r\n]+", " ", text)
        text = re.sub(r"(?<!\d)\s*[-•]\s*(?!\d)", "；", text)
        text = re.sub(r"\s+", " ", text).strip(" ，。；")
        return text + ("。" if text and not re.search(r"[。！？]$", text) else "")

    @staticmethod
    def _compact_profile(profile: Dict[str, Any]) -> Dict[str, Any]:
        basic = profile.get("basic_info") if isinstance(profile.get("basic_info"), dict) else {}
        expectations = profile.get("expectations") or profile.get("job_expectations") or {}
        if not isinstance(expectations, dict):
            expectations = {}
        status = profile.get("status") or profile.get("job_status") or {}
        if not isinstance(status, dict):
            status = {}
        experiences = profile.get("experiences") or profile.get("work_experiences") or []
        projects = profile.get("projects") or profile.get("project_experiences") or []
        return {
            "name": profile.get("name") or basic.get("name") or "",
            "city": basic.get("city") or expectations.get("city") or "",
            "degree": basic.get("degree") or basic.get("education") or "",
            "current_title": profile.get("current_title")
            or basic.get("currentTitle")
            or basic.get("current_title")
            or "",
            "years_experience": profile.get("years_experience")
            or basic.get("workYears")
            or basic.get("work_years")
            or "",
            "expected_titles": profile.get("expected_titles") or expectations.get("position") or [],
            "skills": (profile.get("skills") or [])[:35]
            if isinstance(profile.get("skills"), list)
            else str(profile.get("skills") or "")[:500],
            "personal_advantage": str(profile.get("personal_advantage") or profile.get("personalAdvantage") or "")[
                :1200
            ],
            "job_status": status,
            "job_expectations": expectations,
            "education_experiences": (profile.get("education_experiences") or profile.get("education") or [])[:3],
            "work_experiences": experiences[:5] if isinstance(experiences, list) else str(experiences)[:1200],
            "project_experiences": projects[:5] if isinstance(projects, list) else str(projects)[:1200],
            "job_intentions": str(profile.get("job_intentions") or "")[:500],
        }


class ResumeMatchTool(BaseTool):
    name = "resume_match"
    aliases = ["match_resume_jobs"]
    search_hint = "简历 岗位 匹配 评分 推荐"
    description = (
        "给定已解析的简历对象和一组岗位,调用大模型对每个岗位输出 0-100 的匹配度评分、命中点、缺口和改进建议。"
        "岗位输入字段对齐 Boss 直聘 get_recommend_jobs_tool 返回:jobName, salaryDesc, jobLabels, skills, jobExperience, cityName, brandName, industry, securityId。"
    )
    input_schema = {
        "type": "object",
        "properties": {
            "resume": {"type": "object", "description": "已解析的结构化简历,通常由 resume_parse 工具产出"},
            "jobs": {
                "type": "array",
                "description": "候选岗位列表,每项至少包含 jobName/skills,推荐附带 securityId 作为 id",
                "items": {"type": "object"},
            },
            "top_k": {"type": "integer", "description": "只对前 N 个岗位评分,默认 10", "default": MAX_JOBS_PER_MATCH},
            "sections": {
                "type": "array",
                "items": {"type": "string"},
                "description": "可选,仅生成指定 match 字段,用于分段流式报告",
            },
        },
        "required": ["resume", "jobs"],
    }
    tags = ["resume", "job", "match"]
    timeout_seconds = 60
    risk_level = ToolRiskLevel.LOW
    read_only = True

    def __init__(self, llm_client: Optional[OpenAICompatibleClient] = None):
        self._llm_client = llm_client

    def _client(self) -> OpenAICompatibleClient:
        if self._llm_client is None:
            self._llm_client = OpenAICompatibleClient()
        return self._llm_client

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        resume = arguments.get("resume")
        jobs = arguments.get("jobs") or []
        if not isinstance(resume, dict):
            raise ValueError("resume 参数必须是对象")
        if not isinstance(jobs, list) or not jobs:
            raise ValueError("jobs 参数必须是非空数组")

        top_k = int(arguments.get("top_k") or MAX_JOBS_PER_MATCH)
        if top_k <= 0:
            raise ValueError("top_k 必须为正整数")
        scoped_jobs = jobs[: min(top_k, MAX_JOBS_PER_MATCH)]
        all_sections = [
            "score",
            "score_confidence",
            "recommendation",
            "reasoning",
            "dimensions",
            "evidence",
            "hits",
            "gaps",
            "risks",
            "interview_focus",
            "improvement_actions",
            "limitations",
        ]
        requested = [str(x) for x in (arguments.get("sections") or []) if str(x) in all_sections]
        sections = requested or all_sections

        resume_brief = self._compact_resume(resume)
        jobs_brief = [self._compact_job(idx, job) for idx, job in enumerate(scoped_jobs)]

        messages = [
            ChatMessage(
                role="system",
                content=(
                    "你是一名资深技术招聘评估专家。下面会给你一份候选人简历摘要和若干岗位摘要。"
                    "必须严格基于输入证据评估,不得编造经历、岗位要求或公司信息；如果岗位描述不足,必须降低置信度并写入 limitations。"
                    "输出严格 JSON 对象,字段为: evaluation_schema, matches, limitations。matches 为数组,顺序与输入岗位一致。"
                    "每个 match 字段固定如下: id(string), score(0-100整数或 null), score_confidence(high|medium|low), "
                    "recommendation(enum: 推荐|可尝试|谨慎|不建议|证据不足), reasoning(str), "
                    "dimensions{technical_skill{score,evidence,gap}, seniority{score,evidence,gap}, project_relevance{score,evidence,gap}, domain_fit{score,evidence,gap}, constraints{score,evidence,gap}}, "
                    "evidence[{resume_evidence,job_requirement,assessment}], hits[str], gaps[str], risks[str], interview_focus[str], improvement_actions[str], limitations[str]。"
                    "reasoning 必须用 2-3 句话直接回答是否值得投递、核心依据和最大阻碍,不得只复述岗位名称或技能词。"
                    "hits 和 gaps 必须引用当前简历与当前 JD 的具体内容；risks 只写可能影响筛选、定级或录用的关键风险。"
                    "improvement_actions 必须是投递前可执行动作,优先说明简历哪段应补充什么项目证据、指标或技能验证；禁止使用继续学习、加强能力等空话。"
                    "interview_focus 必须结合当前 JD 与简历经历给出具体追问方向或准备问题。"
                    "评分规则: 只有岗位要求和简历证据都明确时才给高分；证据不足不得给 80 以上；只有岗位名称没有 JD 时 score_confidence 必须 low, recommendation 必须证据不足。"
                    f"本次每个 match 只生成 id 和这些字段: {', '.join(sections)}。除指定字段外不要输出其他 match 字段。只返回 JSON。"
                ),
            ),
            ChatMessage(
                role="user",
                content=json.dumps({"resume": resume_brief, "jobs": jobs_brief}, ensure_ascii=False),
            ),
        ]

        try:
            response = await self._client().chat(
                messages=messages,
                temperature=0.1,
                max_tokens=MAX_RESUME_MATCH_TOKENS,
                disable_thinking=True,
            )
        except LLMServiceError as e:
            raise RuntimeError(f"岗位匹配调用 LLM 失败：{e}")

        content = response.get("content") or ""
        data = _extract_json(content)
        if not isinstance(data, dict):
            raise ValueError("LLM 输出的匹配结果不是 JSON 对象")
        payload: Dict[str, Any] = data
        rows = payload.get("matches") if isinstance(payload.get("matches"), list) else []
        if not rows:
            raise ValueError("大模型未返回有效的岗位匹配结果，请重试")
        normalized = [self._normalize_match(item, idx, scoped_jobs) for idx, item in enumerate(rows)]
        if requested:
            normalized = [{key: row.get(key) for key in ["id", *sections] if key in row} for row in normalized]
        else:
            normalized.sort(key=lambda x: -1 if x.get("score") is None else int(x.get("score") or 0), reverse=True)
        return {
            "matches": normalized,
            "scored_count": len(normalized),
            "total_jobs": len(jobs),
            "evaluation_schema": payload.get("evaluation_schema") or "evidence_based_resume_job_match_v2",
            "limitations": [str(x) for x in (payload.get("limitations") or [])][:10],
            "evidence_policy": "no_fabrication_score_requires_resume_and_job_evidence",
        }

    @staticmethod
    def _compact_resume(resume: Dict[str, Any]) -> Dict[str, Any]:
        skills = resume.get("skills") or []
        experiences = resume.get("experiences") or resume.get("work_experiences") or []
        projects = resume.get("projects") or resume.get("project_experiences") or []

        def compact_records(records: Any, field_groups: Dict[str, tuple[str, ...]], limit: int) -> List[Any]:
            if not isinstance(records, list):
                return [str(records)[:1200]] if records else []
            compacted: List[Any] = []
            for record in records[:limit]:
                if not isinstance(record, dict):
                    compacted.append(str(record)[:500])
                    continue
                item: Dict[str, Any] = {}
                for target, candidates in field_groups.items():
                    value = next((record.get(key) for key in candidates if record.get(key)), None)
                    if value is not None:
                        item[target] = value[:1200] if isinstance(value, str) else value
                if item:
                    compacted.append(item)
            return compacted

        work_details = compact_records(
            experiences,
            {
                "company": ("company", "companyName"),
                "title": ("title", "position", "positionName", "role"),
                "description": ("description", "content", "responsibility", "workContent"),
                "achievement": ("achievement", "achievements", "result"),
            },
            5,
        )
        project_details = compact_records(
            projects,
            {
                "name": ("name", "projectName", "title"),
                "role": ("role", "position"),
                "skills": ("skills", "techStack", "tech_stack"),
                "responsibility": ("responsibility", "description", "content"),
                "achievement": ("achievement", "achievements", "result"),
            },
            5,
        )
        return {
            "summary": str(resume.get("summary") or resume.get("personal_advantage") or "")[:800],
            "years_experience": resume.get("years_experience") or resume.get("work_years") or 0,
            "current_title": resume.get("current_title") or "",
            "expected_titles": resume.get("expected_titles") or [],
            "skills": skills[:30] if isinstance(skills, list) else str(skills)[:800],
            "work_experiences": work_details,
            "project_experiences": project_details,
        }

    @staticmethod
    def _compact_job(idx: int, job: Dict[str, Any]) -> Dict[str, Any]:
        job_id = job.get("securityId") or job.get("id") or job.get("jobId") or job.get("encryptJobId") or f"job_{idx}"
        return {
            "id": str(job_id),
            "jobName": job.get("jobName", ""),
            "salaryDesc": job.get("salaryDesc", ""),
            "cityName": job.get("cityName", ""),
            "jobExperience": job.get("jobExperience", ""),
            "brandName": job.get("brandName", ""),
            "industry": job.get("industry", ""),
            "jobLabels": (job.get("jobLabels") or [])[:8],
            "skills": (job.get("skills") or [])[:15],
            "jobDescription": str(
                job.get("jobDescription") or job.get("description") or job.get("postDescription") or ""
            )[:1800],
            "source": job.get("source", ""),
        }

    @staticmethod
    def _normalize_match(item: Any, idx: int, jobs: List[Dict[str, Any]]) -> Dict[str, Any]:
        item = item if isinstance(item, dict) else {}
        fallback_id = (
            jobs[idx].get("securityId")
            or jobs[idx].get("id")
            or jobs[idx].get("jobId")
            or jobs[idx].get("encryptJobId")
            or f"job_{idx}"
        )
        score_raw = item.get("score")
        score: Optional[int]
        try:
            score = int(score_raw) if score_raw is not None and str(score_raw).strip() != "" else None
        except (TypeError, ValueError):
            score = None
        if score is not None:
            score = max(0, min(100, score))
        evidence = item.get("evidence") if isinstance(item.get("evidence"), list) else []
        dimensions = item.get("dimensions") if isinstance(item.get("dimensions"), dict) else {}
        limitations = [str(x) for x in (item.get("limitations") or [])][:8]
        confidence = str(item.get("score_confidence") or item.get("confidence") or "").lower()
        if confidence not in {"high", "medium", "low"}:
            confidence = "medium" if evidence else "low"
        if score is not None and score >= 80 and not evidence:
            score = min(score, 70)
            confidence = "low"
            limitations.append("缺少逐条证据支撑,高分已下调。")
        return {
            "id": str(item.get("id") or fallback_id),
            "score": score,
            "score_confidence": confidence,
            "recommendation": str(item.get("recommendation") or ("证据不足" if confidence == "low" else "")),
            "reasoning": str(item.get("reasoning") or ""),
            "dimensions": dimensions,
            "evidence": evidence[:12],
            "hits": [str(x) for x in (item.get("hits") or [])][:8],
            "gaps": [str(x) for x in (item.get("gaps") or [])][:8],
            "risks": [str(x) for x in (item.get("risks") or [])][:8],
            "interview_focus": [str(x) for x in (item.get("interview_focus") or [])][:8],
            "improvement_actions": [str(x) for x in (item.get("improvement_actions") or [])][:8],
            "limitations": limitations,
        }
