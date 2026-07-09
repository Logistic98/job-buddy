"""统一 loguru 日志配置。

通过 patcher 把 logger.contextualize 注入的链路字段（request_id/run_id/session_id/trace_id 等）
渲染进每条日志，使嵌套模块（graph、工具、LLM 客户端）的日志无需手工拼接即可携带 run 上下文。
"""

from __future__ import annotations

import os
import sys

from loguru import logger

_CONTEXT_KEY = "log_context"

_LOG_FORMAT = (
    "<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | "
    "<level>{level: <8}</level> | "
    "<cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan>"
    "{extra[" + _CONTEXT_KEY + "]} - <level>{message}</level>"
)


def _render_context(record) -> None:
    fields = {key: value for key, value in record["extra"].items() if key != _CONTEXT_KEY}
    if fields:
        rendered = " ".join(f"{key}={value}" for key, value in fields.items())
        record["extra"][_CONTEXT_KEY] = f" [{rendered}]"
    else:
        record["extra"][_CONTEXT_KEY] = ""


def setup_logging() -> None:
    logger.remove()
    logger.configure(patcher=_render_context)
    logger.add(sys.stderr, format=_LOG_FORMAT, level=os.environ.get("LOG_LEVEL", "INFO"))
