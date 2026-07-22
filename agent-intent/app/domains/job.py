"""求职领域意图识别与 slot 抽取。

第一版基于规则与正则,服务于 Boss 直聘场景。当前下游通过 agent-tool 的 boss_browser 工具复用 boss-cli 与本机浏览器 Cookie 访问 Boss:
- 抽取 city / role / experience / salary 作为岗位搜索条件
- exclude_keywords 在后端做客户端过滤
"""

import re
from typing import Any, Dict, Optional, Tuple

from ..models import IntentResult

CITY_PATTERNS = [
    "上海",
    "北京",
    "深圳",
    "广州",
    "杭州",
    "成都",
    "南京",
    "苏州",
    "武汉",
    "西安",
    "重庆",
    "天津",
    "厦门",
    "长沙",
    "合肥",
    "济南",
    "青岛",
    "郑州",
    "宁波",
    "无锡",
    "佛山",
    "东莞",
    "福州",
    "大连",
    "沈阳",
]

ROLE_HINTS = {
    "java": "Java 后端",
    "golang": "Go 后端",
    "go后端": "Go 后端",
    "go工程师": "Go 后端",
    "python": "Python",
    "前端": "前端",
    "react": "前端",
    "vue": "前端",
    "node": "Node 后端",
    "算法": "算法",
    "机器学习": "算法",
    "ai": "算法",
    "数据": "数据",
    "大数据": "数据",
    "ios": "iOS",
    "android": "Android",
    "运维": "运维",
    "sre": "运维",
    "测试": "测试",
    "qa": "测试",
    "产品": "产品",
    "后端": "后端",
}

EXCLUDE_HINTS = ["外包", "驻场", "私企", "国企", "外企"]

JOB_KEYWORDS = ["岗位", "招聘", "投递", "求职", "找工作", "职位", "薪资", "薪水", "boss", "直聘", "hr"]
PROJECT_DEEP_DIVE_KEYWORDS = ["项目深挖", "深挖项目", "项目追问", "项目面试", "项目经历", "项目经验"]
INTERVIEW_PREP_KEYWORDS = ["面试", "一面", "二面", "hr面", "终面"]
RESUME_KEYWORDS = ["简历", "履历", "cv"]
AUTH_KEYWORDS = ["登录", "扫码", "二维码", "授权", "失效", "重新登录"]
OUT_OF_SCOPE = ["自动投递", "自动打招呼", "群发", "代投", "代聊"]


