from __future__ import annotations

import os
import re
from pathlib import Path
from threading import RLock
from typing import Any, Dict, List

import yaml
from pydantic import BaseModel, ConfigDict, Field


class LLMServiceConfig(BaseModel):
    """大模型服务配置。"""

    provider: str = "deepseek_api"
    base_url: str = "https://api.deepseek.com/chat/completions"
    api_key: str = "EMPTY"
    model_name: str = "deepseek-chat"
    timeout_seconds: int = 60
    max_retries: int = 2
    retry_backoff_seconds: float = 0.8
    temperature: float = 0.2
    max_tokens: int = 4096
    prompt_cache_enabled: bool = True
    prompt_cache_strategy: str = "stable-prefix"
    request_cache_enabled: bool = True
    request_cache_max_entries: int = 256
    # 任务理解等结构化路由调用是否关闭模型思考链：推理模型的隐藏思考会显著拉长首字延迟，
    # 而路由 JSON 不需要深度思考。仅对支持 thinking 开关的提供方（DeepSeek）生效。
    understanding_thinking_disabled: bool = True


class ToolSearchConfig(BaseModel):
    """工具搜索配置。"""

    enabled: bool = True
    limit: int = 8
    fallback_limit: int = 5


class WebSearchConfig(BaseModel):
    """联网搜索配置。"""

    provider: str = "bocha"
    bocha_api_key: str = ""
    bocha_web_endpoint: str = "https://api.bochaai.com/v1/web-search"
    bocha_ai_endpoint: str = "https://api.bochaai.com/v1/ai-search?utm_source=job-buddy-runtime"
    freshness: str = "noLimit"
    fallback_to_duckduckgo: bool = True


class WebFetchConfig(BaseModel):
    """Untrusted HTTP response budgets enforced before result serialization."""

    max_wire_bytes: int = 512 * 1024
    max_decoded_bytes: int = 1024 * 1024
    max_expansion_ratio: int = 20
    max_text_chars: int = 24000
    chunk_bytes: int = 16 * 1024


class RuntimeConfig(BaseModel):
    """运行时主链路配置。"""

    app_name: str = "job_buddy_runtime"
    max_turns: int = 12
    max_tool_calls: int = 20
    max_failures: int = 3
    # 单次 run 的 token 预算默认值；请求传入 0 时也回落到该有限预算。
    max_run_tokens: int = 32768
    # Loop 内观察列表的结构化压缩（Compaction）配置：条数或总字符任一达到阈值即触发，
    # 触发后保留最近 keep_recent 条原始观察，其余折叠为五要素快照。
    compaction_enabled: bool = True
    compaction_trigger_observations: int = 12
    compaction_trigger_chars: int = 6000
    compaction_keep_recent: int = 4
    tool_timeout_seconds: int = 30
    use_llm_planner: bool = True
    workspace_dir: str = "."
    max_inline_result_chars: int = 12000
    profiles_dir: str = "config/profiles"
    workflows_dir: str = "config/workflows"
    prompts_dir: str = "config/prompts"
    # 业务侧 request metadata 键白名单：由部署配置注入，Runtime Core 不硬编码具体业务概念
    # （如 resume_id、current_jobs_count）。理解链路与上下文装配按此白名单透传业务元数据。
    business_metadata_keys: List[str] = Field(default_factory=list)


class CheckpointConfig(BaseModel):
    """检查点配置。"""

    enabled: bool = True
    max_per_session: int = 100


class PermissionConfig(BaseModel):
    """权限策略配置。"""

    default_mode: str = "default"
    # AUTO/BYPASS are execution policies, not privileges supplied by an API caller.
    # Deployments must opt in before request permission modes are honored.
    allow_auto_permission_mode: bool = False
    allow_bypass_permission_mode: bool = False
    allow_high_risk_in_default: bool = False
    allow_tools: List[str] = Field(default_factory=list)
    deny_tools: List[str] = Field(default_factory=list)
    destructive_tools: List[str] = Field(default_factory=list)


class TranscriptReviewConfig(BaseModel):
    """高风险工具调用前的独立 transcript 复核服务。"""

    enabled: bool = True
    base_url: str = "http://localhost:8020"
    timeout_seconds: float = 2.0
    max_retries: int = 1
    retry_backoff_seconds: float = 0.2


