import asyncio
import copy
import hashlib
import json
import random
from collections import OrderedDict
from typing import Any, AsyncIterator, Dict, List, Optional

import httpx
from loguru import logger

from app.core.common.settings import settings
from app.models.schemas import ChatMessage, ToolDefinition


class LLMServiceError(RuntimeError):
    """模型服务异常。"""


class OpenAICompatibleClient:
    """多来源模型客户端。

    DeepSeek API 走 OpenAI Chat Completions 兼容协议；ChatGPT Pro 与 Claude Max
    使用官网会员 Token 来源标识，模型名不在平台设置中固定，运行时会尝试拉取可用模型，
    拉取失败时使用对应来源的最新模型兜底名。
    """

    def __init__(self, base_url: str = None, api_key: str = None, model: str = None, timeout: int = None, provider: str = None, **overrides):
        llm_config = settings.config.llm_service
        self.provider = self._normalize_provider(provider or overrides.get("provider") or llm_config.provider)
        self.auto_model = bool(overrides.get("auto_model", False))
        self.base_url = (base_url or self._default_base_url(self.provider) or llm_config.base_url).rstrip("/")
        self.chat_completions_url = self._build_chat_completions_url(self.base_url)
        self.api_key = api_key or overrides.get("auth_token") or llm_config.api_key
        self.model = "" if self.auto_model and not model else (model or llm_config.model_name)
        self.timeout = timeout or llm_config.timeout_seconds
        self.max_retries = int(self._override(overrides, "max_retries", llm_config.max_retries))
        self.retry_backoff_seconds = float(self._override(overrides, "retry_backoff_seconds", llm_config.retry_backoff_seconds))
        self.temperature = float(self._override(overrides, "temperature", llm_config.temperature))
        self.max_tokens = int(self._override(overrides, "max_tokens", llm_config.max_tokens))
        self.prompt_cache_enabled = bool(self._override(overrides, "prompt_cache_enabled", llm_config.prompt_cache_enabled))
        self.prompt_cache_strategy = str(self._override(overrides, "prompt_cache_strategy", llm_config.prompt_cache_strategy))
        self.request_cache_enabled = bool(self._override(overrides, "request_cache_enabled", llm_config.request_cache_enabled))
        self.request_cache_max_entries = int(self._override(overrides, "request_cache_max_entries", llm_config.request_cache_max_entries))
        self.understanding_thinking_disabled = bool(self._override(overrides, "understanding_thinking_disabled", getattr(llm_config, "understanding_thinking_disabled", True)))
        self._cache: OrderedDict[str, Dict[str, Any]] = OrderedDict()
        self.cache_metrics: Dict[str, int] = {"hits": 0, "misses": 0, "stores": 0}
        # 进程内复用同一 AsyncClient：default_llm_client 在 Executor 构造期固定，跨请求
        # 复用该实例即复用到模型服务的 keep-alive 连接，省掉每次调用重做 TLS/TCP 握手
        # 带来的首字延迟（理解 + 合成两次调用各省一次握手）。
        self._http: Optional[httpx.AsyncClient] = None

    def _client(self) -> httpx.AsyncClient:
        """惰性获取并复用 AsyncClient，保持到模型服务的连接池与 keep-alive。

        仅以 timeout 构造，单次调用需要更短超时时在请求方法上以 timeout= 覆盖，
        既不破坏按调用粒度的超时控制，又能跨调用复用底层连接。
        """
        if self._http is None:
            self._http = httpx.AsyncClient(timeout=self.timeout)
        return self._http

    async def aclose(self) -> None:
        if self._http is not None:
            await self._http.aclose()
            self._http = None

    @classmethod
    def from_config(cls, config: Dict[str, Any]) -> "OpenAICompatibleClient":
        data = config or {}
        return cls(
            base_url=data.get("base_url") or data.get("baseUrl"),
            api_key=data.get("api_key") or data.get("apiKey"),
            model=data.get("model_name") or data.get("modelName") or data.get("model"),
            timeout=data.get("timeout_seconds") or data.get("timeoutSeconds"),
            provider=data.get("provider"),
            max_retries=data.get("max_retries") or data.get("maxRetries"),
            retry_backoff_seconds=data.get("retry_backoff_seconds") or data.get("retryBackoffSeconds"),
            temperature=data.get("temperature"),
            max_tokens=data.get("max_tokens") or data.get("maxTokens"),
            prompt_cache_enabled=data.get("prompt_cache_enabled") if "prompt_cache_enabled" in data else data.get("promptCacheEnabled"),
            prompt_cache_strategy=data.get("prompt_cache_strategy") or data.get("promptCacheStrategy"),
            request_cache_enabled=data.get("request_cache_enabled") if "request_cache_enabled" in data else data.get("requestCacheEnabled"),
            request_cache_max_entries=data.get("request_cache_max_entries") or data.get("requestCacheMaxEntries"),
            auth_token=data.get("auth_token") or data.get("authToken"),
            auto_model=not bool(data.get("model_name") or data.get("modelName") or data.get("model")),
        )

    async def chat(self, messages: List[ChatMessage], tools: Optional[List[ToolDefinition]] = None, temperature: Optional[float] = None, max_tokens: Optional[int] = None, disable_thinking: bool = False) -> Dict[str, Any]:
        await self._ensure_model()
        payload = self._build_payload(messages, tools, temperature, max_tokens, stream=False, disable_thinking=disable_thinking)
        cache_key = self._cache_key({"provider": self.provider, "url": self.chat_completions_url, **payload})
        if self.request_cache_enabled and cache_key in self._cache:
            self.cache_metrics["hits"] += 1
            cached = self._cache.pop(cache_key)
            self._cache[cache_key] = cached
            message = copy.deepcopy(cached)
            message.setdefault("usage", {})
            message["cache"] = {"hit": True, "key": cache_key[:12]}
            logger.debug(f"模型缓存命中：provider={self.provider}, model={self.model}, key={cache_key[:12]}")
            return message
        self.cache_metrics["misses"] += 1

        last_error: Optional[Exception] = None
        for attempt in range(self.max_retries + 1):
            try:
                response = await self._client().post(self.chat_completions_url, headers=self._headers(), json=payload)
                response.raise_for_status()
                data = response.json()
                message = self._parse_response(data)
                message["cache"] = {"hit": False, "key": cache_key[:12]}
                self._store_cache(cache_key, message)
                logger.debug(f"模型响应完成：provider={self.provider}, model={self.model}, prompt_cache={self.prompt_cache_enabled}/{self.prompt_cache_strategy}, usage: {message.get('usage') or {}}")
                return message
            except (httpx.TimeoutException, httpx.ConnectError, httpx.HTTPStatusError) as e:
                last_error = e
                if isinstance(e, httpx.HTTPStatusError) and e.response.status_code < 500:
                    break
                if attempt < self.max_retries:
                    # 指数退避叠加随机抖动，避免并发请求同时重试造成 thundering herd
                    await asyncio.sleep(self.retry_backoff_seconds * (2 ** attempt) * (1 + random.uniform(0, 0.5)))
                    continue
                break
            except Exception as e:
                last_error = e
                break
        raise LLMServiceError(f"模型服务调用失败：{last_error}")

    async def stream_chat(self, messages: List[ChatMessage], temperature: Optional[float] = None, max_tokens: Optional[int] = None) -> AsyncIterator[Dict[str, str]]:
        """流式问答，逐段 yield {"type": "reasoning"|"answer", "text": ...}。

        推理模型（如 DeepSeek-R1）会先输出 reasoning_content（思考过程）再输出 content（答案），
        两者都逐字下发：reasoning 作为可见的推理过程，answer 作为最终答案，避免思考阶段长时间空白。
        """
        await self._ensure_model()
        payload = self._build_payload(messages, None, temperature, max_tokens, stream=True)
        try:
            async with self._client().stream("POST", self.chat_completions_url, headers=self._headers(), json=payload) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    if not line or not line.startswith("data:"):
                        continue
                    data_str = line[len("data:"):].strip()
                    if not data_str or data_str == "[DONE]":
                        if data_str == "[DONE]":
                            break
                        continue
                    try:
                        chunk = json.loads(data_str)
                    except json.JSONDecodeError:
                        continue
                    kind, piece = self._parse_stream_event(chunk)
                    if piece:
                        yield {"type": kind, "text": piece}
        except (httpx.TimeoutException, httpx.ConnectError, httpx.HTTPStatusError) as e:
            raise LLMServiceError(f"模型流式调用失败：{e}")

    def _build_payload(self, messages: List[ChatMessage], tools: Optional[List[ToolDefinition]], temperature: Optional[float], max_tokens: Optional[int], stream: bool, disable_thinking: bool = False) -> Dict[str, Any]:
        if self._is_anthropic():
            system, anthropic_messages = self._to_anthropic_messages(messages)
            payload: Dict[str, Any] = {"model": self.model, "messages": anthropic_messages, "temperature": self.temperature if temperature is None else temperature, "max_tokens": max_tokens or self.max_tokens}
            if system:
                payload["system"] = system
            if tools:
                anthropic_tools = [self._tool_to_anthropic(tool) for tool in tools]
                # Anthropic 最多 4 个 cache 断点，只在最后一个工具上打标即可缓存整个工具前缀
                if self.prompt_cache_enabled and anthropic_tools:
                    anthropic_tools[-1]["cache_control"] = {"type": "ephemeral"}
                payload["tools"] = anthropic_tools
                payload["tool_choice"] = {"type": "auto"}
            if stream:
                payload["stream"] = True
            return payload

        payload = {"model": self.model, "messages": [self._message_to_dict(item) for item in messages], "temperature": self.temperature if temperature is None else temperature, "max_tokens": max_tokens or self.max_tokens}
        if tools:
            payload["tools"] = [self._tool_to_openai(tool) for tool in tools]
            payload["tool_choice"] = "auto"
        if stream:
            payload["stream"] = True
        # 路由/分类类调用关闭推理模型的隐藏思考链，避免首字延迟被思考阶段拉长。
        # 仅 DeepSeek OpenAI 兼容接口支持 thinking 开关，其他提供方忽略以免请求被拒。
        if disable_thinking and self.understanding_thinking_disabled and self.provider == "deepseek_api":
            payload["thinking"] = {"type": "disabled"}
        return payload

    def _headers(self) -> Dict[str, str]:
        if self._is_anthropic():
            return {"x-api-key": self.api_key, "anthropic-version": "2023-06-01", "Content-Type": "application/json"}
        return {"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"}

    def _parse_response(self, data: Dict[str, Any]) -> Dict[str, Any]:
        if self._is_anthropic():
            text = "".join((block.get("text") or "") for block in (data.get("content") or []) if block.get("type") == "text")
            return {"role": "assistant", "content": text, "usage": data.get("usage") or {}}
        message = data["choices"][0]["message"]
        message["usage"] = data.get("usage", {})
        return message

    def _parse_stream_piece(self, chunk: Dict[str, Any]) -> Optional[str]:
        if self._is_anthropic():
            if chunk.get("type") == "content_block_delta":
                return (chunk.get("delta") or {}).get("text")
            return None
        choices = chunk.get("choices") or []
        return ((choices[0].get("delta") or {}).get("content")) if choices else None

    def _parse_stream_event(self, chunk: Dict[str, Any]) -> tuple[Optional[str], Optional[str]]:
        """解析一帧流式增量，区分推理过程与最终答案，返回 (kind, text)。

        kind 为 "reasoning"（思考过程）或 "answer"（最终答案）。无可用增量时返回 (None, None)。
        """
        if self._is_anthropic():
            if chunk.get("type") == "content_block_delta":
                delta = chunk.get("delta") or {}
                if delta.get("type") == "thinking_delta":
                    return "reasoning", delta.get("thinking")
                return "answer", delta.get("text")
            return None, None
        choices = chunk.get("choices") or []
        if not choices:
            return None, None
        delta = choices[0].get("delta") or {}
        reasoning = delta.get("reasoning_content")
        if reasoning is None:
            reasoning = delta.get("reasoning")
        if reasoning:
            return "reasoning", reasoning
        return "answer", delta.get("content")

    def _to_anthropic_messages(self, messages: List[ChatMessage]) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        system_blocks: List[Dict[str, Any]] = []
        output: List[Dict[str, Any]] = []
        for message in messages:
            content = message.content or ""
            if message.role == "system":
                system_blocks.append({"type": "text", "text": content})
            else:
                output.append({"role": "assistant" if message.role == "assistant" else "user", "content": [{"type": "text", "text": content}]})
        if self.prompt_cache_enabled and system_blocks:
            system_blocks[-1]["cache_control"] = {"type": "ephemeral"}
        return system_blocks, output

    async def _ensure_model(self):
        if self.model:
            return
        self.model = await self._fetch_latest_model() or self._fallback_model()

    async def _fetch_latest_model(self) -> Optional[str]:
        try:
            if self.base_url.endswith("/v1"):
                url = f"{self.base_url}/models"
            else:
                url = f"{self.base_url.rstrip('/')}/v1/models"
            response = await self._client().get(url, headers=self._headers(), timeout=min(int(self.timeout), 20))
            response.raise_for_status()
            data = response.json()
            models = data.get("data") or data.get("models") or []
            candidates = [item for item in models if isinstance(item, dict) and (item.get("id") or item.get("name"))]
            if not candidates:
                return None
            # 选最新模型：优先按 created/created_at 倒序，无时间字段时保持服务端默认顺序
            def _created(item: Dict[str, Any]):
                value = item.get("created") or item.get("created_at") or 0
                if isinstance(value, (int, float)):
                    return (1, float(value), "")
                return (0, 0.0, str(value))

            with_time = [item for item in candidates if item.get("created") or item.get("created_at")]
            if with_time:
                newest = max(with_time, key=_created)
            else:
                newest = candidates[0]
            return newest.get("id") or newest.get("name")
        except Exception as e:
            logger.debug(f"拉取模型列表失败，使用默认最新模型兜底：provider={self.provider}, error={e}")
            return None

    def _fallback_model(self) -> str:
        if self._is_anthropic():
            return "claude-sonnet-4-6"
        if self.provider == "chatgpt_pro":
            return "gpt-4o"
        return "deepseek-chat"

    def _cache_key(self, payload: Dict[str, Any]) -> str:
        raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    def _store_cache(self, key: str, value: Dict[str, Any]):
        if not self.request_cache_enabled:
            return
        self._cache[key] = copy.deepcopy(value)
        self.cache_metrics["stores"] += 1
        while len(self._cache) > max(1, self.request_cache_max_entries):
            self._cache.popitem(last=False)

    def get_cache_metrics(self) -> Dict[str, int]:
        return dict(self.cache_metrics)

    def _build_chat_completions_url(self, base_url: str) -> str:
        if self._is_anthropic():
            if base_url.endswith("/messages"):
                return base_url
            if base_url.endswith("/v1"):
                return f"{base_url}/messages"
            return f"{base_url}/v1/messages"
        if base_url.endswith("/chat/completions"):
            return base_url
        if base_url.endswith("/v1"):
            return f"{base_url}/chat/completions"
        return f"{base_url}/v1/chat/completions"

    def _message_to_dict(self, message: ChatMessage) -> Dict[str, Any]:
        data = {"role": message.role, "content": message.content}
        if message.name:
            data["name"] = message.name
        if message.tool_call_id:
            data["tool_call_id"] = message.tool_call_id
        return data

    def _tool_to_openai(self, tool: ToolDefinition) -> Dict[str, Any]:
        return {"type": "function", "function": {"name": tool.name, "description": tool.description, "parameters": tool.input_schema or {"type": "object", "properties": {}}}}

    def _tool_to_anthropic(self, tool: ToolDefinition) -> Dict[str, Any]:
        return {"name": tool.name, "description": tool.description, "input_schema": tool.input_schema or {"type": "object", "properties": {}}}

    def _default_base_url(self, provider: str) -> str:
        if provider == "claude_max":
            return "https://claude.ai/api"
        if provider == "chatgpt_pro":
            return "https://chatgpt.com/backend-api"
        if provider == "deepseek_api":
            return "https://api.deepseek.com"
        return ""

    def _override(self, values: Dict[str, Any], key: str, fallback: Any) -> Any:
        value = values.get(key)
        return fallback if value is None or value == "" else value

    def _normalize_provider(self, provider: str) -> str:
        value = (provider or "deepseek_api").strip().lower()
        if value in {"codex", "openai", "openai_subscription"}:
            return "chatgpt_pro"
        if value in {"claude", "anthropic", "claude_subscription"}:
            return "claude_max"
        return value if value in {"chatgpt_pro", "claude_max", "deepseek_api"} else "deepseek_api"

    def _is_anthropic(self) -> bool:
        return self.provider == "claude_max"
