"""从 Boss API JSON 中提取岗位列表与岗位详情。

Boss 返回结构通常形如 {code, message, zpData:{jobList:[...]}}、boss-cli 的 data
载荷，或嵌套在 data/result 下。这里做鲁棒提取，兼容字段变体；岗位详情归一化为下游统一字段。
"""

from __future__ import annotations

import re
from typing import Any, Optional

from loguru import logger

_LIST_KEYS = ("jobList", "job_list", "cardList", "card_list", "jobs", "items", "list", "results")
_CONTAINER_KEYS = ("zpData", "data", "result")

# Boss 前端字段会随页面版本漂移。列表项有时是扁平结构，有时把岗位、公司、Boss 信息
# 分别塞进 jobInfo / brandInfo / bossInfo。这里只做“缺省补齐”而不是破坏原始字段，
# 下游仍能拿到原始 payload 中的所有键。
_JOB_FIELD_CANDIDATES: dict[str, tuple[str, ...]] = {
    "securityId": ("securityId", "security_id"),
    "encryptJobId": ("encryptJobId", "encrypt_job_id", "jobEncryptId", "job_encrypt_id"),
    "jobId": ("jobId", "job_id", "id"),
    "lid": ("lid", "listId", "list_id"),
    "jobName": ("jobName", "job_name", "positionName", "position_name", "title", "name"),
    "brandName": ("brandName", "brand_name", "companyName", "company_name", "company", "brand"),
    "salaryDesc": (
        "salaryDesc",
        "salary_desc",
        "salary",
        "salaryText",
        "salary_text",
        "salaryName",
        "salary_name",
        "salaryRange",
        "salary_range",
        "jobSalary",
        "job_salary",
        "pay",
        "wage",
        "compensation",
    ),
    "cityName": ("cityName", "city_name", "city", "workCity", "work_city", "location"),
    "areaDistrict": ("areaDistrict", "area_district", "district", "area"),
    "jobExperience": ("jobExperience", "job_experience", "experience", "experienceName", "experience_name"),
    "jobDegree": ("jobDegree", "job_degree", "degree", "education", "degreeName", "degree_name"),
    "brandIndustry": ("brandIndustry", "brand_industry", "companyIndustry", "industry", "industryName"),
    "brandScaleName": ("brandScaleName", "brand_scale_name", "companyScale", "scaleName", "brandScale"),
    "brandStageName": ("brandStageName", "brand_stage_name", "companyStage", "financeStage", "stageName"),
    "bossName": ("bossName", "boss_name", "boss", "recruiterName", "recruiter_name"),
    "bossTitle": ("bossTitle", "boss_title", "bossPosition", "positionTitle", "recruiterTitle"),
    "skills": ("skills", "skillList", "skill_list", "skillLabels"),
    "jobLabels": ("jobLabels", "job_labels", "labels", "tagList", "tag_list"),
    "welfareList": ("welfareList", "welfare_list", "welfare", "benefits"),
    "jobDescription": (
        "jobDescription",
        "postDescription",
        "jobDesc",
        "jobSecText",
        "jobContent",
        "positionDescription",
    ),
    "jobUrl": ("jobUrl", "job_url", "url", "href", "link", "detailUrl", "jobDetailUrl"),
}

_LOW_SALARY_KEYS = (
    "lowSalary",
    "low_salary",
    "salaryLow",
    "salary_low",
    "minSalary",
    "min_salary",
    "salaryMin",
    "salary_min",
)
_HIGH_SALARY_KEYS = (
    "highSalary",
    "high_salary",
    "salaryHigh",
    "salary_high",
    "maxSalary",
    "max_salary",
    "salaryMax",
    "salary_max",
)


def extract_jobs(payload: Any) -> list[dict]:
    if payload is None:
        return []
    found = _find_job_list(payload, depth=0) or []
    jobs = [_normalize_job(item) for item in found]
    # 自诊断：首项缺薪资字段时打出真实键名，便于定位 Boss 端薪资字段漂移；字段正常则不打。
    if jobs and not any(jobs[0].get(k) for k in ("salaryDesc", "salary")):
        logger.warning(f"岗位项缺薪资字段，首项可用键：{sorted(jobs[0].keys())}")
    return jobs


def _find_job_list(node: Any, depth: int) -> Optional[list[dict]]:
    if depth > 6:
        return None
    if isinstance(node, list):
        if node and all(isinstance(item, dict) for item in node):
            return node
        return None
    if isinstance(node, dict):
        for key in _LIST_KEYS:
            value = node.get(key)
            if isinstance(value, list) and value and all(isinstance(i, dict) for i in value):
                page_lid = node.get("lid") or node.get("listId")
                if page_lid:
                    for row in value:
                        row.setdefault("lid", page_lid)
                return value
        for key in _CONTAINER_KEYS:
            if key in node:
                found = _find_job_list(node[key], depth + 1)
                if found:
                    return found
        for value in node.values():
            found = _find_job_list(value, depth + 1)
            if found:
                return found
    return None


def _container(payload: Any) -> dict:
    if not isinstance(payload, dict):
        return {}
    for key in _CONTAINER_KEYS:
        value = payload.get(key)
        if isinstance(value, dict):
            return value
    return payload