class ToolRuntimeConfig(BaseModel):
    """工具运行时配置。"""

    enabled: bool = True
    max_retries: int = 1
    retry_backoff_seconds: float = 0.5
    shell_allow_prefixes: List[str] = Field(default_factory=list)
    shell_deny_patterns: List[str] = Field(default_factory=list)
    shell_sandbox_enabled: bool = True
    shell_sandbox_base_url: str = "http://localhost:8061"
    shell_sandbox_timeout_seconds: float = 30.0
    injection_probe_enabled: bool = True


class ObservabilityConfig(BaseModel):
    """观测配置。"""

    enabled: bool = True
    log_events: bool = True
    max_events: int = 1000
    persist_enabled: bool = True
    persist_dir: str = "../.run/logs/runtime-traces"
    # OpenTelemetry 导出为纯增量旁路，默认关闭；开启后失败静默降级，权威取证数据仍在 JSONL。
    otel_enabled: bool = False
    otel_endpoint: str = "http://localhost:4318/v1/traces"
    otel_service_name: str = "job-buddy-runtime"
    otel_timeout_seconds: float = 3.0


class MemoryConfig(BaseModel):
    """agent-memory 集成配置。

    默认关闭：开启后上下文装配阶段会调用 agent-memory 检索长期记忆,
    检索失败静默降级,不阻塞主链路。
    """

    enabled: bool = False
    base_url: str = "http://localhost:8030"
    timeout_seconds: float = 3.0
    top_k: int = 5
    default_scope: str = "session"


class McpServerConfig(BaseModel):
    """单个 MCP 服务接入配置。

    仅支持 streamable-http 传输。
    """

    enabled: bool = False
    transport: str = "streamable_http"
    url: str = ""
    name_prefix: str = ""
    tool_tag: str = ""
    timeout_seconds: int = 30
    headers: Dict[str, str] = Field(default_factory=dict)


class McpConfig(BaseModel):
    """MCP 接入总配置。"""

    enabled: bool = False
    connect_timeout_seconds: int = 10
    max_tools_per_server: int = 128
    max_catalog_bytes: int = 512 * 1024
    max_tool_name_chars: int = 128
    max_tool_description_chars: int = 4096
    max_schema_bytes: int = 64 * 1024
    max_schema_nodes: int = 2048
    max_schema_depth: int = 16
    max_result_bytes: int = 1024 * 1024
    max_result_items: int = 256
    max_result_nodes: int = 8192
    max_result_depth: int = 32
    servers: Dict[str, McpServerConfig] = Field(default_factory=dict)


class AppConfig(BaseModel):
    """完整 YAML 配置。"""

    llm_service: LLMServiceConfig = Field(default_factory=LLMServiceConfig)
    runtime: RuntimeConfig = Field(default_factory=RuntimeConfig)
    tool_search: ToolSearchConfig = Field(default_factory=ToolSearchConfig)
    web_search: WebSearchConfig = Field(default_factory=WebSearchConfig)
    web_fetch: WebFetchConfig = Field(default_factory=WebFetchConfig)
    checkpoint: CheckpointConfig = Field(default_factory=CheckpointConfig)
    permission: PermissionConfig = Field(default_factory=PermissionConfig)
    transcript_review: TranscriptReviewConfig = Field(default_factory=TranscriptReviewConfig)
    tool_runtime: ToolRuntimeConfig = Field(default_factory=ToolRuntimeConfig)
    observability: ObservabilityConfig = Field(default_factory=ObservabilityConfig)
    memory: MemoryConfig = Field(default_factory=MemoryConfig)
    mcp: McpConfig = Field(default_factory=McpConfig)


