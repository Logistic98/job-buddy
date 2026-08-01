from __future__ import annotations

import os
import shlex
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest

import app.core.runtime as runtime_module
from app import SandboxProcessError, SandboxResult, SandboxRuntime, default_config


@pytest.fixture()
def runtime(fake_srt: Path, tmp_path: Path) -> SandboxRuntime:
    return SandboxRuntime(default_config(allow_write=[str(tmp_path)]), cwd=tmp_path)


def test_run_arbitrary_argv_command(runtime: SandboxRuntime) -> None:
    result = runtime.run([sys.executable, "-c", "print('hello command')"])
    assert result.ok
    assert result.stdout.strip() == "hello command"
    assert "--settings" in result.args
    assert result.args[3] == "--"


def test_run_command_string(runtime: SandboxRuntime) -> None:
    result = runtime.run(f"{sys.executable} -c \"print('hello string command')\"")
    assert result.ok
    assert result.stdout.strip() == "hello string command"


def test_run_cli_tool(runtime: SandboxRuntime) -> None:
    result = runtime.run_cli(sys.executable, ["-c", "import sys; print(sys.version_info.major)"])
    assert result.ok
    assert result.stdout.strip() == str(sys.version_info.major)


def test_run_shell_command(runtime: SandboxRuntime) -> None:
    result = runtime.run_shell("printf 'a' && printf 'b'")
    assert result.ok
    assert result.stdout == "ab"


def test_run_python_code(runtime: SandboxRuntime) -> None:
    result = runtime.run_python_code("import sys; print(sys.argv[1].upper())", args=["job-buddy"])
    assert result.ok
    assert result.stdout.strip() == "JOB-BUDDY"


def test_run_python_script(runtime: SandboxRuntime, tmp_path: Path) -> None:
    script = tmp_path / "script.py"
    script.write_text("import sys\nprint('script:' + sys.argv[1])\n", encoding="utf-8")
    result = runtime.run_python(script, args=["ok"])
    assert result.ok
    assert result.stdout.strip() == "script:ok"


def test_run_code_file_python(runtime: SandboxRuntime) -> None:
    result = runtime.run_code_file("print('code file')")
    assert result.ok
    assert result.stdout.strip() == "code file"


def test_dependency_install_and_code_execution_use_independent_timeouts(runtime: SandboxRuntime, monkeypatch) -> None:
    observed: dict[str, float | None] = {}

    def fake_install(*args, timeout=None, **kwargs):
        observed["dependency_timeout"] = timeout
        return SandboxResult(args=[], returncode=0, stdout="", stderr="")

    def fake_run(*args, timeout=None, **kwargs):
        observed["code_timeout"] = timeout
        return SandboxResult(args=[], returncode=0, stdout="ok\n", stderr="")

    monkeypatch.setattr(runtime, "_install_python_dependencies", fake_install)
    monkeypatch.setattr(SandboxRuntime, "run", fake_run)

    result = runtime.run_code_file(
        "print('ok')",
        dependencies=["numpy"],
        timeout=25,
        dependency_timeout=90,
    )

    assert result.stdout == "ok\n"
    assert observed == {"dependency_timeout": 90, "code_timeout": 25}


def test_runtime_directories_are_created_before_dependency_sandbox(
    runtime: SandboxRuntime, tmp_path: Path, monkeypatch
) -> None:
    private_tmp = tmp_path / "srt-private"
    cache_dir = tmp_path / "uv-cache"
    monkeypatch.setattr(runtime_module, "_SRT_PRIVATE_TMP", str(private_tmp))
    monkeypatch.setenv("AGENT_SANDBOX_DEPENDENCY_CACHE_DIR", str(cache_dir))

    resolved_cache_dir = runtime._dependency_cache_dir()
    runtime._ensure_dependency_runtime_directories(resolved_cache_dir)

    assert private_tmp.is_dir()
    assert private_tmp.stat().st_mode & 0o777 == 0o700
    assert resolved_cache_dir == cache_dir
    assert cache_dir.is_dir()
    assert cache_dir.stat().st_mode & 0o777 == 0o700


def test_dependency_install_policy_does_not_mutate_code_execution_policy(
    runtime: SandboxRuntime, fake_srt: Path, tmp_path: Path
) -> None:
    cache_dir = tmp_path / "dependency-cache"

    install_config = runtime._dependency_install_config(tmp_path, uv_bin=str(fake_srt), cache_dir=cache_dir)

    assert runtime.config.network.allowedDomains == []
    assert runtime.config.filesystem.allowWrite == [str(tmp_path)]
    assert install_config.network.allowedDomains == ["pypi.org", "files.pythonhosted.org"]
    assert install_config.network.allowLocalBinding is False
    assert str(tmp_path.resolve()) in install_config.filesystem.allowWrite
    assert str(cache_dir.resolve()) in install_config.filesystem.allowWrite
    assert "/tmp/claude" in install_config.filesystem.allowWrite


