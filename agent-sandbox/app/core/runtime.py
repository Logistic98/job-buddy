"""Anthropic Sandbox Runtime CLI（srt）底层适配器。"""

from __future__ import annotations

import asyncio
import json
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Mapping, Sequence

from .config import SandboxRuntimeConfig, default_config
from .exceptions import SandboxCommandNotFoundError, SandboxProcessError
from .models import SandboxResult


class SandboxRuntime:
    """srt CLI 的 Python 门面。"""

    def __init__(
        self,
        config: SandboxRuntimeConfig | Mapping | None = None,
        *,
        srt_bin: str = "srt",
        cwd: str | Path | None = None,
        env: Mapping[str, str] | None = None,
        keep_settings_file: bool = False,
        max_output_bytes: int | None = None,
    ) -> None:
        self.config = (
            config
            if isinstance(config, SandboxRuntimeConfig)
            else SandboxRuntimeConfig.from_dict(dict(config or default_config().to_dict()))
        )
        self.srt_bin = srt_bin
        self.cwd = Path(cwd).resolve() if cwd else None
        self.env = dict(env or {})
        self.keep_settings_file = keep_settings_file
        configured_max = max_output_bytes
        if configured_max is None:
            try:
                configured_max = int(os.getenv("AGENT_SANDBOX_MAX_CAPTURE_BYTES", str(1024 * 1024)))
            except ValueError:
                configured_max = 1024 * 1024
        self.max_output_bytes = max(4096, min(configured_max, 16 * 1024 * 1024))

    def ensure_available(self) -> str:
        resolved = shutil.which(self.srt_bin)
        if not resolved:
            raise SandboxCommandNotFoundError("未找到 srt CLI，请先安装：npm install -g @anthropic-ai/sandbox-runtime")
        return resolved

    def run(
        self,
        command: str | Sequence[str],
        *,
        timeout: float | None = None,
        check: bool = True,
        cwd: str | Path | None = None,
        env: Mapping[str, str] | None = None,
        text: bool = True,
    ) -> SandboxResult:
        """在 sandbox-runtime 中执行命令。"""

        srt = self.ensure_available()
        settings_path = self._write_settings_file()
        try:
            args = [srt, "--settings", str(settings_path)]
            if isinstance(command, str):
                args.append(command)
            else:
                args.extend(str(part) for part in command)

            merged_env = self._minimal_env()
            merged_env.update(self.env)
            if env:
                merged_env.update({str(key): str(value) for key, value in env.items()})

            # stdout/stderr 直接落临时文件，避免不可信进程的大输出被 capture_output
            # 无界聚合到 Python 堆内存。进程结束后只读取配置允许的最大字节数。
            with tempfile.TemporaryFile(mode="w+b") as stdout_file, tempfile.TemporaryFile(mode="w+b") as stderr_file:
                proc = subprocess.run(
                    args,
                    cwd=str(Path(cwd).resolve()) if cwd else (str(self.cwd) if self.cwd else None),
                    env=merged_env,
                    stdout=stdout_file,
                    stderr=stderr_file,
                    timeout=timeout,
                )
                result = SandboxResult(
                    args=args,
                    returncode=proc.returncode,
                    stdout=self._read_limited_output(stdout_file, text=text),
                    stderr=self._read_limited_output(stderr_file, text=text),
                )
            if check and not result.ok:
                raise SandboxProcessError(
                    f"沙箱命令退出码：{result.returncode}",
                    returncode=result.returncode,
                    stdout=result.stdout,
                    stderr=result.stderr,
                )
            return result
        finally:
            if not self.keep_settings_file:
                try:
                    settings_path.unlink(missing_ok=True)
                except OSError:
                    pass

    def _read_limited_output(self, stream, *, text: bool) -> str:
        stream.flush()
        size = stream.tell()
        stream.seek(0)
        data = stream.read(self.max_output_bytes)
        output = data.decode("utf-8", errors="replace")
        if size > self.max_output_bytes:
            output += f"\n...[output truncated: {size - self.max_output_bytes} bytes omitted]"
        return output

    async def arun(self, command: str | Sequence[str], **kwargs) -> SandboxResult:
        """run 的异步封装。"""

        return await asyncio.to_thread(self.run, command, **kwargs)

    def run_cli(self, executable: str, args: Sequence[str] | None = None, **kwargs) -> SandboxResult:
        """在沙箱中执行 CLI argv。"""

        return self.run([executable, *(str(item) for item in (args or []))], **kwargs)

    def run_shell(self, command: str, *, shell: str = "/bin/sh", **kwargs) -> SandboxResult:
        """在沙箱中执行 Shell 命令。"""

        return self.run([shell, "-lc", command], **kwargs)

    def run_python(
        self,
        script: str | Path,
        args: Sequence[str] | None = None,
        *,
        python_bin: str | None = None,
        **kwargs,
    ) -> SandboxResult:
        """在沙箱中执行 Python 脚本。"""

        argv = [python_bin or sys.executable, str(script)]
        if args:
            argv.extend(str(item) for item in args)
        return self.run(argv, **kwargs)

    def run_python_code(
        self,
        code: str,
        args: Sequence[str] | None = None,
        *,
        python_bin: str | None = None,
        **kwargs,
    ) -> SandboxResult:
        """通过 python -c 在沙箱中执行代码。"""

        argv = [python_bin or sys.executable, "-c", code]
        if args:
            argv.extend(str(item) for item in args)
        return self.run(argv, **kwargs)

    def run_code_file(
        self,
        code: str,
        *,
        suffix: str = ".py",
        interpreter: str | Sequence[str] | None = None,
        args: Sequence[str] | None = None,
        **kwargs,
    ) -> SandboxResult:
        """写入临时代码文件并在沙箱中执行。"""

        parent_cwd = kwargs.pop("cwd", None)
        temp_parent = str(Path(parent_cwd).resolve()) if parent_cwd else None
        with tempfile.TemporaryDirectory(prefix="job-buddy-sandbox-code-", dir=temp_parent) as temp_dir:
            code_path = Path(temp_dir) / f"main{suffix}"
            code_path.write_text(code, encoding="utf-8")
            if interpreter is None:
                command = [sys.executable, str(code_path)]
            elif isinstance(interpreter, str):
                command = [interpreter, str(code_path)]
            else:
                command = [*(str(item) for item in interpreter), str(code_path)]
            if args:
                command.extend(str(item) for item in args)
            return self.run(command, cwd=temp_dir, **kwargs)

    @staticmethod
    def quote_args(args: Sequence[str]) -> str:
        """返回 shell 转义后的命令字符串。"""

        return " ".join(shlex.quote(str(item)) for item in args)

    def _write_settings_file(self) -> Path:
        fd, path = tempfile.mkstemp(prefix="srt-settings-", suffix=".json")
        settings_path = Path(path)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(self.config.to_dict(), f, ensure_ascii=False, indent=2)
        return settings_path

    def _minimal_env(self) -> dict[str, str]:
        env = {
            "PATH": os.environ.get("PATH", os.defpath),
            "TMPDIR": os.environ.get("TMPDIR", tempfile.gettempdir()),
        }
        for key in ("LANG", "LC_ALL", "LC_CTYPE"):
            value = os.environ.get(key)
            if value:
                env[key] = value
        return env