_EXP_RE = re.compile(r"(\d+(?:\.\d+)?)\s*(?:年|年以上|\+\s*年|y(?:ear)?s?)", re.IGNORECASE)
_EXP_RANGE_RE = re.compile(r"(\d+)\s*[-到~]\s*(\d+)\s*年")
_SALARY_RANGE_RE = re.compile(r"(\d+)\s*[-到~]\s*(\d+)\s*[kK千]")
_SALARY_MIN_RE = re.compile(r"(\d+)\s*[kK千]\s*(?:以上|\+|起)")
_SALARY_PLAIN_RE = re.compile(r"(\d+)\s*[kK千]")
_CN_NUM = {"零": 0, "一": 1, "两": 2, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
_CN_EXP_RE = re.compile(r"([零一两二三四五六七八九十]+)\s*年")
_FRESH_KEYS = ["应届", "毕业生", "实习"]


def _extract_city(text: str) -> Optional[str]:
    for city in CITY_PATTERNS:
        if city in text:
            return city
    return None


def _extract_role(text: str) -> Optional[str]:
    lower = text.lower()
    normalized = re.sub(r"\s+", "", lower)
    for keyword, role in ROLE_HINTS.items():
        if keyword in lower or keyword.lower().replace(" ", "") in normalized:
            return role
    return None


def _extract_excludes(text: str) -> list[str]:
    hits: list[str] = []
    for token in EXCLUDE_HINTS:
        if token in text and ("不要" in text or "排除" in text or "拒绝" in text or "屏蔽" in text):
            hits.append(token)
    return hits


def _cn_to_int(cn: str) -> Optional[int]:
    if cn in _CN_NUM:
        return _CN_NUM[cn]
    if cn.endswith("十") and len(cn) == 2:
        return _CN_NUM.get(cn[0], 0) * 10
    if cn.startswith("十") and len(cn) == 2:
        return 10 + _CN_NUM.get(cn[1], 0)
    if "十" in cn and len(cn) == 3:
        return _CN_NUM.get(cn[0], 0) * 10 + _CN_NUM.get(cn[2], 0)
    return None


def _extract_experience(text: str) -> Tuple[Optional[int], Optional[str]]:
    """返回 (years 估算, Boss 枚举字符串)。"""

    if any(token in text for token in _FRESH_KEYS):
        return 0, "应届"

    range_match = _EXP_RANGE_RE.search(text)
    if range_match:
        low, high = int(range_match.group(1)), int(range_match.group(2))
        return low, _map_experience_enum((low + high) // 2)

    match = _EXP_RE.search(text)
    if match:
        years = int(float(match.group(1)))
        return years, _map_experience_enum(years)

    cn_match = _CN_EXP_RE.search(text)
    if cn_match:
        years = _cn_to_int(cn_match.group(1))
        if years is not None:
            return years, _map_experience_enum(years)

    return None, None


def _map_experience_enum(years: int) -> str:
    if years <= 0:
        return "应届"
    if years < 1:
        return "一年以内"
    if years < 3:
        return "一到三年"
    if years < 5:
        return "三到五年"
    if years < 10:
        return "五到十年"
    return "十年以上"


def _extract_salary(text: str) -> Tuple[Optional[int], Optional[int], Optional[str]]:
    """返回 (salary_min K, salary_max K, Boss 枚举字符串)。"""

    range_match = _SALARY_RANGE_RE.search(text)
    if range_match:
        low, high = int(range_match.group(1)), int(range_match.group(2))
        return low, high, _map_salary_enum(low, high)

    min_match = _SALARY_MIN_RE.search(text)
    if min_match:
        low = int(min_match.group(1))
        return low, None, _map_salary_enum(low, None)

    plain_match = _SALARY_PLAIN_RE.search(text)
    if plain_match:
        value = int(plain_match.group(1))
        return value, None, _map_salary_enum(value, None)

    return None, None, None


def _map_salary_enum(low: Optional[int], high: Optional[int]) -> Optional[str]:
    """把 K 为单位的薪资映射到 Boss 直聘枚举字符串。

    保守取低端值;最终会由 boss-cli 映射为 Boss 直聘可识别的筛选条件。
    """

    pivot = low if low is not None else (high or 0)
    if pivot <= 0:
        return None
    if pivot < 3:
        return "3k以下"
    if pivot < 5:
        return "3-5k"
    if pivot < 10:
        return "5-10k"
    if pivot < 20:
        return "10-20k"
    if pivot < 50:
        return "20-50k"
    return "50以上"


def _extract_job_slots(text: str) -> Dict[str, Any]:
    slots: Dict[str, Any] = {}
    city = _extract_city(text)
    if city:
        slots["city"] = city
    role = _extract_role(text)
    if role:
        slots["role"] = role

    years, exp_enum = _extract_experience(text)
    if years is not None:
        slots["experience_years"] = years
    if exp_enum:
        slots["boss_experience"] = exp_enum

    salary_min, salary_max, salary_enum = _extract_salary(text)
    if salary_min is not None:
        slots["salary_min_k"] = salary_min
    if salary_max is not None:
        slots["salary_max_k"] = salary_max
    if salary_enum:
        slots["boss_salary"] = salary_enum

    excludes = _extract_excludes(text)
    if excludes:
        slots["exclude_keywords"] = excludes

    return slots


def _has_any(text: str, keywords: list[str]) -> bool:
    lower = text.lower()
    return any(k.lower() in lower for k in keywords)


def _build_clarification(slots: Dict[str, Any], missing_slots: list[str]) -> IntentResult:
    enriched_slots = dict(slots)
    enriched_slots["missing_slots"] = missing_slots
    if "role" not in slots:
        enriched_slots["clarification_question"] = "你想找什么方向或岗位？例如 Java 后端、Python、前端或算法。"
    elif "city" not in slots:
        enriched_slots["clarification_question"] = "你希望在哪个城市找岗位？也可以说明远程或不限城市。"
    else:
        enriched_slots["clarification_question"] = "请补充期望经验、薪资或需要排除的岗位类型。"
    return IntentResult(
        domain="job",
        intent="job.recommend",
        confidence=0.72,
        secondary=["needs_job_filters"],
        risk="low",
        needs_clarification=True,
        next_action="ask_job_search_clarification",
        slots=enriched_slots,
    )


def classify_job(text: str) -> Optional[IntentResult]:
    """命中求职域返回 IntentResult,否则返回 None 让上层走兜底。"""

    if not text:
        return None

    if _has_any(text, OUT_OF_SCOPE):
        return IntentResult(
            domain="job",
            intent="out_of_scope",
            confidence=0.95,
            risk="high",
            needs_clarification=False,
            next_action="reject_with_reason",
            slots={"reason": "automated_outreach_not_supported"},
        )

    if _has_any(text, AUTH_KEYWORDS):
        return IntentResult(
            domain="job",
            intent="auth.login",
            confidence=0.9,
            risk="low",
            needs_clarification=False,
            next_action="trigger_boss_login",
            slots={},
        )

    project_deep_dive = (
        _has_any(text, PROJECT_DEEP_DIVE_KEYWORDS)
        or ("项目" in text and "深挖" in text)
        or ("项目" in text and "追问" in text)
    )
    if project_deep_dive and _has_any(text, ["问题", "准备", "生成", "回答", "面试", "深挖", "追问"]):
        return IntentResult(
            domain="job",
            intent="project.deep_dive",
            confidence=0.91,
            secondary=["project_questions"],
            risk="low",
            needs_clarification=False,
            next_action="generate_project_deep_dive",
            slots={},
        )

    if _has_any(text, INTERVIEW_PREP_KEYWORDS) and _has_any(
        text, ["准备", "清单", "问题", "回答", "复盘", "反问", "深挖"]
    ):
        return IntentResult(
            domain="job",
            intent="interview.prepare",
            confidence=0.9,
            secondary=["interview_plan"],
            risk="low",
            needs_clarification=False,
            next_action="build_interview_plan",
            slots={},
        )

    matched_resume = _has_any(text, RESUME_KEYWORDS)
    matched_job = _has_any(text, JOB_KEYWORDS)
    slots = _extract_job_slots(text)

    if matched_resume and (matched_job or "匹配" in text or "对比" in text or "合适" in text):
        return IntentResult(
            domain="job",
            intent="resume.match",
            confidence=0.88,
            secondary=["needs_resume", "needs_jobs"],
            risk="low",
            needs_clarification=False,
            next_action="run_resume_match",
            slots=slots,
        )

    if matched_resume:
        return IntentResult(
            domain="job",
            intent="resume.upload",
            confidence=0.82,
            risk="low",
            needs_clarification=False,
            next_action="prompt_resume_upload",
            slots=slots,
        )

    has_city_signal = "city" in slots
    has_role_signal = "role" in slots
    has_filter_signal = any(k in slots for k in ("boss_experience", "boss_salary"))
    has_search_verb = _has_any(text, ["找", "看看", "推荐", "筛选", "搜索", "有没有", "适合", "机会"])

    # 仅命中城市不足以判定为求职意图,避免把"今天上海天气怎么样"误归为 job。
    # 但“上海 Java”“找工作”“20k 以上岗位”这类短句需要进入求职域，而不是落到 open_domain。
    looks_like_job_search = (
        matched_job
        or (has_city_signal and has_role_signal)
        or (has_role_signal and (has_filter_signal or has_search_verb))
        or (has_filter_signal and (matched_job or has_search_verb))
    )

    if looks_like_job_search:
        missing_slots = []
        if not has_role_signal:
            missing_slots.append("role")
        if not has_city_signal:
            missing_slots.append("city")

        # 没有岗位方向时不直接调用 boss-cli，先澄清，避免按空关键词误搜。
        if missing_slots and not has_role_signal:
            return _build_clarification(slots, missing_slots)

        confidence = 0.9 if matched_job else 0.82
        return IntentResult(
            domain="job",
            intent="job.recommend",
            confidence=confidence,
            secondary=[k for k in ("boss_experience", "boss_salary") if k in slots],
            risk="low",
            needs_clarification=False,
            next_action="call_get_recommend_jobs",
            slots=slots,
        )

    return None
