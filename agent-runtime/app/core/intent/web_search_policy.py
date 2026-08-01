"""为任务理解阶段生成可审计的联网搜索决策。"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from app.models.schemas import TaskUnderstandingResult


@dataclass(frozen=True)
class WebSearchDecision:
    """一次只读联网工具需求判定。"""

    mode: str
    trigger: str
    reason: str
    signals: tuple[str, ...] = ()

    @property
    def required(self) -> bool:
        return self.mode == "required"

    def as_metadata(self) -> dict[str, Any]:
        return {
            "mode": self.mode,
            "trigger": self.trigger,
            "reason": self.reason,
            "signals": list(self.signals),
        }


class WebSearchPolicy:
    """合并用户约束、模型判断与高置信确定性信号。

    模型负责识别开放语义，确定性规则负责覆盖最新事实、来源核验等高价值
    回归场景。最终结果仍受 Capability allowlist 约束，不能借此扩权。
    """

    _OPT_OUT = re.compile(
        r"(?:不要|不用|无需|禁止|别)(?:再)?.{0,6}(?:联网|上网|网络|网页|网上|互联网|web[_\s-]?search|web|online)",
        re.IGNORECASE,
    )
    _EXPLICIT_SEARCH = re.compile(
        r"(?:联网|上网)(?:查找|查询|搜索|检索|搜|查|浏览)"
        r"|(?:搜索|查找|查询|检索|搜一下|查一下)(?:网页|网络|互联网)"
        r"|(?:网页|网络|互联网)(?:搜索|查找|查询|检索)"
        r"|\b(?:web\s+search|search\s+the\s+web|browse\s+(?:the\s+)?(?:web|internet)"
        r"|look\s+up\s+online|online\s+search)\b",
        re.IGNORECASE,
    )
    _SOURCE_REQUIREMENT = re.compile(
        r"(?:给出|提供|附上|列出|需要|要求|核验|核实|验证).{0,10}(?:来源|引用|出处|链接|证据|官方)"
        r"|(?:官方|可信|可验证).{0,6}(?:来源|引用|出处|链接|证据)"
        r"|\b(?:provide|need|require|cite|verify).{0,24}(?:source|citation|official\s+link)\b",
        re.IGNORECASE,
    )
    _LOOKUP_INTENT = re.compile(
        r"(?:查找|查询|搜索|检索|搜一下|查一下|调查|核验|核实)"
        r"|\b(?:look\s+up|find|search|verify|check)\b",
        re.IGNORECASE,
    )
    _TEMPORAL = re.compile(
        r"(?:最新|近期|最近|当前发布|目前|现在|今天|今日|本周|本月|今年|截至(?:目前|现在|今日)?)"
        r"|\b(?:latest|recent|currently|current|today|this\s+(?:week|month|year)|as\s+of\s+now)\b",
        re.IGNORECASE,
    )
    _VOLATILE_FACT = re.compile(
        r"(?:新闻|动态|公告|发布|版本|模型|API|价格|股价|汇率|天气|预报|比分|赛程|排名|政策|法规|法律|标准|漏洞|CVE|高管|CEO|总统|主席|在任|博客|博文|文章)"
        r"|\b(?:news|release|version|model|api|price|weather|forecast|score|schedule|policy|law|cve|ceo|president|blog|article|post)\b",
        re.IGNORECASE,
    )
    _PERSONAL_OR_LOCAL = re.compile(
        r"(?:当前|我的|这份|这个|本地|工作区|仓库|代码库).{0,8}(?:简历|岗位|项目|经历|附件|文件|目录|代码|日志|数据库|表)"
        r"|(?:简历|岗位|项目|经历|附件|文件|目录|代码|日志|数据库|表).{0,8}(?:当前|我的|这份|这个|本地|工作区|仓库|代码库)",
        re.IGNORECASE,
    )
    _EXTERNAL_FACT_THROUGH_CONTEXT = re.compile(
        r"(?:所在公司|目标公司|行业).{0,10}(?:新闻|动态|公告|发布|价格|政策|法规|高管|CEO)"
        r"|(?:新闻|动态|公告|发布|价格|股价|汇率|天气|预报|政策|法规|法律|高管|CEO)",
        re.IGNORECASE,
    )
    _PROVIDED_CONTENT_TASK = re.compile(
        r"(?:总结|归纳|整理|改写|润色|提取).{0,16}(?:我|用户)?(?:已经|已)?提供的?.{0,8}(?:来源|材料|内容|附件|链接)"
        r"|(?:总结|归纳|整理|改写|润色|提取).{0,10}(?:这些|上述).{0,6}(?:来源|材料|内容|附件|链接)",
        re.IGNORECASE,
    )

    def decide(self, message: str, result: TaskUnderstandingResult) -> WebSearchDecision:
        text = str(message or "").strip()
        rewrite = result.rewritten_query
        resolved_text = " ".join(
            str(item or "")
            for item in (
                text,
                rewrite.resolved_query,
                rewrite.retrieval_query,
            )
        )
        metadata = result.metadata if isinstance(result.metadata, dict) else {}
        contract = metadata.get("capability_contract")
        allowed_tools = self._allowed_tools(contract)

        if self._OPT_OUT.search(text):
            return WebSearchDecision(
                mode="prohibited",
                trigger="user",
                reason="用户明确禁止联网搜索",
                signals=("explicit_opt_out",),
            )

        explicit = bool(self._EXPLICIT_SEARCH.search(text))
        provided_content_task = bool(self._PROVIDED_CONTENT_TASK.search(text))
        source_required = bool(self._SOURCE_REQUIREMENT.search(text)) and not provided_content_task
        lookup_intent = bool(self._LOOKUP_INTENT.search(text))
        temporal = bool(self._TEMPORAL.search(resolved_text))
        volatile_fact = bool(self._VOLATILE_FACT.search(resolved_text))
        personal_or_local = bool(self._PERSONAL_OR_LOCAL.search(text))
        external_fact_through_context = bool(self._EXTERNAL_FACT_THROUGH_CONTEXT.search(text))
        provided_context_sufficient = (personal_or_local or provided_content_task) and not external_fact_through_context
        model_required = self._model_requires_external_information(metadata)

        signals = tuple(
            name
            for name, present in (
                ("explicit_request", explicit),
                ("source_citation", source_required),
                ("lookup_intent", lookup_intent),
                ("temporal_freshness", temporal),
                ("volatile_fact", volatile_fact),
                ("model_required", model_required),
                ("provided_context", personal_or_local),
                ("provided_content", provided_content_task),
                ("external_fact_through_context", external_fact_through_context),
            )
            if present
        )

        trigger = ""
        reason = ""
        if explicit:
            trigger = "explicit"
            reason = "用户明确要求联网搜索"
        elif source_required:
            trigger = "autonomous"
            reason = "回答要求可核验来源或官方链接"
        elif model_required:
            trigger = "autonomous"
            reason = "任务理解判定回答依赖外部信息"
        elif not provided_context_sufficient and lookup_intent:
            trigger = "autonomous"
            reason = "用户要求查找或核验外部公开信息"
        elif not provided_context_sufficient and temporal and volatile_fact:
            trigger = "autonomous"
            reason = "问题依赖可能变化的时效性事实"

        if not trigger:
            return WebSearchDecision(
                mode="optional",
                trigger="policy",
                reason="现有上下文或稳定知识足以回答",
                signals=signals,
            )
        if "web_search" not in allowed_tools:
            return WebSearchDecision(
                mode="not_allowed",
                trigger=trigger,
                reason="当前能力未授权 web_search，未扩大工具权限",
                signals=signals,
            )
        return WebSearchDecision(
            mode="required",
            trigger=trigger,
            reason=reason,
            signals=signals,
        )

    def _allowed_tools(self, contract: Any) -> set[str]:
        if not isinstance(contract, dict):
            return set()
        return {
            str(item)
            for item in [
                *(contract.get("required_tools") or []),
                *(contract.get("allowed_tools") or []),
            ]
        }

    def _model_requires_external_information(self, metadata: dict[str, Any]) -> bool:
        requirement = metadata.get("external_information_requirement")
        if not isinstance(requirement, dict):
            return False
        return str(requirement.get("mode") or "").strip().lower() == "required"