class RuntimeSettings(BaseModel):
    """运行时配置访问器。

    常用配置通过只读属性访问，完整嵌套配置通过 `config` 访问。
    环境变量由 config.yaml 中的占位符解析,避免 JOB_BUDDY_CONFIG 被 pydantic-settings
    误当作嵌套 config JSON 解析导致 Runtime 启动失败。
    """

    config_path: str = "config/config.yaml"
    config: AppConfig = Field(default_factory=AppConfig)

    model_config = ConfigDict(arbitrary_types_allowed=True)

    @classmethod
    def load(cls, config_path: str = None) -> "RuntimeSettings":
        use_dotenv = config_path is None
        if use_dotenv:
            _load_dotenv_files()
        path = (
            config_path
            or os.getenv("JOB_BUDDY_CONFIG")
            or _dotenv_values.get("JOB_BUDDY_CONFIG")
            or "config/config.yaml"
        )
        yaml_data = _load_yaml(path, load_dotenv=use_dotenv)
        return cls(config_path=path, config=AppConfig.model_validate(yaml_data))

    @property
    def app_name(self) -> str:
        return self.config.runtime.app_name

    @property
    def model_base_url(self) -> str:
        return self.config.llm_service.base_url

    @property
    def model_api_key(self) -> str:
        return self.config.llm_service.api_key

    @property
    def model_name(self) -> str:
        return self.config.llm_service.model_name

    @property
    def model_timeout_seconds(self) -> int:
        return self.config.llm_service.timeout_seconds

    @property
    def max_turns(self) -> int:
        return self.config.runtime.max_turns

    @property
    def max_tool_calls(self) -> int:
        return self.config.runtime.max_tool_calls

    @property
    def max_failures(self) -> int:
        return self.config.runtime.max_failures

    @property
    def max_run_tokens(self) -> int:
        return self.config.runtime.max_run_tokens

    @property
    def tool_timeout_seconds(self) -> int:
        return self.config.runtime.tool_timeout_seconds

    @property
    def workspace_dir(self) -> str:
        return self.config.runtime.workspace_dir

    @property
    def profiles_dir(self) -> str:
        return self.config.runtime.profiles_dir

    @property
    def workflows_dir(self) -> str:
        return self.config.runtime.workflows_dir

    @property
    def prompts_dir(self) -> str:
        return self.config.runtime.prompts_dir

    @property
    def business_metadata_keys(self) -> List[str]:
        return self.config.runtime.business_metadata_keys


_ENV_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}")
_settings_lock = RLock()
_settings_cache: RuntimeSettings | None = None
_dotenv_values: Dict[str, str] = {}


def _load_yaml(config_path: str, load_dotenv: bool = True) -> Dict[str, Any]:
    if load_dotenv:
        _load_dotenv_files()
    path = Path(config_path)
    if not path.is_absolute() and not path.exists():
        module_path = _module_root() / path
        if module_path.exists():
            path = module_path
    if not path.exists():
        return {}
    with path.open("r", encoding="utf-8") as f:
        return _resolve_env_placeholders(yaml.safe_load(f) or {}, use_dotenv=load_dotenv)


def _module_root() -> Path:
    return Path(__file__).resolve().parents[3]


def _project_root() -> Path:
    return _module_root().parent


def _load_dotenv_files():
    """加载本地 .env，保留已导出的环境变量。"""
    for env_path in [_project_root() / ".env", _module_root() / ".env"]:
        if not env_path.exists() or not env_path.is_file():
            continue
        try:
            for raw_line in env_path.read_text(encoding="utf-8").splitlines():
                line = raw_line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                key = key.strip()
                if not key or key in os.environ or key in _dotenv_values:
                    continue
                _dotenv_values[key] = _strip_env_value(value.strip())
        except OSError:
            continue


def _strip_env_value(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
        return value[1:-1]
    return value


def _resolve_env_placeholders(value: Any, use_dotenv: bool = True) -> Any:
    """解析 YAML 中的 ${ENV:default} 占位符。

    连接信息、密钥和外部服务地址只保存在环境变量中。
    """

    if isinstance(value, dict):
        return {key: _resolve_env_placeholders(item, use_dotenv=use_dotenv) for key, item in value.items()}
    if isinstance(value, list):
        return [_resolve_env_placeholders(item, use_dotenv=use_dotenv) for item in value]
    if not isinstance(value, str):
        return value

    def replace(match: re.Match[str]) -> str:
        env_name = match.group(1)
        default = match.group(2)
        if env_name in os.environ:
            return os.environ[env_name]
        if use_dotenv and env_name in _dotenv_values:
            return _dotenv_values[env_name]
        return default if default is not None else ""

    resolved = _ENV_PATTERN.sub(replace, value)
    return _coerce_scalar(resolved)


def _coerce_scalar(value: str) -> Any:
    lower = value.lower()
    if lower == "true":
        return True
    if lower == "false":
        return False
    if re.fullmatch(r"[-+]?\d+", value):
        return int(value)
    if re.fullmatch(r"[-+]?\d+\.\d+", value):
        return float(value)
    return value


def get_settings() -> RuntimeSettings:
    global _settings_cache
    with _settings_lock:
        if _settings_cache is None:
            _settings_cache = RuntimeSettings.load()
        return _settings_cache


def reload_settings(config_path: str | None = None) -> RuntimeSettings:
    global _settings_cache
    with _settings_lock:
        _settings_cache = RuntimeSettings.load(config_path)
        return _settings_cache


class SettingsProxy:
    """始终指向最新配置快照的动态代理。"""

    def __getattr__(self, item: str) -> Any:
        return getattr(get_settings(), item)


settings = SettingsProxy()
