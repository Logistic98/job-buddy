import asyncio
import json
from abc import ABC, abstractmethod
from typing import Any, Awaitable, Callable, Dict, Optional

from loguru import logger
from pydantic import BaseModel, ConfigDict, Field

from app.core.common.constants import ToolKind, ToolRiskLevel
from app.core.common.settings import settings
from app.core.utils.time_utils import TimeUtils
from app.models.schemas import ToolCall, ToolDefinition, ToolResult

ToolProgressCallback = Callable[[Dict[str, Any]], Awaitable[None]]


class ToolExecutionContext(BaseModel):
    """工具执行上下文。"""

    run_id: str
    trace_id: str
    session_id: str
    workspace_dir: str = "."
    metadata: Dict[str, Any] = Field(default_factory=dict)
    progress_callback: Optional[ToolProgressCallback] = None

    model_config = ConfigDict(arbitrary_types_allowed=True)


class ValidationResult(BaseModel):
    result: bool
    message: str = ""
    error_code: int = 0


class BaseTool(ABC):
    """
    工具基类。

    参考 Claude Code Tool 成熟接口：工具自描述、别名、检索提示、只读/破坏性标识、并发安全、
    输入校验、权限前置、执行上下文、进度回调和大结果有界截断都由工具协议统一承载。
    """

    name: str = ""
    aliases: list[str] = []
    search_hint: str = ""
    description: str = ""
    kind: ToolKind = ToolKind.BUILTIN
    input_schema: Dict[str, Any] = {"type": "object", "properties": {}}
    output_schema: Dict[str, Any] = {"type": "object"}
    risk_level: ToolRiskLevel = ToolRiskLevel.LOW
    tags: list[str] = []
    timeout_seconds: int = 30
    max_retries: int = 0
    max_result_size_chars: int = 12000
    enabled: bool = True
    read_only: bool = True
    destructive: bool = False
    concurrency_safe: bool = True
    should_defer: bool = False
    always_load: bool = False

    def definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            aliases=self.aliases,
            search_hint=self.search_hint,
            description=self.description,
            kind=self.kind,
            input_schema=self.input_schema,
            output_schema=self.output_schema,
            risk_level=self.risk_level,
            tags=self.tags,
            timeout_seconds=self.timeout_seconds,
            max_retries=self.max_retries,
            max_result_size_chars=self.max_result_size_chars,
            enabled=self.enabled,
            read_only=self.read_only,
            destructive=self.destructive,
            concurrency_safe=self.concurrency_safe,
            should_defer=self.should_defer,
            always_load=self.always_load,
        )

    def is_enabled(self) -> bool:
        return self.enabled

    def is_read_only(self, arguments: Dict[str, Any]) -> bool:
        return self.read_only

    def is_destructive(self, arguments: Dict[str, Any]) -> bool:
        return self.destructive

    def inputs_equivalent(self, a: Dict[str, Any], b: Dict[str, Any]) -> bool:
        return a == b

    async def description_for_call(self, arguments: Dict[str, Any]) -> str:
        return self.description

    async def validate_input(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> ValidationResult:
        required = self.input_schema.get("required", []) or []
        for key in required:
            if key not in arguments:
                return ValidationResult(result=False, message=f"缺少必填参数: {key}", error_code=400)
        return ValidationResult(result=True)

    async def safe_run(self, tool_call: ToolCall, context: ToolExecutionContext) -> ToolResult:
        start_timestamp = TimeUtils.get_timestamp()
        try:
            validation = await self.validate_input(tool_call.arguments, context)
            if not validation.result:
                raise ValueError(validation.message)

            timeout = self.timeout_seconds or settings.tool_timeout_seconds
            output = await asyncio.wait_for(self._run(tool_call.arguments, context), timeout=timeout)
            normalized_output, metadata = self._normalize_output(tool_call, output)
            return ToolResult(
                tool_call_id=tool_call.id,
                tool_name=self.name,
                success=True,
                output=normalized_output,
                latency_ms=TimeUtils.calculate_latency_ms(start_timestamp, TimeUtils.get_timestamp()),
                metadata=metadata,
            )
        except (ValueError, TimeoutError, asyncio.TimeoutError) as e:
            if isinstance(e, (TimeoutError, asyncio.TimeoutError)):
                configured_timeout = self.timeout_seconds or settings.tool_timeout_seconds
                error_message = f"工具 {self.name} 执行超时（{configured_timeout} 秒）"
            else:
                error_message = str(e).strip() or f"工具 {self.name} 执行失败"
            logger.warning(f"工具执行失败：tool={self.name}, error={error_message}")
            return ToolResult(
                tool_call_id=tool_call.id,
                tool_name=self.name,
                success=False,
                error=error_message,
                latency_ms=TimeUtils.calculate_latency_ms(start_timestamp, TimeUtils.get_timestamp()),
            )
        except Exception as e:
            logger.exception(f"工具执行异常：tool={self.name}")
            return ToolResult(
                tool_call_id=tool_call.id,
                tool_name=self.name,
                success=False,
                error=str(e),
                latency_ms=TimeUtils.calculate_latency_ms(start_timestamp, TimeUtils.get_timestamp()),
            )

    def _normalize_output(self, tool_call: ToolCall, output: Any) -> tuple[Any, Dict[str, Any]]:
        metadata: Dict[str, Any] = {}
        try:
            text = json.dumps(output, ensure_ascii=False, default=str)
        except TypeError:
            text = str(output)

        max_chars = self.max_result_size_chars or settings.config.runtime.max_inline_result_chars
        if max_chars > 0 and len(text) > max_chars:
            metadata.update({"truncated": True, "full_size_chars": len(text), "storage": "not_persisted"})
            return {"preview": text[:max_chars], "truncated": True, "full_size_chars": len(text)}, metadata
        metadata["truncated"] = False
        return output, metadata

    async def emit_progress(self, context: ToolExecutionContext, payload: Dict[str, Any]):
        if context.progress_callback:
            await context.progress_callback(payload)

    @abstractmethod
    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        pass
