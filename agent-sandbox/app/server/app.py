"""沙箱 Runtime FastAPI 服务。"""

from __future__ import annotations

import os
import shutil
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from fastapi import FastAPI, HTTPException
from loguru import logger

from ..core.config import FilesystemConfig, NetworkConfig, SandboxRuntimeConfig
from ..core.exceptions import SandboxCommandNotFoundError, SandboxProcessError
from ..core.models import CodeSpec, ExecutionOptions, SandboxResult
from ..core.policies import SandboxPolicies
from ..internal_auth import install_internal_auth
from ..sdk import SandboxClient
from .schemas import (
    CliRequest,
    CodeFileRequest,
    CommandRequest,
    PythonCodeRequest,
    SandboxResponse,
    ShellRequest,
)

_MAX_CONCURRENCY = max(1, int(os.getenv("AGENT_SANDBOX_MAX_CONCURRENCY", "4")))
_MAX_OUTPUT_CHARS = max(1024, int(os.getenv("AGENT_SANDBOX_MAX_OUTPUT_CHARS", "200000")))
_EXECUTION_SLOTS = threading.BoundedSemaphore(_MAX_CONCURRENCY)
_ALLOWED_ENV_KEYS = {"LANG", "LC_ALL", "LC_CTYPE"}


def create_app() -> FastAPI:
    app = FastAPI(title="Job Buddy Sandbox Runtime Service", version="1.0.0")
    install_internal_auth(app)

    @app.get("/health")
    def health() -> dict:
        return {"code": 200, "message": "success", "data": {"status": "UP", "service": "agent-sandbox"}}

    @app.post("/v1/commands", response_model=SandboxResponse)
    def run_command(req: CommandRequest) -> SandboxResponse:
        if bool(req.argv) == bool(req.command):
            raise HTTPException(status_code=400, detail="argv 与 command 必须且只能提供一个")
        if req.argv is not None:
            return _run_with_request(
                "command",
                req.policy,
                req.options,
                lambda client, options: client.command(req.argv or [], **_options_kwargs(options)),
            )
        return _run_with_request(
            "command",
            req.policy,
            req.options,
            lambda client, options: client.command_string(req.command or "", **_options_kwargs(options)),
        )

    @app.post("/v1/cli", response_model=SandboxResponse)
    def run_cli(req: CliRequest) -> SandboxResponse:
        return _run_with_request(
            "cli",
            req.policy,
            req.options,
            lambda client, options: client.cli(req.executable, req.args, **_options_kwargs(options)),
        )

    @app.post("/v1/shell", response_model=SandboxResponse)
    def run_shell(req: ShellRequest) -> SandboxResponse:
        return _run_with_request(
            "shell",
            req.policy,
            req.options,
            lambda client, options: client.shell(req.command, shell=req.shell, **_options_kwargs(options)),
        )

    @app.post("/v1/python/code", response_model=SandboxResponse)
    def run_python_code(req: PythonCodeRequest) -> SandboxResponse:
        return _run_with_request(
            "python_code",
            req.policy,
            req.options,
            lambda client, options: client.python_code(
                req.code,
                req.args,
                python_bin=req.python_bin,
                **_options_kwargs(options),
            ),
        )

    @app.post("/v1/code-file", response_model=SandboxResponse)
    def run_code_file(req: CodeFileRequest) -> SandboxResponse:
        return _run_with_request(
            "code_file",
            req.policy,
            req.options,
            lambda client, options: client.code_file(
                CodeSpec(
                    code=req.code,
                    suffix=req.suffix,
                    interpreter=req.interpreter,
                    args=req.args,
                    options=options,
                )
            ),
        )

    return app


@dataclass
class PreparedExecution:
    client: SandboxClient
    options: ExecutionOptions
    cleanup: Callable[[], None]


def _run_with_request(
    op: str, policy, options, runner: Callable[[SandboxClient, ExecutionOptions], SandboxResult]
) -> SandboxResponse:
    if not _EXECUTION_SLOTS.acquire(blocking=False):
        raise HTTPException(status_code=429, detail="沙箱并发已达上限，请稍后重试")
    prepared = None
    try:
        prepared = _prepare_execution(policy, options)
        return _execute(op, lambda: runner(prepared.client, prepared.options))
    finally:
        if prepared is not None:
            prepared.cleanup()
        _EXECUTION_SLOTS.release()


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
            detail={
                "request_id": request_id,
                "returncode": exc.returncode,
                "stdout": _bounded_output(exc.stdout),
                "stderr": _bounded_output(exc.stderr),
            },
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


def _prepare_execution(policy, options) -> PreparedExecution:
    raw_options = _execution_options(options)
    cwd, cleanup = _safe_cwd(raw_options.cwd)
    safe_options = ExecutionOptions(
        cwd=str(cwd),
        env=_safe_env(raw_options.env),
        timeout=raw_options.timeout,
        check=raw_options.check,
    )
    return PreparedExecution(
        client=SandboxClient(_effective_config(policy, cwd), cwd=cwd),
        options=safe_options,
        cleanup=cleanup,
    )


