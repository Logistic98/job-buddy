
"""简历解析与岗位匹配工具。

第一版面向 Boss 直聘求职场景:
- resume_parse: 读取本地 PDF 简历,调用 LLM 抽取为结构化对象
- resume_analyze: 基于简历原文和结构化结果,调用 LLM 输出优势、劣势、问题、深挖点、排版和错别字
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


MAX_RESUME_TEXT_CHARS = 12000
MAX_RESUME_PARSE_TOKENS = 4096
MAX_RESUME_ANALYSIS_TOKENS = 8192
MAX_RESUME_MATCH_TOKENS = 8192
MAX_PROFILE_SUMMARY_TOKENS = 1024
MAX_JOBS_PER_MATCH = 80


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


class ResumeParseTool(BaseTool):
    name = "resume_parse"
    aliases = ["parse_resume"]
    search_hint = "解析 简历 PDF Markdown 求职"
    description = "读取 workspace 下的 PDF 简历,使用大模型抽取为结构化对象,字段包含基本信息、技能、教育、工作经历、项目。"
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
            response = await self._client().chat(messages=messages, temperature=0.0, max_tokens=MAX_RESUME_PARSE_TOKENS)
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
    search_hint = "简历 分析 优势 劣势 问题 面试 深挖 排版 错别字"
    description = "读取 PDF 简历原文,基于大模型输出优势、劣势、问题、面试可能深挖点、排版问题和错别字。"
    input_schema = {
        "type": "object",
        "properties": {
            "file_path": {"type": "string", "description": "相对于 workspace 的简历文件路径,仅支持 .pdf"},
            "parsed": {"type": "object", "description": "可选,已抽取的结构化简历"},
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

        messages = [
            ChatMessage(
                role="system",
                content=(
                    "你是一名资深技术招聘面试官和简历诊断专家。请严格基于简历原文和结构化信息做分析。"
                    "输出 JSON 对象,字段固定如下:"
                    "overall_score(0-100整数), summary(str 总体判断), advantages[{title,detail,evidence}], "
                    "disadvantages[{title,detail,evidence}], problems[{type,detail,suggestion}], "
                    "interview_deep_dive_points[{topic,question,reason,preparation}], "
                    "layout_issues[{detail,suggestion}], typo_issues[{text,suggestion,context}], "
                    "action_items[str]。"
                    "重点检查: 1优势和竞争力; 2短板和风险; 3表达、逻辑、经历真实性问题; "
                    "4面试官可能深挖的问题; 5排版是否混乱、层级是否清楚; 6错别字、术语错误、标点和中英文混排问题。"
                    "如果未发现排版或错别字问题,对应数组为空。只返回 JSON。"
                ),
            ),
            ChatMessage(role="user", content=json.dumps({"parsed": parsed, "raw_text": truncated}, ensure_ascii=False)),
        ]
        try:
            response = await self._client().chat(messages=messages, temperature=0.1, max_tokens=MAX_RESUME_ANALYSIS_TOKENS)
        except LLMServiceError as e:
            raise RuntimeError(f"简历分析调用 LLM 失败：{e}")
        data = _extract_json(response.get("content") or "")
        if not isinstance(data, dict):
            raise ValueError("LLM 输出的简历分析不是 JSON 对象")
        for key in ["advantages", "disadvantages", "problems", "interview_deep_dive_points", "layout_issues", "typo_issues", "action_items"]:
            data.setdefault(key, [])
        data.setdefault("summary", "")
        data.setdefault("overall_score", 0)
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
                    "5 不要堆砌所有字段，不要重复姓名，避免'核心技能：'这类字段拼接口吻；6 信息不足时写清已知事实，并把缺失项放入 missing_fields。"
                    "输出严格 JSON 对象,字段为 summary(str), highlights(str数组,最多5项), missing_fields(str数组)。"
                ),
            ),
            ChatMessage(role="user", content=json.dumps(compact, ensure_ascii=False)),
        ]
        try:
            response = await self._client().chat(messages=messages, temperature=0.1, max_tokens=MAX_PROFILE_SUMMARY_TOKENS)
        except LLMServiceError as e:
            raise RuntimeError(f"画像摘要调用 LLM 失败：{e}")
        data = _extract_json(response.get("content") or "")
        if not isinstance(data, dict):
            raise ValueError("LLM 输出的画像摘要不是 JSON 对象")
        summary = self._clean_summary(str(data.get("summary") or ""))
        if not summary:
            raise ValueError("LLM 输出的画像摘要为空")
        return {
            "summary": summary[:220],
            "highlights": [str(x) for x in (data.get("highlights") or [])][:5],
            "missing_fields": [str(x) for x in (data.get("missing_fields") or [])][:8],
        }

    @staticmethod
    def _clean_summary(value: str) -> str:
        text = re.sub(r"```(?:json)?|```", "", value or "")
        text = re.sub(r"^[\s\-•*\d.、]+", "", text.strip())
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
            "current_title": profile.get("current_title") or basic.get("currentTitle") or basic.get("current_title") or "",
            "years_experience": profile.get("years_experience") or basic.get("workYears") or basic.get("work_years") or "",
            "expected_titles": profile.get("expected_titles") or expectations.get("position") or [],
            "skills": (profile.get("skills") or [])[:35] if isinstance(profile.get("skills"), list) else str(profile.get("skills") or "")[:500],
            "personal_advantage": str(profile.get("personal_advantage") or profile.get("personalAdvantage") or "")[:1200],
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
                    "评分规则: 只有岗位要求和简历证据都明确时才给高分；证据不足不得给 80 以上；只有岗位名称没有 JD 时 score_confidence 必须 low, recommendation 必须证据不足。"
                    "只返回 JSON。"
                ),
            ),
            ChatMessage(
                role="user",
                content=json.dumps({"resume": resume_brief, "jobs": jobs_brief}, ensure_ascii=False),
            ),
        ]

        try:
            response = await self._client().chat(messages=messages, temperature=0.1, max_tokens=MAX_RESUME_MATCH_TOKENS)
        except LLMServiceError as e:
            raise RuntimeError(f"岗位匹配调用 LLM 失败：{e}")

        content = response.get("content") or ""
        data = _extract_json(content)
        if isinstance(data, list):
            payload: Dict[str, Any] = {"matches": data, "limitations": ["legacy_array_output"]}
        elif isinstance(data, dict):
            payload = data
        else:
            raise ValueError("LLM 输出的匹配结果不是 JSON 对象或数组")
        rows = payload.get("matches") if isinstance(payload.get("matches"), list) else []
        normalized = [self._normalize_match(item, idx, scoped_jobs) for idx, item in enumerate(rows)]
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
        experiences = resume.get("experiences") or []
        projects = resume.get("projects") or []
        return {
            "summary": (resume.get("summary") or "")[:400],
            "years_experience": resume.get("years_experience") or 0,
            "current_title": resume.get("current_title") or "",
            "expected_titles": resume.get("expected_titles") or [],
            "skills": skills[:30],
            "experience_titles": [item.get("title", "") for item in experiences][:6],
            "project_skills": list({s for proj in projects for s in (proj.get("skills") or [])})[:30],
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
            "jobDescription": str(job.get("jobDescription") or job.get("description") or job.get("postDescription") or "")[:1800],
            "source": job.get("source", ""),
        }

    @staticmethod
    def _normalize_match(item: Any, idx: int, jobs: List[Dict[str, Any]]) -> Dict[str, Any]:
        item = item if isinstance(item, dict) else {}
        fallback_id = jobs[idx].get("securityId") or jobs[idx].get("id") or jobs[idx].get("jobId") or jobs[idx].get("encryptJobId") or f"job_{idx}"
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
