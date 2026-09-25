import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, Mock

import pytest

from app import CommandSpec, SandboxClient, SandboxPolicies, SandboxResult, SandboxRuntime
from app.core import runtime as runtime_module
from app.core.dependencies import normalize_python_dependencies
from app.core.exceptions import SandboxCommandNotFoundError, SandboxProcessError, SandboxRuntimeError


@pytest.mark.parametrize("dependencies", [["pkg"] * 9, [123], ["some.pkg", "some_pkg"]])
def test_dependency_declarations_reject_excess_non_string_and_duplicate_names(dependencies):
    with pytest.raises(ValueError):
        normalize_python_dependencies(dependencies)


@pytest.mark.parametrize("arguments", [{}, {"argv": ["python"], "command": "python"}])
def test_command_requires_exactly_one_representation(arguments):
    with pytest.raises(ValueError, match="只能提供一个"):
        CommandSpec(**arguments)


def test_readonly_workspace_policy_does_not_grant_write(tmp_path):
    config = SandboxPolicies.workspace_readonly(tmp_path)
    assert config.filesystem.allowWrite == []
    assert str(tmp_path.resolve()) in config.filesystem.allowRead


def test_async_client_preserves_options_and_result(monkeypatch, tmp_path):
    client = SandboxClient(default_timeout=3)
    expected = SandboxResult([], 0, "done", "")
    run = AsyncMock(return_value=expected)
    monkeypatch.setattr(client.runtime, "arun", run)
    assert asyncio.run(client.aexecute(CommandSpec.from_string("command"))) is expected
    run.assert_awaited_once_with("command", timeout=3, check=True, cwd=None, env=None)


def test_invalid_capture_settings_fall_back_to_bounded_defaults(monkeypatch):
    monkeypatch.setenv("AGENT_SANDBOX_MAX_CAPTURE_BYTES", "invalid")
    monkeypatch.setenv("AGENT_SANDBOX_MAX_PROCESS_OUTPUT_BYTES", "invalid")
    runtime = SandboxRuntime()
    assert runtime.max_output_bytes == 1024 * 1024
    assert runtime.max_process_output_bytes == 16 * 1024 * 1024
    monkeypatch.setattr(runtime_module.shutil, "which", lambda _: None)
    with pytest.raises(SandboxCommandNotFoundError, match="srt"):
        runtime.ensure_available()


@pytest.mark.parametrize("interpreter", ["python3", ["python3", "-u"]])
def test_code_file_forwards_interpreter_arguments_and_cleans_file(monkeypatch, interpreter):
    runtime = SandboxRuntime()
    paths = []

    def run(command, **kwargs):
        path = Path(command[-2])
        paths.append(path)
        assert path.read_text() == "print(1)"
        assert command[-1] == "argument"
        assert kwargs["env"]["PYTHONDONTWRITEBYTECODE"] == "1"
        return SandboxResult(command, 0, "1", "")

    monkeypatch.setattr(runtime, "run", run)
    assert runtime.run_code_file("print(1)", interpreter=interpreter, args=["argument"]).ok
    assert not paths[0].exists()


@pytest.mark.parametrize("check", [True, False])
def test_failed_dependency_install_prevents_code_execution(monkeypatch, check):
    runtime = SandboxRuntime()
    failure = SandboxResult([], 1, "", "installation failed")
    monkeypatch.setattr(runtime, "_trusted_system_python", lambda: "/usr/bin/python3")
    monkeypatch.setattr(runtime, "_install_python_dependencies", lambda *a, **kw: failure)
    execute = Mock()
    monkeypatch.setattr(runtime, "run", execute)
    if check:
        with pytest.raises(SandboxProcessError, match="依赖安装失败"):
            runtime.run_code_file("print(1)", dependencies=["package==1"], check=check)
    else:
        assert runtime.run_code_file("print(1)", dependencies=["package==1"], check=check) is failure
    execute.assert_not_called()


def test_dependencies_reject_non_python_and_missing_installer(monkeypatch, tmp_path):
    runtime = SandboxRuntime()
    with pytest.raises(ValueError, match="仅支持 Python"):
        runtime.run_code_file("code", suffix=".js", interpreter="node", dependencies=["pkg"])
    monkeypatch.setattr(runtime_module.shutil, "which", lambda _: None)
    with pytest.raises(SandboxCommandNotFoundError, match="uv"):
        runtime._install_python_dependencies(["pkg"], dependency_dir=tmp_path, python_bin="python3", timeout=1)


@pytest.mark.parametrize("variable", ["AGENT_SANDBOX_DEPENDENCY_CACHE_DIR", "AGENT_SANDBOX_DEPENDENCY_ROOT"])
@pytest.mark.parametrize("kind", ["root", "symlink"])
def test_dependency_directories_reject_root_and_symlinks(monkeypatch, tmp_path, variable, kind):
    path = tmp_path / "link"
    path.symlink_to(tmp_path, target_is_directory=True)
    monkeypatch.setenv(variable, "/" if kind == "root" else str(path))
    function = (
        SandboxRuntime._dependency_cache_dir if "CACHE" in variable else SandboxRuntime._configured_dependency_root
    )
    with pytest.raises(SandboxRuntimeError, match="非法"):
        function()


def test_dependency_root_must_exist_and_system_python_must_be_executable(monkeypatch, tmp_path):
    monkeypatch.setenv("AGENT_SANDBOX_DEPENDENCY_ROOT", str(tmp_path / "missing"))
    with pytest.raises(SandboxRuntimeError, match="不可写"):
        SandboxRuntime._configured_dependency_root()
    monkeypatch.setattr(Path, "is_file", lambda _: False)
    with pytest.raises(SandboxCommandNotFoundError, match="Python"):
        SandboxRuntime._trusted_system_python()


