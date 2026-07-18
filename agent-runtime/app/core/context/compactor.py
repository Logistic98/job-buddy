from __future__ import annotations

import json
from typing import Any, Dict, List, Optional

from pydantic import BaseModel

from app.core.common.settings import settings

COMPACTION_MARKER_PREFIX = "[Compaction]"


class CompactionReport(BaseModel):
    """一次压缩的现场报告，用于 Trace 记录与测试断言。"""

    folded_observations: int
    chars_before: int
    chars_after: int
    rounds: int


class ContextCompactor:
    """Loop 内观察列表的结构化压缩器。

    Compaction 是任务状态迁移而非单纯摘要：折叠时保留目标、修改、决策、失败、下一步
    五要素（来源为结构化的 tool_results / plan / selected_tool_call，不解析观察文本）。
    多轮触发采用追加式合并，快照存 state["compaction"]，同时以带前缀的观察字符串放在
    列表头部供 Planner 直接消费；新观察继续尾部追加，不重写历史，保持缓存友好。
    """

    MAX_ITEM_CHARS = 200
    MAX_ITEMS = 20
    MARKER_ITEMS = 8

    def __init__(
        self,
        enabled: Optional[bool] = None,
        trigger_observations: Optional[int] = None,
        trigger_chars: Optional[int] = None,
        keep_recent: Optional[int] = None,
    ):
        runtime = settings.config.runtime
        self.enabled = runtime.compaction_enabled if enabled is None else bool(enabled)
        self.trigger_observations = int(trigger_observations or runtime.compaction_trigger_observations)
        self.trigger_chars = int(trigger_chars or runtime.compaction_trigger_chars)
        self.keep_recent = max(1, int(keep_recent or runtime.compaction_keep_recent))

    def maybe_compact(self, state: Dict[str, Any]) -> Optional[CompactionReport]:
        if not self.enabled:
            return None
        observations = list(state.get("observations") or [])
        has_marker = bool(observations) and str(observations[0]).startswith(COMPACTION_MARKER_PREFIX)
        body = observations[1:] if has_marker else observations
        body_chars = sum(len(str(item)) for item in body)
        if len(body) < self.trigger_observations and body_chars < self.trigger_chars:
            return None
        fold_count = len(body) - self.keep_recent
        if fold_count <= 0:
            return None
        chars_before = sum(len(str(item)) for item in observations)
        folded = body[:fold_count]
        kept = body[fold_count:]
        snapshot = self._merge_snapshot(state, folded)
        state["compaction"] = snapshot
        state["observations"] = [self._to_marker(snapshot), *kept]
        metrics = state.setdefault("metrics", {})
        metrics["compaction"] = {
            "rounds": snapshot["rounds"],
            "folded_observations": snapshot["folded_observations"],
        }
        chars_after = sum(len(str(item)) for item in state["observations"])
        return CompactionReport(
            folded_observations=len(folded),
            chars_before=chars_before,
            chars_after=chars_after,
            rounds=snapshot["rounds"],
        )

    def _merge_snapshot(self, state: Dict[str, Any], folded: List[str]) -> Dict[str, Any]:
        previous = dict(state.get("compaction") or {})
        changes: List[Dict[str, str]] = list(previous.get("changes") or [])
        failures: List[Dict[str, str]] = list(previous.get("failures") or [])
        decisions: List[str] = list(previous.get("decisions") or [])
        seen_changes = {(item.get("tool"), item.get("summary")) for item in changes}
        seen_failures = {(item.get("tool"), item.get("error")) for item in failures}
        for result in state.get("tool_results") or []:
            metadata = getattr(result, "metadata", None) or {}
            if metadata.get("synthetic"):
                continue
            if getattr(result, "success", False):
                entry = {
                    "tool": str(getattr(result, "tool_name", "")),
                    "summary": self._compact_text(getattr(result, "output", None)),
                }
                key = (entry["tool"], entry["summary"])
                if key not in seen_changes:
                    changes.append(entry)
                    seen_changes.add(key)
            else:
                entry = {
                    "tool": str(getattr(result, "tool_name", "")),
                    "error": self._compact_text(getattr(result, "error", None)),
                }
                key = (entry["tool"], entry["error"])
                if key not in seen_failures:
                    failures.append(entry)
                    seen_failures.add(key)
        plan = state.get("plan")
        for step in getattr(plan, "steps", None) or []:
            goal = str(getattr(step, "goal", "") or "")
            if goal and goal not in decisions:
                decisions.append(goal)
        next_step = ""
        selected = state.get("selected_tool_call")
        if selected is not None:
            next_step = str(getattr(selected, "reason", None) or f"调用工具 {getattr(selected, 'name', '')}")
        elif plan is not None and getattr(plan, "final_answer", None):
            next_step = "输出最终答案"
        return {
            "objective": str(state.get("objective") or ""),
            "changes": changes[-self.MAX_ITEMS :],
            "decisions": decisions[-self.MAX_ITEMS :],
            "failures": failures[-self.MAX_ITEMS :],
            "next_step": next_step or str(previous.get("next_step") or ""),
            "folded_observations": int(previous.get("folded_observations") or 0) + len(folded),
            "rounds": int(previous.get("rounds") or 0) + 1,
        }

    def _to_marker(self, snapshot: Dict[str, Any]) -> str:
        view = {
            "objective": snapshot["objective"],
            "changes": snapshot["changes"][-self.MARKER_ITEMS :],
            "decisions": snapshot["decisions"][-self.MARKER_ITEMS :],
            "failures": snapshot["failures"][-self.MARKER_ITEMS :],
            "next_step": snapshot["next_step"],
            "folded_observations": snapshot["folded_observations"],
        }
        return f"{COMPACTION_MARKER_PREFIX} 较早观察已压缩为五要素快照：" + json.dumps(
            view, ensure_ascii=False, sort_keys=True
        )

    def _compact_text(self, value: Any) -> str:
        text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, default=str)
        return (text or "")[: self.MAX_ITEM_CHARS]
