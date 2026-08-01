"""Anthropic Sandbox Runtime CLI（srt）底层适配器。"""

from __future__ import annotations

import asyncio
import json
import os
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Mapping, Sequence

from .config import SandboxRuntimeConfig, default_config
from .dependencies import normalize_python_dependencies
from .exceptions import SandboxCommandNotFoundError, SandboxProcessError, SandboxRuntimeError
from .models import SandboxResult

_PYPI_INDEX_URL = "https://pypi.org/simple"
_PYPI_ALLOWED_DOMAINS = ["pypi.org", "files.pythonhosted.org"]
_SRT_PRIVATE_TMP = "/tmp/claude"
_DEFAULT_DEPENDENCY_CACHE_DIR = Path(tempfile.gettempdir()) / "job-buddy-sandbox-uv-cache"
_DEPENDENCY_CACHE_ENV = "AGENT_SANDBOX_DEPENDENCY_CACHE_DIR"
_DEPENDENCY_ROOT_ENV = "AGENT_SANDBOX_DEPENDENCY_ROOT"


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
        max_process_output_bytes: int | None = None,
    ) -> None:
        self.config = (
            config
            if isinstance(config, SandboxRuntimeConfig)
            else SandboxRuntimeConfig.from_dict(dict(config or default_config().to_dict()))
        )
        dependency_root = self._configured_dependency_root()
        if dependency_root is not None:
            dependency_root_path = str(dependency_root)
            self.config.filesystem.denyRead = list(
                dict.fromkeys([*self.config.filesystem.denyRead, dependency_root_path])
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
        configured_process_max = max_process_output_bytes
        if configured_process_max is None:
            try:
                configured_process_max = int(os.getenv("AGENT_SANDBOX_MAX_PROCESS_OUTPUT_BYTES", str(16 * 1024 * 1024)))
            except ValueError:
                configured_process_max = 16 * 1024 * 1024
        self.max_process_output_bytes = max(
            self.max_output_bytes,
            min(configured_process_max, 64 * 1024 * 1024),
        )

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
            # Commander 会继续解析位置参数中的 `-c`、`-V` 等选项。显式使用
            # `--` 终止 srt 自身的选项解析，保证 Python、Node 等子命令参数原样传递。
            args = [srt, "--settings", str(settings_path), "--"]
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
                proc = subprocess.Popen(
                    args,
                    cwd=str(Path(cwd).resolve()) if cwd else (str(self.cwd) if self.cwd else None),
                    env=merged_env,
                    stdout=stdout_file,
                    stderr=stderr_file,
                    start_new_session=True,
                )
                try:
                    self._wait_with_limits(
                        proc,
                        stdout_file=stdout_file,
                        stderr_file=stderr_file,
                        timeout=timeout,
                    )
                except subprocess.TimeoutExpired as exc:
                    raise subprocess.TimeoutExpired(["sandbox-runtime"], exc.timeout) from None
                finally:
                    _terminate_process_group(proc)
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

    def _wait_with_limits(self, proc, *, stdout_file, stderr_file, timeout: float | None) -> None:
        started = time.monotonic()
        while proc.poll() is None:
            total_output_bytes = self._stream_size(stdout_file) + self._stream_size(stderr_file)
            if total_output_bytes > self.max_process_output_bytes:
                _terminate_process_group(proc)
                raise SandboxProcessError(
                    "沙箱输出超过限制",
                    returncode=proc.returncode if proc.returncode is not None else -9,
                    stdout=self._read_limited_output(stdout_file, text=True),
                    stderr=self._read_limited_output(stderr_file, text=True),
                )
            if timeout is not None:
                elapsed = time.monotonic() - started
                if elapsed >= timeout:
                    raise subprocess.TimeoutExpired(["sandbox-runtime"], timeout)
                wait_seconds = min(0.05, max(0.001, timeout - elapsed))
            else:
                wait_seconds = 0.05
            try:
                proc.wait(timeout=wait_seconds)
            except subprocess.TimeoutExpired:
                continue

        total_output_bytes = self._stream_size(stdout_file) + self._stream_size(stderr_file)
        if total_output_bytes > self.max_process_output_bytes:
            raise SandboxProcessError(
                "沙箱输出超过限制",
                returncode=proc.returncode if proc.returncode is not None else -9,
                stdout=self._read_limited_output(stdout_file, text=True),
                stderr=self._read_limited_output(stderr_file, text=True),
            )

    @staticmethod
    def _stream_size(stream) -> int:
        stream.flush()
        return os.fstat(stream.fileno()).st_size

    def _read_limited_output(self, stream, *, text: bool) -> str:
        stream.flush()
        size = self._stream_size(stream)
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
        dependencies: Sequence[str] | None = None,
        dependency_timeout: float | None = None,
        **kwargs,
    ) -> SandboxResult:
        """写入临时代码文件，可选安装一次性 Python 依赖后在沙箱中执行。"""

        parent_cwd = kwargs.pop("cwd", None)
        caller_env = dict(kwargs.pop("env", None) or {})
        temp_parent = str(Path(parent_cwd).resolve()) if parent_cwd else None
        with tempfile.TemporaryDirectory(prefix="job-buddy-sandbox-code-", dir=temp_parent) as temp_dir:
            dependency_workspace: tempfile.TemporaryDirectory[str] | None = None
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

            normalized_dependencies = normalize_python_dependencies(dependencies)
            code_env = {**caller_env, "PYTHONDONTWRITEBYTECODE": "1"}
            if normalized_dependencies:
                if suffix.lower() != ".py" or not self._is_python_interpreter(command[0]):
                    raise ValueError("dependencies 仅支持 Python 代码执行")
                python_bin = self._trusted_system_python()
                command[0] = python_bin
                dependency_dir = Path(temp_dir) / "site-packages"
                dependency_root = self._configured_dependency_root()
                if dependency_root is not None:
                    dependency_workspace = tempfile.TemporaryDirectory(
                        prefix="job-buddy-sandbox-deps-",
                        dir=dependency_root,
                    )
                    dependency_dir = Path(dependency_workspace.name) / "site-packages"
                try:
                    install_result = self._install_python_dependencies(
                        normalized_dependencies,
                        dependency_dir=dependency_dir,
                        python_bin=python_bin,
                        timeout=dependency_timeout if dependency_timeout is not None else kwargs.get("timeout"),
                    )
                    if not install_result.ok:
                        if kwargs.get("check", True):
                            raise SandboxProcessError(
                                f"Python 依赖安装失败，退出码：{install_result.returncode}",
                                returncode=install_result.returncode,
                                stdout=install_result.stdout,
                                stderr=install_result.stderr,
                            )
                        return install_result
                    code_env["PYTHONPATH"] = str(dependency_dir)
                    code_runtime = self._code_execution_runtime(dependency_dir)
                    return code_runtime.run(command, cwd=temp_dir, env=code_env, **kwargs)
                finally:
                    if dependency_workspace is not None:
                        dependency_workspace.cleanup()
            return self.run(command, cwd=temp_dir, env=code_env, **kwargs)

    def _install_python_dependencies(
        self,
        dependencies: Sequence[str],
        *,
        dependency_dir: Path,
        python_bin: str,
        timeout: float | None,
    ) -> SandboxResult:
        """在独立 srt 策略中安装 wheel，目录随本次代码执行销毁。"""

        uv_bin = shutil.which("uv")
        if not uv_bin:
            raise SandboxCommandNotFoundError("未找到 uv，无法创建一次性 Python 依赖环境")
        cache_dir = self._dependency_cache_dir()
        self._ensure_dependency_runtime_directories(cache_dir)
        dependency_dir.mkdir(parents=True, exist_ok=True)
        temp_dir = dependency_dir.parent
        install_runtime = SandboxRuntime(
            self._dependency_install_config(temp_dir, uv_bin=uv_bin, cache_dir=cache_dir),
            srt_bin=self.srt_bin,
            cwd=temp_dir,
            env=self.env,
            keep_settings_file=self.keep_settings_file,
            max_output_bytes=self.max_output_bytes,
            max_process_output_bytes=self.max_process_output_bytes,
        )
        uv_command = [
            uv_bin,
            "pip",
            "install",
            "--target",
            str(dependency_dir),
            "--python",
            python_bin,
            "--default-index",
            _PYPI_INDEX_URL,
            "--only-binary",
            ":all:",
            "--cache-dir",
            str(cache_dir),
            "--no-config",
            "--no-python-downloads",
            "--no-progress",
            *dependencies,
        ]
        bootstrap = (
            "import os,sys; "
            f"os.makedirs({_SRT_PRIVATE_TMP!r}, mode=0o700, exist_ok=True); "
            "os.execv(sys.argv[1], sys.argv[1:])"
        )
        command = [python_bin, "-c", bootstrap, *uv_command]
        install_env = {
            "HOME": str(temp_dir),
            "TMPDIR": str(temp_dir),
            "UV_CACHE_DIR": str(cache_dir),
            "UV_NO_CONFIG": "1",
            "UV_PYTHON_DOWNLOADS": "never",
        }
        return install_runtime.run(
            command,
            cwd=temp_dir,
            env=install_env,
            timeout=timeout,
            check=False,
        )

    @staticmethod
    def _ensure_dependency_runtime_directories(cache_dir: Path) -> None:
        """在收窄策略生效前创建固定的 srt 临时目录与下载缓存。"""

        for path, label in (
            (Path(_SRT_PRIVATE_TMP), "srt 私有临时目录"),
            (cache_dir, "uv 下载缓存目录"),
        ):
            if path.is_symlink():
                raise SandboxRuntimeError(f"{label}不能是符号链接: {path}")
            path.mkdir(mode=0o700, parents=True, exist_ok=True)
            if not path.is_dir():
                raise SandboxRuntimeError(f"{label}不是目录: {path}")
            path.chmod(0o700)

    @staticmethod
    def _dependency_cache_dir() -> Path:
        configured = os.getenv(_DEPENDENCY_CACHE_ENV, "").strip()
        if not configured:
            return _DEFAULT_DEPENDENCY_CACHE_DIR
        configured_path = Path(configured).expanduser()
        if configured_path.is_symlink():
            raise SandboxRuntimeError(f"uv 下载缓存目录非法: {configured_path}")
        cache_dir = configured_path.resolve()
        if cache_dir == Path(cache_dir.anchor):
            raise SandboxRuntimeError(f"uv 下载缓存目录非法: {cache_dir}")
        return cache_dir

    def _dependency_install_config(
        self,
        workspace: Path,
        *,
        uv_bin: str,
        cache_dir: Path,
    ) -> SandboxRuntimeConfig:
        config = SandboxRuntimeConfig.from_dict(self.config.to_dict())
        config.network.allowedDomains = list(_PYPI_ALLOWED_DOMAINS)
        config.network.allowUnixSockets = []
        config.network.allowAllUnixSockets = False
        config.network.allowLocalBinding = False
        workspace_path = str(workspace.resolve())
        cache_path = str(cache_dir.resolve())
        config.filesystem.allowRead = list(
            dict.fromkeys([*config.filesystem.allowRead, workspace_path, cache_path, str(Path(uv_bin).resolve())])
        )
        # srt 将进程临时目录固定映射到 /tmp/claude；它属于本次 srt 私有挂载，
        # 仅依赖安装子阶段可写，代码执行仍沿用原始无网络/只读策略。
        config.filesystem.allowWrite = [workspace_path, cache_path, _SRT_PRIVATE_TMP]
        return config

    def _code_execution_runtime(self, dependency_dir: Path) -> SandboxRuntime:
        """仅为代码执行增加一次性依赖目录的只读权限。"""

        config = SandboxRuntimeConfig.from_dict(self.config.to_dict())
        dependency_path = str(dependency_dir.resolve())
        config.filesystem.allowRead = list(dict.fromkeys([*config.filesystem.allowRead, dependency_path]))
        return SandboxRuntime(
            config,
            srt_bin=self.srt_bin,
            cwd=self.cwd,
            env=self.env,
            keep_settings_file=self.keep_settings_file,
            max_output_bytes=self.max_output_bytes,
            max_process_output_bytes=self.max_process_output_bytes,
        )

    @staticmethod
    def _configured_dependency_root() -> Path | None:
        configured = os.getenv(_DEPENDENCY_ROOT_ENV, "").strip()
        if not configured:
            return None
        configured_path = Path(configured).expanduser()
        if configured_path.is_symlink():
            raise SandboxRuntimeError(f"一次性依赖根目录非法: {configured_path}")
        root = configured_path.resolve()
        if root == Path(root.anchor):
            raise SandboxRuntimeError(f"一次性依赖根目录非法: {root}")
        if not root.is_dir() or not os.access(root, os.W_OK | os.X_OK):
            raise SandboxRuntimeError(f"一次性依赖根目录不可写: {root}")
        return root

    @staticmethod
    def _is_python_interpreter(value: str) -> bool:
        return Path(value).name.lower() in {"python", "python3"} or Path(value).name.lower().startswith("python3.")

    @staticmethod
    def _trusted_system_python() -> str:
        configured = os.getenv("AGENT_SANDBOX_PYTHON_BIN", "").strip()
        candidates = [configured, "/usr/local/bin/python3", "/opt/homebrew/bin/python3", "/usr/bin/python3"]
        for candidate in candidates:
            if not candidate:
                continue
            path = Path(candidate).expanduser().resolve()
            if path.is_file() and os.access(path, os.X_OK):
                return str(path)
        raise SandboxCommandNotFoundError("未找到受信任的系统 Python，无法创建一次性依赖环境")

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
        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            env["JAVA_HOME"] = java_home
        return env