def test_code_execution_only_exposes_current_dependency_environment(
    fake_srt: Path, tmp_path: Path, monkeypatch
) -> None:
    dependency_root = tmp_path / "dependency-root"
    dependency_root.mkdir()
    monkeypatch.setenv("AGENT_SANDBOX_DEPENDENCY_ROOT", str(dependency_root))
    runtime = SandboxRuntime(default_config(allow_write=[str(tmp_path)]), cwd=tmp_path)
    dependency_dir = tmp_path / "dependency-env" / "site-packages"

    code_runtime = runtime._code_execution_runtime(dependency_dir)

    assert runtime.config.network.allowedDomains == []
    assert code_runtime.config.network.allowedDomains == []
    assert code_runtime.config.filesystem.allowWrite == runtime.config.filesystem.allowWrite
    assert str(dependency_root.resolve()) in runtime.config.filesystem.denyRead
    assert str(dependency_root.resolve()) in code_runtime.config.filesystem.denyRead
    assert str(dependency_dir.resolve()) in code_runtime.config.filesystem.allowRead
    assert str(dependency_dir.resolve()) not in runtime.config.filesystem.allowRead


def test_cwd_and_env_are_propagated(runtime: SandboxRuntime, tmp_path: Path) -> None:
    result = runtime.run_python_code(
        "import os; print(os.getcwd()); print(os.environ['JOB_BUDDY_TEST_ENV'])",
        cwd=tmp_path,
        env={"JOB_BUDDY_TEST_ENV": "sandbox-env"},
    )
    lines = result.stdout.splitlines()
    assert lines[0] == str(tmp_path)
    assert lines[1] == "sandbox-env"


def test_java_home_is_preserved_as_a_non_secret_runtime_variable(fake_srt: Path, tmp_path: Path, monkeypatch) -> None:
    java_home = tmp_path / "jdk"
    monkeypatch.setenv("JAVA_HOME", str(java_home))
    runtime = SandboxRuntime(
        default_config(allow_write=[str(tmp_path)]),
        cwd=tmp_path,
    )

    result = runtime.run_python_code("import os; print(os.environ['JAVA_HOME'])")

    assert result.stdout.strip() == str(java_home)


def test_non_zero_exit_raises_when_check_enabled(runtime: SandboxRuntime) -> None:
    with pytest.raises(SandboxProcessError) as exc_info:
        runtime.run_python_code("import sys; sys.exit(7)")
    assert exc_info.value.returncode == 7


def test_non_zero_exit_can_be_returned_when_check_disabled(runtime: SandboxRuntime) -> None:
    result = runtime.run_python_code("import sys; sys.exit(5)", check=False)
    assert not result.ok
    assert result.returncode == 5


def test_timeout_terminates_the_entire_srt_process_group(tmp_path: Path) -> None:
    process_ids_path = tmp_path / "spawned-processes.txt"
    timeout_srt = tmp_path / "timeout-srt"
    timeout_srt.write_text(
        "#!/bin/sh\n"
        "sleep 30 &\n"
        "child=$!\n"
        f'printf \'%s %s\\n\' "$$" "$child" > {shlex.quote(str(process_ids_path))}\n'
        'wait "$child"\n',
        encoding="utf-8",
    )
    timeout_srt.chmod(0o755)
    runtime = SandboxRuntime(
        default_config(allow_write=[str(tmp_path)]),
        srt_bin=str(timeout_srt),
        cwd=tmp_path,
    )
    spawned_process_ids: list[int] = []

    try:
        with pytest.raises(subprocess.TimeoutExpired) as exc_info:
            runtime.run_python_code("print('private-source-code')", timeout=1)
        assert exc_info.value.cmd == ["sandbox-runtime"]
        assert "private-source-code" not in str(exc_info.value)
        spawned_process_ids = [int(value) for value in process_ids_path.read_text().split()]

        deadline = time.monotonic() + 1
        while time.monotonic() < deadline and any(_process_exists(pid) for pid in spawned_process_ids):
            time.sleep(0.02)

        assert all(not _process_exists(pid) for pid in spawned_process_ids)
    finally:
        for process_id in spawned_process_ids:
            try:
                os.kill(process_id, signal.SIGKILL)
            except ProcessLookupError:
                pass


def test_large_output_is_truncated_without_unbounded_capture(fake_srt: Path, tmp_path: Path) -> None:
    runtime = SandboxRuntime(
        default_config(allow_write=[str(tmp_path)]),
        cwd=tmp_path,
        max_output_bytes=4096,
    )

    result = runtime.run_python_code("print('x' * 12000)")

    assert result.ok
    assert len(result.stdout) < 5000
    assert "output truncated" in result.stdout
    assert "bytes omitted" in result.stdout


def test_process_output_limit_terminates_execution(fake_srt: Path, tmp_path: Path) -> None:
    runtime = SandboxRuntime(
        default_config(allow_write=[str(tmp_path)]),
        cwd=tmp_path,
        max_output_bytes=4096,
        max_process_output_bytes=8192,
    )

    with pytest.raises(SandboxProcessError) as exc_info:
        runtime.run_python_code("print('x' * 100000)")

    assert "输出超过限制" in str(exc_info.value)
    assert len(exc_info.value.stdout) < 5000


def test_quote_args() -> None:
    assert SandboxRuntime.quote_args(["echo", "hello world"]) == "echo 'hello world'"


def _process_exists(process_id: int) -> bool:
    try:
        os.kill(process_id, 0)
    except ProcessLookupError:
        return False
    return True
