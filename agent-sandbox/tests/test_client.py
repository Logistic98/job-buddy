
from __future__ import annotations

import sys
from pathlib import Path

from app import CodeSpec, CommandSpec, ExecutionOptions, SandboxClient, SandboxPolicies


def _client(fake_srt: Path, tmp_path: Path) -> SandboxClient:
    return SandboxClient(
        SandboxPolicies.workspace_readwrite(tmp_path),
        cwd=tmp_path,
        default_timeout=10,
    )


def test_client_execute_command_spec(fake_srt: Path, tmp_path: Path) -> None:
    client = _client(fake_srt, tmp_path)
    result = client.execute(CommandSpec.from_argv([sys.executable, "-c", "print('spec')"]))
    assert result.stdout.strip() == "spec"


def test_client_command_api(fake_srt: Path, tmp_path: Path) -> None:
    client = _client(fake_srt, tmp_path)
    result = client.command([sys.executable, "-c", "print('command')"])
    assert result.stdout.strip() == "command"


def test_client_command_string_api(fake_srt: Path, tmp_path: Path) -> None:
    client = _client(fake_srt, tmp_path)
    result = client.command_string(f"{sys.executable} -c \"print('command-string')\"")
    assert result.stdout.strip() == "command-string"


def test_client_cli_api(fake_srt: Path, tmp_path: Path) -> None:
    client = _client(fake_srt, tmp_path)
    result = client.cli(sys.executable, ["-c", "print('cli')"])
    assert result.stdout.strip() == "cli"


def test_client_shell_api(fake_srt: Path, tmp_path: Path) -> None:
    client = _client(fake_srt, tmp_path)
    result = client.shell("printf client-shell")
    assert result.stdout.strip() == "client-shell"


def test_client_python_script_api(fake_srt: Path, tmp_path: Path) -> None:
    client = _client(fake_srt, tmp_path)
    script = tmp_path / "script.py"
    script.write_text("print('python-script')\n", encoding="utf-8")
    result = client.python_script(script)
    assert result.stdout.strip() == "python-script"


def test_client_python_code_api(fake_srt: Path, tmp_path: Path) -> None:
    client = _client(fake_srt, tmp_path)
    result = client.python_code("print('python-code')")
    assert result.stdout.strip() == "python-code"


def test_client_code_file_api(fake_srt: Path, tmp_path: Path) -> None:
    client = _client(fake_srt, tmp_path)
    result = client.code_file(CodeSpec(code="print('code-file')"))
    assert result.stdout.strip() == "code-file"


def test_client_options_override(fake_srt: Path, tmp_path: Path) -> None:
    client = _client(fake_srt, tmp_path)
    spec = CommandSpec.from_argv(
        [sys.executable, "-c", "import os; print(os.environ['A']); print(os.getcwd())"],
        ExecutionOptions(cwd=tmp_path, env={"A": "B"}, timeout=1),
    )
    result = client.execute(spec)
    lines = result.stdout.splitlines()
    assert lines == ["B", str(tmp_path)]
