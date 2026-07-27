"""从会话、观察、记忆与工具引用装配有界任务上下文。"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional

from app.core.common.settings import settings
from app.core.memory import MemoryClient
from app.core.tool.injection_probe import probe_text
from app.models.schemas import ChatMessage, TaskUnderstandingResult, ToolResult


class ContextAssembler:
    """上下文预算装配器。

    该组件只做可验证的上下文整理：按当前步骤、当前任务、长期引用和观察结果分层，
    稳定排序、限长、输出摘要和结构化元数据。长期记忆通过 MemoryClient 按配置注入，
    默认关闭，检索失败静默降级。
    """

    def __init__(
        self,
        max_messages: int = 8,
        max_observations: int = 5,
        max_chars: int = 8000,
        memory_client: Optional[MemoryClient] = None,
    ):
        self.max_messages = max_messages
        self.max_observations = max_observations
        self.max_chars = max_chars
        self.memory_client = memory_client or MemoryClient()

    def assemble(
        self,
        *,
        messages: List[ChatMessage],
        task: Optional[TaskUnderstandingResult],
        observations: List[str],
        tool_results: List[ToolResult],
        metadata: Dict[str, Any],
        compaction: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        current_step = {
            "profile": task.profile if task else str(metadata.get("profile") or "default"),
            "objective": task.rewritten_query.planner_query if task else self._last_user_message(messages),
            "next_action": task.next_action if task else "direct_answer",
        }
        current_task = {
            "domain": task.intent.domain if task else "general",
            "intent": task.intent.intent if task else "chat",
            "confidence": task.intent.confidence if task else 0.0,
            "slots": task.slots.filled if task else {},
            "missing_required": task.slots.missing_required if task else [],
            "candidate_capabilities": [
                item.model_dump() for item in (task.routing.candidate_capabilities if task else [])[:5]
            ],
        }
        recent_messages = [self._message_summary(item) for item in messages[-self.max_messages :]]
        recent_observations = list(observations[-self.max_observations :])
        long_term_refs = self._long_term_refs(task, metadata)
        tool_refs = self._tool_refs(tool_results)
        payload = {
            "current_step": current_step,
            "current_task": current_task,
            "recent_messages": recent_messages,
            "recent_observations": recent_observations,
            "long_term_refs": long_term_refs,
            "tool_refs": tool_refs,
            "workspace_dir": settings.workspace_dir,
        }
        # checkpoint 恢复等场景下 state 已携带压缩快照，装配时保留五要素供 Planner 使用。
        if isinstance(compaction, dict) and compaction:
            payload["compaction"] = compaction
        # 由 Java Backend 注入的求职画像/简历/求职进展等个人上下文，让 Planner 直接使用既有数据，
        # 避免在工作台问答里反复要求用户重新提供已知信息。
        personal_context = metadata.get("personal_context")
        if isinstance(personal_context, dict) and personal_context:
            payload["personal_context"] = personal_context
        attachments = self._attachments(metadata)
        if attachments:
            payload["attachments"] = attachments
        memory_refs = self._memory_refs(current_step["objective"], metadata)
        if memory_refs:
            payload["memory_refs"] = memory_refs
        summary = self._to_budgeted_summary(payload)
        return {
            "summary": summary,
            "payload": payload,
            "metrics": {
                "message_count": len(messages),
                "observation_count": len(observations),
                "tool_ref_count": len(tool_refs),
                "memory_ref_count": len(memory_refs),
                "attachment_count": len(attachments),
                "summary_chars": len(summary),
                "budget_chars": self.max_chars,
            },
        }

    def direct_evidence_summary(self, metadata: Dict[str, Any]) -> str:
        """为直达答案合成路径整理与完整 Agent Loop 一致的受控证据。

        直达路径只需要本轮个人上下文与附件，不触发长期记忆检索等额外 I/O；附件仍必须经过
        数量、字符预算和 Prompt Injection 探测，避免低延迟路径绕过上下文治理。
        """
        payload: Dict[str, Any] = {}
        attachments = self._attachments(metadata)
        if attachments:
            payload["attachments"] = attachments
        personal_context = metadata.get("personal_context")
        if isinstance(personal_context, dict) and personal_context:
            payload["personal_context"] = personal_context
        if not payload:
            return "（无额外个人上下文或附件）"
        return self._to_budgeted_summary(payload)

    def _attachments(self, metadata: Dict[str, Any]) -> List[Dict[str, Any]]:
        """整理用户附件并扫描指令注入特征，最多保留五份有界文本。"""
        raw = metadata.get("attachments")
        if not isinstance(raw, list):
            return []
        attachment_count = max(1, min(5, len(raw)))
        per_attachment_chars = max(1200, min(4000, 6000 // attachment_count))
        result: List[Dict[str, Any]] = []
        for item in raw[:5]:
            if not isinstance(item, dict):
                continue
            content = str(item.get("content") or "")
            hits = probe_text(content)
            compact_content = content[:per_attachment_chars]
            result.append(
                {
                    "attachment_id": str(item.get("attachmentId") or ""),
                    "file_name": str(item.get("fileName") or ""),
                    "content_type": str(item.get("contentType") or ""),
                    "character_count": int(item.get("characterCount") or len(content)),
                    "truncated": bool(item.get("truncated")) or len(content) > len(compact_content),
                    "untrusted": True,
                    "injection_hits": hits,
                    "content": "" if hits else compact_content,
                }
            )
        return result

    def _memory_refs(self, objective: str, metadata: Dict[str, Any]) -> List[Dict[str, Any]]:
        if not self.memory_client.enabled:
            return []
        scope = metadata.get("memory_scope")
        trace_id = metadata.get("trace_id")
        tenant_id = metadata.get("tenant_id")
        operator_id = metadata.get("operator_id") or metadata.get("user_id")
        return self.memory_client.search(
            objective,
            scope=scope,
            trace_id=trace_id,
            tenant_id=str(tenant_id) if tenant_id else None,
            operator_id=str(operator_id) if operator_id else None,
        )

    def _last_user_message(self, messages: List[ChatMessage]) -> str:
        for message in reversed(messages or []):
            if message.role == "user":
                return str(message.content)
        return ""

    def _message_summary(self, message: ChatMessage) -> Dict[str, str]:
        text = str(message.content or "")
        return {"role": message.role, "content": text[:500]}

    def _long_term_refs(
        self, task: Optional[TaskUnderstandingResult], metadata: Dict[str, Any]
    ) -> List[Dict[str, Any]]:
        refs: List[Dict[str, Any]] = []
        if task:
            for ref in task.context.resolved_references[:5]:
                refs.append(ref.model_dump())
        # previous_slots 是通用运行时键；业务侧键由部署配置 business_metadata_keys 注入，
        # Runtime Core 不硬编码 resume_id、current_jobs_count 等具体业务概念。
        ref_keys = sorted({"previous_slots", *settings.business_metadata_keys})
        for key in ref_keys:
            if key in metadata:
                refs.append({"source": "request_metadata", "key": key, "value": metadata.get(key)})
        return refs

    def _tool_refs(self, tool_results: List[ToolResult]) -> List[Dict[str, Any]]:
        refs = []
        for result in tool_results or []:
            if result.metadata.get("synthetic"):
                continue
            refs.append(
                {
                    "tool": result.tool_name,
                    "success": result.success,
                    "summary": self._compact(result.output if result.success else result.error),
                    "trace_id": result.metadata.get("trace_id"),
                }
            )
        return refs[-self.max_observations :]

    def _to_budgeted_summary(self, payload: Dict[str, Any]) -> str:
        text = json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str)
        if len(text) <= self.max_chars:
            return text
        return text[: self.max_chars] + "...(truncated)"

    def _compact(self, value: Any) -> str:
        text = json.dumps(value, ensure_ascii=False, default=str) if not isinstance(value, str) else value
        return text[:800]
