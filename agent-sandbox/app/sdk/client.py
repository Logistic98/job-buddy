"""Anthropic Sandbox Runtime 高层 SDK。"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Mapping, Sequence

from ..core.config import SandboxRuntimeConfig
from ..core.models import CodeSpec, CommandSpec, ExecutionOptions, SandboxResult
from ..core.policies import SandboxPolicies
from ..core.runtime import SandboxRuntime


class SandboxClient:
    """生产用沙箱客户端。

    封装临时配置文件，提供命令、CLI、Shell、脚本、内联 Python 和通用代码文件执行入口。
    """

    def __init__(
        self,
        config: SandboxRuntimeConfig | Mapping | None = None,
        *,
        srt_bin: str | None = None,
        cwd: str | Path | None = None,
        env: Mapping[str, str] | None = None,
        default_timeout: float | None = None,
        keep_settings_file: bool = False,
    ) -> None:
        self.default_timeout = default_timeout
        resolved_srt_bin = srt_bin or os.getenv("AGENT_SANDBOX_SRT_BIN") or "srt"
        self._runtime = SandboxRuntime(
            config or SandboxPolicies.no_network_readonly(),
            srt_bin=resolved_srt_bin,
            cwd=cwd,
            env=env,
            keep_settings_file=keep_settings_file,
        )

    @property
    def runtime(self) -> SandboxRuntime:
        return self._runtime

    def execute(self, spec: CommandSpec) -> SandboxResult:
        options = self._merge_options(spec.options)
        command: str | Sequence[str] = spec.command if spec.command is not None else list(spec.argv or [])
        return self._runtime.run(
            command,
            timeout=options.timeout,
            check=options.check,
            cwd=options.cwd,
            env=options.env,
        )

    async def aexecute(self, spec: CommandSpec) -> SandboxResult:
        options = self._merge_options(spec.options)
        command: str | Sequence[str] = spec.command if spec.command is not None else list(spec.argv or [])
        return await self._runtime.arun(
            command,
            timeout=options.timeout,
            check=options.check,
            cwd=options.cwd,
            env=options.env,
        )

    def command(self, argv: Sequence[str], **kwargs) -> SandboxResult:
        return self.execute(CommandSpec.from_argv(argv, self._options_from_kwargs(**kwargs)))

    def command_string(self, command: str, **kwargs) -> SandboxResult:
        return self.execute(CommandSpec.from_string(command, self._options_from_kwargs(**kwargs)))

    def cli(self, executable: str, args: Sequence[str] | None = None, **kwargs) -> SandboxResult:
        return self.command([executable, *(str(item) for item in (args or []))], **kwargs)

    def shell(self, command: str, *, shell: str = "/bin/sh", **kwargs) -> SandboxResult:
        return self.command([shell, "-lc", command], **kwargs)

    def python_script(
        self,
        script: str | Path,
        args: Sequence[str] | None = None,
        *,
        python_bin: str | None = None,
        **kwargs,
    ) -> SandboxResult:
        return self.command([python_bin or sys.executable, str(script), *(str(item) for item in (args or []))], **kwargs)

    def python_code(
        self,
        code: str,
        args: Sequence[str] | None = None,
        *,
        python_bin: str | None = None,
        **kwargs,
    ) -> SandboxResult:
        return self.command([python_bin or sys.executable, "-c", code, *(str(item) for item in (args or []))], **kwargs)

    def code_file(self, spec: CodeSpec) -> SandboxResult:
        options = self._merge_options(spec.options)
        return self._runtime.run_code_file(
            spec.code,
            suffix=spec.suffix,
            interpreter=spec.interpreter,
            args=spec.args,
            timeout=options.timeout,
            check=options.check,
            cwd=options.cwd,
            env=options.env,
        )

    @staticmethod
    def _options_from_kwargs(
        *,
        cwd: str | Path | None = None,
        env: Mapping[str, str] | None = None,
        timeout: float | None = None,
        check: bool = True,
    ) -> ExecutionOptions:
        return ExecutionOptions(cwd=cwd, env=env, timeout=timeout, check=check)

    def _merge_options(self, options: ExecutionOptions) -> ExecutionOptions:
        return ExecutionOptions(
            cwd=options.cwd,
            env=options.env,
            timeout=options.timeout if options.timeout is not None else self.default_timeout,
            check=options.check,
        )
