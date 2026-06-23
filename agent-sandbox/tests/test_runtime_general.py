
from __future__ import annotations

import sys
from pathlib import Path

import pytest

from app import SandboxProcessError, SandboxRuntime, default_config


@pytest.fixture()
def runtime(fake_srt: Path, tmp_path: Path) -> SandboxRuntime:
    return SandboxRuntime(default_config(allow_write=[str(tmp_path)]), cwd=tmp_path)


def test_run_arbitrary_argv_command(runtime: SandboxRuntime) -> None:
    result = runtime.run([sys.executable, "-c", "print('hello command')"])
    assert result.ok
    assert result.stdout.strip() == "hello command"
    assert "--settings" in result.args


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


def test_cwd_and_env_are_propagated(runtime: SandboxRuntime, tmp_path: Path) -> None:
    result = runtime.run_python_code(
        "import os; print(os.getcwd()); print(os.environ['JOB_BUDDY_TEST_ENV'])",
        cwd=tmp_path,
        env={"JOB_BUDDY_TEST_ENV": "sandbox-env"},
    )
    lines = result.stdout.splitlines()
    assert lines[0] == str(tmp_path)
    assert lines[1] == "sandbox-env"


def test_non_zero_exit_raises_when_check_enabled(runtime: SandboxRuntime) -> None:
    with pytest.raises(SandboxProcessError) as exc_info:
        runtime.run_python_code("import sys; sys.exit(7)")
    assert exc_info.value.returncode == 7


def test_non_zero_exit_can_be_returned_when_check_disabled(runtime: SandboxRuntime) -> None:
    result = runtime.run_python_code("import sys; sys.exit(5)", check=False)
    assert not result.ok
    assert result.returncode == 5


def test_quote_args() -> None:
    assert SandboxRuntime.quote_args(["echo", "hello world"]) == "echo 'hello world'"
