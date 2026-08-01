from __future__ import annotations

import json
import stat
import sys
import textwrap
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


def test_client_installs_python_dependencies_in_disposable_target(fake_srt: Path, tmp_path: Path, monkeypatch) -> None:
    uv_log = tmp_path / "uv-args.json"
    dependency_root = tmp_path / "dependency-root"
    dependency_root.mkdir()
    monkeypatch.setenv("AGENT_SANDBOX_DEPENDENCY_ROOT", str(dependency_root))
    fake_uv = fake_srt.parent / "uv"
    fake_uv.write_text(
        textwrap.dedent(
            f"""
            #!/usr/bin/env python3
            import json
            import pathlib
            import sys

            args = sys.argv[1:]
            target = pathlib.Path(args[args.index("--target") + 1])
            target.mkdir(parents=True, exist_ok=True)
            (target / "demo_dependency.py").write_text("VALUE = 'isolated'\\n", encoding="utf-8")
            pathlib.Path({str(uv_log)!r}).write_text(json.dumps(args), encoding="utf-8")
            """
        ).lstrip(),
        encoding="utf-8",
    )
    fake_uv.chmod(fake_uv.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    client = _client(fake_srt, tmp_path)
    result = client.code_file(
        CodeSpec(
            code="import demo_dependency; print(demo_dependency.VALUE)",
            dependencies=["demo-dependency==1.0.0"],
        )
    )

    assert result.stdout.strip() == "isolated"
    uv_args = json.loads(uv_log.read_text(encoding="utf-8"))
    assert uv_args[:2] == ["pip", "install"]
    assert "--target" in uv_args
    assert "--only-binary" in uv_args
    assert ":all:" in uv_args
    assert "--cache-dir" in uv_args
    assert "--no-config" in uv_args
    assert "--no-python-downloads" in uv_args
    assert "--default-index" in uv_args
    assert uv_args[-1] == "demo-dependency==1.0.0"
    dependency_target = Path(uv_args[uv_args.index("--target") + 1])
    assert dependency_target.parent.parent == dependency_root
    assert not dependency_target.exists()
    assert list(dependency_root.iterdir()) == []


def test_client_options_override(fake_srt: Path, tmp_path: Path) -> None:
    client = _client(fake_srt, tmp_path)
    spec = CommandSpec.from_argv(
        [sys.executable, "-c", "import os; print(os.environ['A']); print(os.getcwd())"],
        ExecutionOptions(cwd=tmp_path, env={"A": "B"}, timeout=1),
    )
    result = client.execute(spec)
    lines = result.stdout.splitlines()
    assert lines == ["B", str(tmp_path)]