def _deep_first(node: Any, keys: tuple[str, ...], depth: int = 0, max_depth: int = 6) -> Any:
    if depth > max_depth or node is None:
        return None
    if isinstance(node, dict):
        for key in keys:
            value = node.get(key)
            if value not in (None, "", [], {}):
                return value
        for value in node.values():
            found = _deep_first(value, keys, depth + 1, max_depth=max_depth)
            if found not in (None, "", [], {}):
                return found
    elif isinstance(node, list):
        for item in node:
            found = _deep_first(item, keys, depth + 1, max_depth=max_depth)
            if found not in (None, "", [], {}):
                return found
    return None


def _normalize_job(row: dict) -> dict:
    result = dict(row)
    for canonical, candidates in _JOB_FIELD_CANDIDATES.items():
        if _has_value(result.get(canonical)):
            continue
        value = _deep_first(row, candidates, max_depth=4)
        if canonical == "salaryDesc" and not _has_value(value):
            value = _format_salary_from_bounds(row)
        cleaned = _clean_value(value)
        if _has_value(cleaned):
            result[canonical] = cleaned
    return result


def _has_value(value: Any) -> bool:
    return value not in (None, "", [], {})


def _clean_value(value: Any) -> Any:
    if isinstance(value, str):
        return re.sub(r"\s+", " ", value).strip()
    if isinstance(value, list):
        return [_clean_value(item) for item in value if _has_value(_clean_value(item))]
    return value


def _salary_number(value: Any) -> Optional[float]:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        number = float(value)
    else:
        match = re.search(r"\d+(?:\.\d+)?", str(value))
        if not match:
            return None
        number = float(match.group(0))
    if number <= 0:
        return None
    # 有些接口给的是元/月，有些接口给的是 K/月。大于 1000 的按元换算为 K。
    if number >= 1000:
        number = number / 1000.0
    return number


def _format_salary_number(value: float) -> str:
    if abs(value - int(value)) < 0.001:
        return str(int(value))
    return ("%.1f" % value).rstrip("0").rstrip(".")


def _format_salary_from_bounds(node: Any) -> str:
    low = _salary_number(_deep_first(node, _LOW_SALARY_KEYS, max_depth=4))
    high = _salary_number(_deep_first(node, _HIGH_SALARY_KEYS, max_depth=4))
    if low is None and high is None:
        return ""
    if low is not None and high is not None:
        return f"{_format_salary_number(low)}-{_format_salary_number(high)}K"
    if low is not None:
        return f"{_format_salary_number(low)}K以上"
    return f"{_format_salary_number(high)}K以内"


def normalize_detail(payload: Any) -> dict:
    """归一化岗位详情，输出下游统一字段，并保留原始 payload 便于调优。"""
    container = _container(payload)
    detail: dict[str, Any] = {}

    description = _deep_first(
        container,
        (
            "postDescription",
            "jobDescription",
            "description",
            "jobDesc",
            "detailText",
            "jobSecText",
            "jobContent",
            "positionDescription",
            "htmlDescription",
        ),
    )
    if description:
        detail["jobDescription"] = description

    mapping = {
        "welfareList": ("welfareList", "welfare", "benefits"),
        "skillList": ("skillList", "skills", "skillLabels", "jobLabels"),
        "companyScale": ("brandScaleName", "companyScale", "scaleName", "brandScale"),
        "companyStage": ("brandStageName", "financeStage", "stageName"),
        "companyIndustry": ("brandIndustry", "industry", "industryName"),
        "bossTitle": ("bossTitle", "bossPosition", "positionTitle", "recruiterTitle"),
        "bossName": ("bossName", "boss", "recruiterName"),
        "address": ("address", "jobAddress", "locationName"),
        "jobName": ("jobName", "positionName", "title"),
        "brandName": ("brandName", "companyName", "company"),
        "salaryDesc": _JOB_FIELD_CANDIDATES["salaryDesc"],
    }
    for canonical, candidates in mapping.items():
        value = _deep_first(container, candidates)
        if canonical == "salaryDesc" and value in (None, "", [], {}):
            value = _format_salary_from_bounds(container)
        cleaned = _clean_value(value)
        if cleaned not in (None, "", [], {}):
            detail[canonical] = cleaned

    detail["_raw"] = container if container else payload
    return detail


# 求职画像各分段对应的 resume API URL 片段。命中即归入对应键，未命中则保持空。
_PROFILE_SECTION_MARKERS = {
    "basicInfo": ("resume/baseinfo", "resume/base", "baseinfo"),
    "jobExpectations": ("resume/expect",),
    "jobStatus": ("resume/status",),
    "userInfo": ("user/info", "geek/info", "userinfo"),
    "personalAdvantage": ("resume/advantage",),
    "workExperiences": ("resume/workexp",),
    "projectExperiences": ("resume/projectexp",),
    "educationExperiences": ("resume/education",),
    "jobIntentions": ("resume/intention",),
}


def assemble_profile(captures: list) -> dict:
    """把多个 resume API payload 归入下游统一分段键。

    captures 为 [(响应URL, payload), ...]；每个 payload 取其 zpData/data 容器作为分段值。
    无法匹配的接口保留在 sections 原始映射中，供排障和协议校准使用。
    """
    profile: dict[str, Any] = {key: {} for key in _PROFILE_SECTION_MARKERS}
    sections: dict[str, Any] = {}
    for url, payload in captures or []:
        lowered = str(url).lower()
        value = _container(payload)
        matched = None
        for key, fragments in _PROFILE_SECTION_MARKERS.items():
            if any(fragment in lowered for fragment in fragments):
                matched = key
                break
        if matched and not profile.get(matched):
            profile[matched] = value
        sections[url] = payload
    profile["sections"] = sections
    return profile
