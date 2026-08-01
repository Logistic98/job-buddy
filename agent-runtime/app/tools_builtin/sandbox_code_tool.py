"""通过 agent-sandbox 执行固定语言的候选代码，不接受调用方扩张执行策略。"""

from __future__ import annotations

import os
import re
from hashlib import sha256
from typing import Any, Dict

import httpx

from app.core.common.constants import ToolKind, ToolRiskLevel
from app.core.common.settings import settings
from app.core.tool.base import BaseTool, ToolExecutionContext, ToolExecutionFailure, ValidationResult

_MAX_CODE_CHARS = 200_000
_MAX_ARGS = 32
_MAX_ARG_CHARS = 512
_MAX_DEPENDENCIES = 8
_MAX_DEPENDENCY_CHARS = 128
_OUTPUT_CHARS = 12_000
_CODE_DETAIL_CHARS = 40_000
_CODE_TIMEOUT_SECONDS = 25.0
_DEPENDENCY_TIMEOUT_SECONDS = 90.0
_DEPENDENCY_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}(?:==[A-Za-z0-9][A-Za-z0-9.!+_-]{0,63})?$")
_LANGUAGE_RUNTIME = {
    "python": (".py", ["python3"]),
    "javascript": (".js", ["node"]),
    "java": (".java", ["java"]),
    "shell": (".sh", ["/bin/sh"]),
}