def _terminate_process_group(proc: subprocess.Popen) -> None:
    """终止并回收整组 srt 进程，覆盖超时和 CLI 提前退出后的残留孙进程。"""

    if os.name != "posix":
        if proc.poll() is None:
            proc.kill()
        proc.wait()
        return

    process_group_id = proc.pid
    grace_seconds = _termination_grace_seconds()
    try:
        os.killpg(process_group_id, signal.SIGTERM)
    except ProcessLookupError:
        if proc.poll() is None:
            proc.wait()
        return

    try:
        proc.wait(timeout=grace_seconds)
    except subprocess.TimeoutExpired:
        pass

    deadline = time.monotonic() + grace_seconds
    while time.monotonic() < deadline:
        if not _process_group_exists(process_group_id):
            break
        time.sleep(0.01)

    if _process_group_exists(process_group_id):
        try:
            os.killpg(process_group_id, signal.SIGKILL)
        except (PermissionError, ProcessLookupError):
            pass

    if proc.poll() is None:
        try:
            proc.wait(timeout=grace_seconds)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()


def _process_group_exists(process_group_id: int) -> bool:
    try:
        os.killpg(process_group_id, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _termination_grace_seconds() -> float:
    try:
        configured = float(os.getenv("AGENT_SANDBOX_PROCESS_TERMINATION_GRACE_SECONDS", "0.5"))
    except ValueError:
        configured = 0.5
    return max(0.05, min(2.0, configured))