def test_termination_grace_invalid_setting_uses_default(monkeypatch):
    monkeypatch.setenv("AGENT_SANDBOX_PROCESS_TERMINATION_GRACE_SECONDS", "invalid")
    assert runtime_module._termination_grace_seconds() == 0.5


def test_process_group_permission_error_still_means_alive(monkeypatch):
    monkeypatch.setattr(runtime_module.os, "killpg", Mock(side_effect=PermissionError))
    assert runtime_module._process_group_exists(123) is True


def test_async_runtime_moves_execution_to_worker(monkeypatch):
    runtime = SandboxRuntime()
    run = Mock(return_value=SandboxResult([], 0, "ok", ""))
    monkeypatch.setattr(runtime, "run", run)
    assert asyncio.run(runtime.arun("command", timeout=1)).stdout == "ok"
    run.assert_called_once_with("command", timeout=1)


def test_missing_process_group_still_reaps_child(monkeypatch):
    proc = Mock(pid=123)
    proc.poll.return_value = None
    monkeypatch.setattr(runtime_module.os, "killpg", Mock(side_effect=ProcessLookupError))
    runtime_module._terminate_process_group(proc)
    proc.wait.assert_called_once_with()


def test_stubborn_group_escalates_and_reaps_child(monkeypatch):
    import subprocess

    proc = Mock(pid=123)
    proc.poll.return_value = None
    proc.wait.side_effect = [subprocess.TimeoutExpired("cmd", 1), subprocess.TimeoutExpired("cmd", 1), 0]
    signals = Mock(side_effect=[None, PermissionError])
    monkeypatch.setattr(runtime_module.os, "killpg", signals)
    monkeypatch.setattr(runtime_module, "_process_group_exists", lambda _: True)
    monkeypatch.setattr(runtime_module.time, "monotonic", Mock(side_effect=[0, 1]))
    runtime_module._terminate_process_group(proc)
    proc.kill.assert_called_once()
    assert proc.wait.call_count == 3
    assert signals.call_count == 2


def test_live_group_is_checked_until_it_disappears(monkeypatch):
    proc = Mock(pid=123)
    proc.poll.return_value = 0
    monkeypatch.setattr(runtime_module.os, "killpg", Mock())
    monkeypatch.setattr(runtime_module, "_process_group_exists", Mock(side_effect=[True, False, False]))
    monkeypatch.setattr(runtime_module.time, "sleep", Mock())
    runtime_module._terminate_process_group(proc)
    runtime_module.time.sleep.assert_called_once_with(0.01)


def test_process_group_existence_success(monkeypatch):
    monkeypatch.setattr(runtime_module.os, "killpg", Mock())
    assert runtime_module._process_group_exists(123) is True


def test_non_posix_cleanup_kills_running_child(monkeypatch):
    proc = Mock()
    proc.poll.return_value = None
    # Replace the runtime OS facade only; do not change pathlib platform detection.
    from types import SimpleNamespace

    monkeypatch.setattr(runtime_module, "os", SimpleNamespace(name="nt"))
    runtime_module._terminate_process_group(proc)
    proc.kill.assert_called_once()
    proc.wait.assert_called_once()


def test_output_limit_interrupts_running_process(monkeypatch, tmp_path):
    runtime = SandboxRuntime(max_output_bytes=4096, max_process_output_bytes=4096)
    proc = Mock(returncode=None)
    proc.poll.return_value = None
    monkeypatch.setattr(runtime_module, "_terminate_process_group", Mock())
    with (tmp_path / "stdout").open("w+b") as out, (tmp_path / "stderr").open("w+b") as err:
        out.write(b"x" * 5000)
        with pytest.raises(SandboxProcessError, match="超过限制") as failure:
            runtime._wait_with_limits(proc, stdout_file=out, stderr_file=err, timeout=1)
    assert failure.value.returncode == -9
    runtime_module._terminate_process_group.assert_called_once_with(proc)


def test_settings_cleanup_failure_preserves_success(monkeypatch, fake_srt, tmp_path):
    import sys

    runtime = SandboxRuntime()
    settings = tmp_path / "settings.json"
    settings.write_text("{}")
    monkeypatch.setattr(runtime, "_write_settings_file", lambda: settings)
    original = Path.unlink

    def unlink(path, **kwargs):
        if path == settings:
            raise OSError("busy")
        return original(path, **kwargs)

    monkeypatch.setattr(Path, "unlink", unlink)
    assert runtime.run([sys.executable, "-c", "print(1)"]).ok


def test_runtime_directory_rejects_symlink(monkeypatch, tmp_path):
    link = tmp_path / "link"
    link.symlink_to(tmp_path, target_is_directory=True)
    monkeypatch.setattr(runtime_module, "_SRT_PRIVATE_TMP", str(link))
    with pytest.raises(SandboxRuntimeError, match="符号链接"):
        SandboxRuntime._ensure_dependency_runtime_directories(tmp_path / "cache")


def test_runtime_directory_type_is_rechecked_after_creation(monkeypatch, tmp_path):
    private = tmp_path / "private"
    monkeypatch.setattr(runtime_module, "_SRT_PRIVATE_TMP", str(private))
    original_mkdir = Path.mkdir

    def replaced_after_mkdir(path, *args, **kwargs):
        original_mkdir(path, *args, **kwargs)
        if path == private:
            path.rmdir()
            path.write_text("concurrent replacement")

    monkeypatch.setattr(Path, "mkdir", replaced_after_mkdir)
    with pytest.raises(SandboxRuntimeError, match="不是目录"):
        SandboxRuntime._ensure_dependency_runtime_directories(tmp_path / "cache")