class SandboxCodeExecuteTool(BaseTool):
    """在一次性工作目录中执行模型生成的候选代码。"""

    name = "sandbox_code_execute"
    aliases = ["code_execute", "run_code", "python_execute"]
    search_hint = "在隔离沙箱执行 Python JavaScript Java Shell 代码并验证输出"
    description = (
        "在 agent-sandbox 的一次性临时目录中执行候选代码并返回退出码、stdout 和 stderr。"
        "Python 可声明至多 8 个 PyPI 依赖，由 Sandbox 在一次性目录中安装 wheel；"
        "代码执行阶段仍关闭网络。仅支持固定语言枚举，不允许指定解释器、cwd、网络或文件系统策略。"
    )
    kind = ToolKind.CODE
    risk_level = ToolRiskLevel.MEDIUM
    timeout_seconds = 125
    max_retries = 0
    input_schema = {
        "type": "object",
        "properties": {
            "language": {
                "type": "string",
                "enum": sorted(_LANGUAGE_RUNTIME),
                "description": "固定语言标识",
            },
            "code": {
                "type": "string",
                "minLength": 1,
                "maxLength": _MAX_CODE_CHARS,
                "description": "需要在沙箱中验证的完整候选代码",
            },
            "args": {
                "type": "array",
                "items": {"type": "string", "maxLength": _MAX_ARG_CHARS},
                "maxItems": _MAX_ARGS,
                "default": [],
            },
            "dependencies": {
                "type": "array",
                "items": {
                    "type": "string",
                    "maxLength": _MAX_DEPENDENCY_CHARS,
                    "pattern": _DEPENDENCY_PATTERN.pattern,
                },
                "maxItems": _MAX_DEPENDENCIES,
                "default": [],
                "description": "仅 Python 可用；PyPI 包名或 package==version，不接受 URL、VCS、路径或安装参数",
            },
        },
        "required": ["language", "code"],
        "additionalProperties": False,
    }
    output_schema = {
        "type": "object",
        "properties": {
            "language": {"type": "string"},
            "exit_code": {"type": "integer"},
            "stdout": {"type": "string"},
            "stderr": {"type": "string"},
            "sandboxed": {"type": "boolean", "const": True},
            "dependencies": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["language", "exit_code", "stdout", "stderr", "sandboxed"],
    }
    tags = ["sandbox", "code", "python", "javascript", "java", "shell", "verification"]
    read_only = True
    destructive = False
    concurrency_safe = True

    def _normalize_output(self, tool_call, output: Any) -> tuple[Any, Dict[str, Any]]:
        """在 ToolResult 专用元数据中附带有界源码，供鉴权会话展示而不污染工具观察。"""

        normalized_output, base_metadata = super()._normalize_output(tool_call, output)
        code = str(tool_call.arguments.get("code") or "")
        execution_detail = {
            "code": code[:_CODE_DETAIL_CHARS],
            "code_chars": len(code),
            "code_sha256": sha256(code.encode("utf-8", errors="replace")).hexdigest(),
            "code_truncated": len(code) > _CODE_DETAIL_CHARS,
        }
        return normalized_output, {**base_metadata, "execution_detail": execution_detail}

    async def validate_input(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> ValidationResult:
        base = await super().validate_input(arguments, context)
        if not base.result:
            return base
        language = str(arguments.get("language") or "").strip().lower()
        if language not in _LANGUAGE_RUNTIME:
            return ValidationResult(
                result=False,
                message=f"language 必须是以下固定值之一: {', '.join(sorted(_LANGUAGE_RUNTIME))}",
                error_code=400,
            )
        code = arguments.get("code")
        if not isinstance(code, str) or not code.strip():
            return ValidationResult(result=False, message="code 不能为空", error_code=400)
        if len(code) > _MAX_CODE_CHARS:
            return ValidationResult(
                result=False,
                message=f"code 长度不能超过 {_MAX_CODE_CHARS} 字符",
                error_code=400,
            )
        if "\x00" in code:
            return ValidationResult(result=False, message="code 不能包含 NUL 字符", error_code=400)
        args = arguments.get("args", [])
        if not isinstance(args, list) or len(args) > _MAX_ARGS:
            return ValidationResult(result=False, message=f"args 最多允许 {_MAX_ARGS} 项", error_code=400)
        if any(not isinstance(item, str) or len(item) > _MAX_ARG_CHARS for item in args):
            return ValidationResult(
                result=False,
                message=f"args 每项必须是长度不超过 {_MAX_ARG_CHARS} 的字符串",
                error_code=400,
            )
        dependencies = arguments.get("dependencies", [])
        if not isinstance(dependencies, list) or len(dependencies) > _MAX_DEPENDENCIES:
            return ValidationResult(
                result=False,
                message=f"dependencies 最多允许 {_MAX_DEPENDENCIES} 项",
                error_code=400,
            )
        if dependencies and language != "python":
            return ValidationResult(result=False, message="dependencies 仅 Python 代码执行可用", error_code=400)
        seen_dependencies: set[str] = set()
        for dependency in dependencies:
            if (
                not isinstance(dependency, str)
                or len(dependency) > _MAX_DEPENDENCY_CHARS
                or not _DEPENDENCY_PATTERN.fullmatch(dependency.strip())
            ):
                return ValidationResult(
                    result=False,
                    message="Python 依赖格式仅支持 PyPI 包名或 package==version",
                    error_code=400,
                )
            identity = dependency.split("==", 1)[0].lower().replace("_", "-").replace(".", "-")
            if identity in seen_dependencies:
                return ValidationResult(result=False, message=f"Python 依赖重复: {dependency}", error_code=400)
            seen_dependencies.add(identity)
        unexpected = set(arguments) - {"language", "code", "args", "dependencies"}
        if unexpected:
            return ValidationResult(
                result=False,
                message=f"不支持的代码执行参数: {', '.join(sorted(unexpected))}",
                error_code=400,
            )
        return ValidationResult(result=True)

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Dict[str, Any]:
        language = str(arguments["language"]).strip().lower()
        suffix, interpreter = _LANGUAGE_RUNTIME[language]
        config = settings.config.tool_runtime
        base_url = config.shell_sandbox_base_url.rstrip("/")
        dependencies = [str(item).strip() for item in arguments.get("dependencies") or []]
        operation_timeout = _CODE_TIMEOUT_SECONDS
        timeout = operation_timeout + (_DEPENDENCY_TIMEOUT_SECONDS if dependencies else 0.0) + 5.0
        payload = {
            "code": arguments["code"],
            "suffix": suffix,
            "interpreter": interpreter,
            "args": list(arguments.get("args") or []),
            "dependencies": dependencies,
            "policy": {
                "network": {
                    "allowedDomains": [],
                    "deniedDomains": [],
                    "allowUnixSockets": [],
                    "allowAllUnixSockets": False,
                    "allowLocalBinding": False,
                },
                "filesystem": {
                    "allowRead": [],
                    "denyRead": ["~/.ssh", "~/.aws", "~/.config/gcloud", "~/.kube"],
                    "allowWrite": [],
                    "denyWrite": [".env", "secrets/", ".git/"],
                },
            },
            "options": {
                "timeout": operation_timeout,
                "check": False,
            },
        }
        if dependencies:
            payload["dependency_timeout"] = _DEPENDENCY_TIMEOUT_SECONDS
        headers = {}
        token = os.getenv("AGENT_INTERNAL_SERVICE_TOKEN", "").strip()
        if token:
            headers["X-Internal-Service-Token"] = token

        body = None
        last_error: Exception | None = None
        async with httpx.AsyncClient(timeout=timeout, trust_env=False) as client:
            try:
                response = await client.post(
                    f"{base_url}/v1/code-file",
                    json=payload,
                    headers=headers,
                )
                response.raise_for_status()
                body = response.json()
            except (httpx.TimeoutException, httpx.ConnectError, httpx.HTTPStatusError, ValueError) as exc:
                last_error = exc

        if body is None:
            if isinstance(last_error, httpx.TimeoutException):
                raise RuntimeError(f"agent-sandbox 代码执行超时（{timeout}s），未在宿主机回退执行") from last_error
            raise RuntimeError("agent-sandbox 代码执行不可用，未在宿主机回退执行") from last_error
        if not isinstance(body, dict):
            raise RuntimeError("agent-sandbox 返回结构非法，未在宿主机回退执行")
        return_code = body.get("returncode")
        if not isinstance(return_code, int) or isinstance(return_code, bool):
            raise RuntimeError("agent-sandbox 返回的退出码非法")
        stdout = str(body.get("stdout") or "")[-_OUTPUT_CHARS:]
        stderr = str(body.get("stderr") or "")[-_OUTPUT_CHARS:]
        evidence = {
            "language": language,
            "exit_code": return_code,
            "stdout": stdout,
            "stderr": stderr,
            "sandboxed": True,
        }
        if dependencies:
            evidence["dependencies"] = dependencies
        if return_code != 0:
            raise ToolExecutionFailure(
                f"候选代码在 agent-sandbox 中执行失败，退出码 {return_code}",
                output=evidence,
            )
        return evidence
