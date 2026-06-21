"""第三层意图分类器：OpenAI 兼容协议的 LLM 兜底。

默认关闭。通过环境变量开启与配置,任何失败(未配置、超时、解析失败)
返回 None,由上层降级到评分层或默认结果,保证主链路不被模型服务可用性阻塞。

环境变量:
- AGENT_INTENT_LLM_ENABLED: true/false,默认 false
- AGENT_INTENT_LLM_BASE_URL: OpenAI 兼容服务地址,如 https://api.deepseek.com/v1
- AGENT_INTENT_LLM_API_KEY: 服务密钥
- AGENT_INTENT_LLM_MODEL: 模型名
- AGENT_INTENT_LLM_TIMEOUT_SECONDS: 超时,默认 8
"""

import json
import os
from typing import Optional

import httpx
from loguru import logger

from .models import IntentResult

_ALLOWED_DOMAINS = {"job", "runtime", "security", "open_domain", "unknown"}
_ALLOWED_RISKS = {"low", "medium", "high"}

_SYSTEM_PROMPT = """你是意图分类器。根据用户消息输出 JSON,不要输出其他内容。
字段:
- domain: job/runtime/security/open_domain/unknown 之一
- intent: 业务意图标识,如 job.recommend、complex_engineering_qa、complex_question_answering
- confidence: 0 到 1 的小数
- risk: low/medium/high 之一
- needs_clarification: 布尔值
- next_action: 下一步动作标识,如 direct_answer_with_trace、create_agent_task、clarify
"""


def llm_enabled() -> bool:
    return os.getenv("AGENT_INTENT_LLM_ENABLED", "false").strip().lower() in {"1", "true", "yes"}


def classify_with_llm(text: str) -> Optional[IntentResult]:
    if not llm_enabled():
        return None
    base_url = os.getenv("AGENT_INTENT_LLM_BASE_URL", "").rstrip("/")
    api_key = os.getenv("AGENT_INTENT_LLM_API_KEY", "")
    model = os.getenv("AGENT_INTENT_LLM_MODEL", "")
    if not base_url or not api_key or not model:
        logger.warning("agent-intent LLM 分类器已开启但配置不完整,降级到评分层")
        return None
    timeout = float(os.getenv("AGENT_INTENT_LLM_TIMEOUT_SECONDS", "8"))

    try:
        response = httpx.post(
            f"{base_url}/chat/completions",
            headers={"Authorization": f"Bearer {api_key}"},
            json={
                "model": model,
                "messages": [
                    {"role": "system", "content": _SYSTEM_PROMPT},
                    {"role": "user", "content": text},
                ],
                "temperature": 0,
            },
            timeout=timeout,
        )
        response.raise_for_status()
        content = response.json()["choices"][0]["message"]["content"]
        return _parse_result(content)
    except Exception as e:
        logger.warning(f"agent-intent LLM 分类失败,降级到评分层: {e}")
        return None


def _parse_result(content: str) -> Optional[IntentResult]:
    raw = content.strip()
    if raw.startswith("```"):
        raw = raw.strip("`")
        if raw.startswith("json"):
            raw = raw[4:]
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        logger.warning("agent-intent LLM 输出不是合法 JSON")
        return None

    domain = data.get("domain")
    risk = data.get("risk")
    if domain not in _ALLOWED_DOMAINS or risk not in _ALLOWED_RISKS:
        logger.warning(f"agent-intent LLM 输出字段非法: domain={domain}, risk={risk}")
        return None
    try:
        return IntentResult(
            domain=domain,
            intent=str(data.get("intent", "unknown")),
            confidence=max(0.0, min(float(data.get("confidence", 0.5)), 1.0)),
            risk=risk,
            needs_clarification=bool(data.get("needs_clarification", False)),
            next_action=str(data.get("next_action", "direct_answer_with_trace")),
            router="llm",
        )
    except (TypeError, ValueError):
        return None