def _effective_config(policy, workspace: str | Path) -> SandboxRuntimeConfig:
    workspace_path = Path(workspace).expanduser().resolve()
    base = SandboxPolicies.workspace_readwrite(workspace_path)
    if policy is None:
        return base

    requested = SandboxRuntimeConfig.from_dict(policy.model_dump(exclude_none=True))
    return SandboxRuntimeConfig(
        network=NetworkConfig(
            allowedDomains=_narrow_allowed_domains(base.network.allowedDomains, requested.network.allowedDomains),
            deniedDomains=_dedupe([*base.network.deniedDomains, *requested.network.deniedDomains]),
            allowUnixSockets=_narrow_allowed_domains(base.network.allowUnixSockets, requested.network.allowUnixSockets),
            allowAllUnixSockets=base.network.allowAllUnixSockets and requested.network.allowAllUnixSockets,
            allowLocalBinding=base.network.allowLocalBinding and requested.network.allowLocalBinding,
        ),
        filesystem=FilesystemConfig(
            denyRead=_dedupe([*base.filesystem.denyRead, *requested.filesystem.denyRead]),
            allowRead=_narrow_allowed_paths(base.filesystem.allowRead, requested.filesystem.allowRead, workspace_path),
            allowWrite=_narrow_allowed_paths(
                base.filesystem.allowWrite,
                requested.filesystem.allowWrite,
                workspace_path,
                empty_means_none=True,
            ),
            denyWrite=_dedupe([*base.filesystem.denyWrite, *requested.filesystem.denyWrite]),
        ),
        ignoreViolations={},
        enableWeakerNestedSandbox=False,
        enableWeakerNetworkIsolation=False,
        mandatoryDenySearchDepth=base.mandatoryDenySearchDepth,
    )


def _execution_options(options) -> ExecutionOptions:
    return ExecutionOptions(cwd=options.cwd, env=options.env, timeout=options.timeout, check=options.check)


def _options_kwargs(options) -> dict:
    return {"cwd": options.cwd, "env": options.env, "timeout": options.timeout, "check": options.check}


def _response(result) -> SandboxResponse:
    return SandboxResponse(
        ok=result.ok,
        returncode=result.returncode,
        stdout=_bounded_output(result.stdout),
        stderr=_bounded_output(result.stderr),
        args=result.args[:256],
    )


def _safe_env(requested: dict[str, str] | None) -> dict[str, str]:
    if not requested:
        return {}
    return {key: str(value)[:256] for key, value in requested.items() if key in _ALLOWED_ENV_KEYS and value is not None}


def _bounded_output(value: str | None) -> str:
    text = str(value or "")
    if len(text) <= _MAX_OUTPUT_CHARS:
        return text
    return text[:_MAX_OUTPUT_CHARS] + "\n[OUTPUT_TRUNCATED]"


def _safe_cwd(raw_cwd) -> tuple[Path, Callable[[], None]]:
    if raw_cwd:
        path = Path(str(raw_cwd)).expanduser().resolve()
        if not _is_allowed_cwd(path):
            raise HTTPException(status_code=400, detail="cwd 必须位于系统临时目录下")
        path.mkdir(parents=True, exist_ok=True)
        return path, lambda: None

    path = Path(tempfile.mkdtemp(prefix="job-buddy-sandbox-work-")).resolve()
    return path, lambda: shutil.rmtree(path, ignore_errors=True)


def _is_allowed_cwd(path: Path) -> bool:
    roots = [Path(tempfile.gettempdir()), Path("/tmp"), Path("/private/tmp"), Path("/var/tmp")]
    configured_workspace = os.getenv("AGENT_SANDBOX_WORKSPACE_DIR", "").strip()
    if configured_workspace:
        roots.append(Path(configured_workspace))
    for root in roots:
        try:
            path.relative_to(root.expanduser().resolve())
            return True
        except ValueError:
            continue
    return False


def _narrow_allowed_domains(base_values: list[str], requested_values: list[str]) -> list[str]:
    if not requested_values:
        return list(base_values)
    requested = set(requested_values)
    return [value for value in base_values if value in requested]


def _narrow_allowed_paths(
    base_values: list[str],
    requested_values: list[str],
    workspace: Path,
    *,
    empty_means_none: bool = False,
) -> list[str]:
    if not requested_values:
        return [] if empty_means_none else list(base_values)

    narrowed: list[str] = []
    for base_value in base_values:
        base_path = Path(base_value).expanduser().resolve()
        for requested_value in requested_values:
            requested_path = _resolve_policy_path(requested_value, workspace)
            if _contains(requested_path, base_path):
                narrowed.append(str(base_path))
            elif _contains(base_path, requested_path):
                narrowed.append(str(requested_path))
    return _dedupe(narrowed)


def _resolve_policy_path(value: str, workspace: Path) -> Path:
    path = Path(value).expanduser()
    if not path.is_absolute():
        path = workspace / path
    return path.resolve()


def _contains(parent: Path, child: Path) -> bool:
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


def _dedupe(values: list[str]) -> list[str]:
    result: list[str] = []
    for value in values:
        if value not in result:
            result.append(value)
    return result
