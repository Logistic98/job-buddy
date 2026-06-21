"""沙箱 Runtime FastAPI 服务。"""

from __future__ import annotations

import time
import uuid
from typing import Callable

from fastapi import FastAPI, HTTPException
from loguru import logger

from ..core.config import SandboxRuntimeConfig
from ..core.exceptions import SandboxCommandNotFoundError, SandboxProcessError
from ..core.models import CodeSpec, ExecutionOptions, SandboxResult
from ..core.policies import SandboxPolicies
from ..sdk import SandboxClient
from .schemas import (
    CliRequest,
    CodeFileRequest,
    CommandRequest,
    PythonCodeRequest,
    SandboxResponse,
    ShellRequest,
)


def create_app() -> FastAPI:
    app = FastAPI(title="Job Buddy Sandbox Runtime Service", version="0.1.0")

    @app.get("/health")
    def health() -> dict:
        return {"code": 0, "message": "success", "data": {"status": "UP", "service": "agent-sandbox"}}

    @app.post("/v1/commands", response_model=SandboxResponse)
    def run_command(req: CommandRequest) -> SandboxResponse:
        if bool(req.argv) == bool(req.command):
            raise HTTPException(status_code=400, detail="argv 与 command 必须且只能提供一个")
        client = _client(req.policy)
        if req.argv is not None:
            return _execute("command", lambda: client.command(req.argv, **_options_kwargs(req.options)))
        return _execute("command", lambda: client.command_string(req.command or "", **_options_kwargs(req.options)))

    @app.post("/v1/cli", response_model=SandboxResponse)
    def run_cli(req: CliRequest) -> SandboxResponse:
        client = _client(req.policy)
        return _execute("cli", lambda: client.cli(req.executable, req.args, **_options_kwargs(req.options)))

    @app.post("/v1/shell", response_model=SandboxResponse)
    def run_shell(req: ShellRequest) -> SandboxResponse:
        client = _client(req.policy)
        return _execute("shell", lambda: client.shell(req.command, shell=req.shell, **_options_kwargs(req.options)))

    @app.post("/v1/python/code", response_model=SandboxResponse)
    def run_python_code(req: PythonCodeRequest) -> SandboxResponse:
        client = _client(req.policy)
        return _execute(
            "python_code",
            lambda: client.python_code(req.code, req.args, python_bin=req.python_bin, **_options_kwargs(req.options)),
        )

    @app.post("/v1/code-file", response_model=SandboxResponse)
    def run_code_file(req: CodeFileRequest) -> SandboxResponse:
        spec = CodeSpec(
            code=req.code,
            suffix=req.suffix,
            interpreter=req.interpreter,
            args=req.args,
            options=_execution_options(req.options),
        )
        client = _client(req.policy)
        return _execute("code_file", lambda: client.code_file(spec))

    return app


def _execute(op: str, runner: Callable[[], SandboxResult]) -> SandboxResponse:
    """统一执行沙箱命令：生成 request_id、计时、结构化日志与异常归类。"""
    request_id = uuid.uuid4().hex[:12]
    bound = logger.bind(service="agent-sandbox", request_id=request_id, op=op)
    started = time.monotonic()
    try:
        result = runner()
    except SandboxProcessError as exc:
        duration_ms = round((time.monotonic() - started) * 1000, 2)
        bound.warning(f"沙箱进程非零退出 returncode={exc.returncode} duration_ms={duration_ms}")
        raise HTTPException(
            status_code=422,
            detail={"request_id": request_id, "returncode": exc.returncode, "stdout": exc.stdout, "stderr": exc.stderr},
        ) from exc
    except SandboxCommandNotFoundError as exc:
        bound.error(f"沙箱可执行文件缺失 error={exc}")
        raise HTTPException(status_code=500, detail={"request_id": request_id, "message": str(exc)}) from exc
    except Exception as exc:  # noqa: BLE001 统一兜底，避免裸 500 traceback 泄漏到调用方
        bound.exception(f"沙箱执行异常 error={exc}")
        raise HTTPException(status_code=500, detail={"request_id": request_id, "message": str(exc)}) from exc
    duration_ms = round((time.monotonic() - started) * 1000, 2)
    bound.info(f"沙箱执行完成 returncode={result.returncode} ok={result.ok} duration_ms={duration_ms}")
    return _response(result)


def _config(policy) -> SandboxRuntimeConfig:
    if policy is None:
        return SandboxPolicies.no_network_readonly()
    return SandboxRuntimeConfig.from_dict(policy.model_dump(exclude_none=True))


def _client(policy) -> SandboxClient:
    return SandboxClient(_config(policy))


def _execution_options(options) -> ExecutionOptions:
    return ExecutionOptions(cwd=options.cwd, env=options.env, timeout=options.timeout, check=options.check)


def _options_kwargs(options) -> dict:
    return {"cwd": options.cwd, "env": options.env, "timeout": options.timeout, "check": options.check}


def _response(result) -> SandboxResponse:
    return SandboxResponse(ok=result.ok, returncode=result.returncode, stdout=result.stdout, stderr=result.stderr, args=result.args)
