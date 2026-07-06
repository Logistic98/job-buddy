
import contextvars
from typing import Any, Dict, Optional

_run_usage: contextvars.ContextVar[Optional[Dict[str, int]]] = contextvars.ContextVar("run_token_usage", default=None)


def start_usage_tracking() -> Dict[str, int]:
    """开启当前 run 的 token 用量追踪，返回累计器字典。

    累计器随 contextvars 沿 asyncio 任务树传播，asyncio.gather 派生的子任务共享同一
    可变字典对象；并发请求各自持有独立上下文，互不污染。调用方可把返回的字典挂到
    graph state 上，客户端每次写入后读取即为最新累计值。
    """
    usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0, "llm_calls": 0}
    _run_usage.set(usage)
    return usage


def current_usage() -> Optional[Dict[str, int]]:
    return _run_usage.get()


def record_usage(usage: Optional[Dict[str, Any]], count_call: bool = True) -> None:
    """把一次模型调用的 usage 归一化后累加进当前 run 的累计器。

    兼容 OpenAI（prompt_tokens/completion_tokens/total_tokens）与 Anthropic
    （input_tokens/output_tokens）两种字段命名；未开启追踪时为空操作。
    """
    accumulator = _run_usage.get()
    if accumulator is None or not isinstance(usage, dict):
        return
    prompt = _as_int(usage.get("prompt_tokens", usage.get("input_tokens")))
    completion = _as_int(usage.get("completion_tokens", usage.get("output_tokens")))
    total = _as_int(usage.get("total_tokens"))
    if total == 0:
        total = prompt + completion
    accumulator["prompt_tokens"] += prompt
    accumulator["completion_tokens"] += completion
    accumulator["total_tokens"] += total
    if count_call:
        accumulator["llm_calls"] += 1


def _as_int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0
