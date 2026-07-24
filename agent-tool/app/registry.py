"""工具注册表。

每个工具按 8 要素登记:名称、描述、参数、返回、错误、权限、示例、评测。
评测入口统一指向 tests/ 下的对应用例。
"""

TOOLS: list[dict] = [
    {
        "name": "core_trace_summarize",
        "description": "把核心链路 Trace 事件数组压缩为可展示摘要。适用于 UI 过程面板与评估取证;不适用于原始日志全文检索。",
        "permission": "read",
        "parameters": {
            "events": {"type": "array", "required": True, "description": "runtime /trace-events 返回的事件数组"},
        },
        "returns": "data 含 total_events、event_counts、run_ids、error_count、errors、started_at、ended_at",
        "errors": ["invalid_arguments"],
        "example": {
            "arguments": {"events": [{"event": "run_start", "run_id": "run_1", "timestamp": "2026-01-01 00:00:00"}]}
        },
        "eval": "tests/test_tools.py::test_trace_summarize_counts_events",
    },
    {
        "name": "memory_search",
        "description": "检索 agent-memory 中的会话或长期记忆片段。适用于上下文装配前的记忆召回;不适用于全文模糊搜索大语料。",
        "permission": "read",
        "parameters": {
            "query": {"type": "string", "required": True, "description": "检索关键词"},
            "scope": {"type": "string", "required": False, "description": "记忆范围,如 session"},
        },
        "returns": "data 为记忆条目数组,每条含 id、scope、content、created_at",
        "errors": ["invalid_arguments", "memory_timeout", "memory_unavailable"],
        "example": {"arguments": {"query": "Java 岗位偏好", "scope": "session"}},
        "eval": "tests/test_tools.py::test_memory_search_requires_query",
    },
    {
        "name": "sandbox_execute",
        "description": "通过 agent-sandbox 在隔离环境执行不可信 shell 命令。高风险工具,必须显式 confirm;不适用于长驻进程。",
        "permission": "high_risk",
        "parameters": {
            "command": {"type": "string", "required": True, "description": "沙箱内执行的 shell 命令"},
            "timeout": {"type": "number", "required": False, "description": "命令超时秒数"},
        },
        "returns": "data 含 returncode、stdout、stderr",
        "errors": ["invalid_arguments", "command_failed", "sandbox_timeout", "sandbox_unavailable"],
        "example": {"arguments": {"command": "echo hello"}, "confirm": True},
        "eval": "tests/test_tools.py::test_sandbox_execute_requires_confirm",
    },
    {
        "name": "boss_browser",
        "description": "Boss 直聘 boss-cli 工具。默认使用 Backend 注入的持久化凭据和二维码登录，浏览器 Cookie 导入仅在显式开启时使用；支持登录态、岗位搜索、详情、在线简历和限速快照。",
        "permission": "medium_risk",
        "parameters": {
            "operation": {
                "type": "string",
                "required": True,
                "description": "status、refresh_auth、qr_start、qr_status、qr_cancel、search、favorite_list、detail、profile、rate",
            },
            "payload": {"type": "object", "required": False, "description": "search/detail 等操作参数"},
        },
        "returns": "data 为 Boss 业务 {code,message,data} 响应信封",
        "errors": [
            "invalid_arguments",
            "boss_auth_required",
            "boss_risk_control",
            "boss_rate_limited",
            "boss_browser_error",
        ],
        "example": {"arguments": {"operation": "rate", "payload": {}}},
        "eval": "tests/test_boss_browser_tool.py::test_boss_browser_rate_operation_returns_envelope",
    },
]


def list_tools() -> list[dict]:
    return TOOLS


def get_tool(name: str) -> dict | None:
    for tool in TOOLS:
        if tool["name"] == name:
            return tool
    return None
