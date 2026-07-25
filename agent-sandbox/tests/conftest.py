from __future__ import annotations

import os
import stat
import textwrap
from pathlib import Path

import pytest


@pytest.fixture()
def fake_srt(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """创建用于包装器单元测试的伪 `srt` 可执行文件。

    伪程序校验配置文件后直接执行剩余参数，使测试不依赖真实 Sandbox Runtime，
    同时覆盖参数组装、工作目录、环境变量透传与结果处理。
    """

    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    fake = bin_dir / "srt"
    fake.write_text(
        textwrap.dedent(
            """
            #!/usr/bin/env python3
            import json
            import os
            import subprocess
            import sys

            def main():
                args = sys.argv[1:]
                if len(args) < 3 or args[0] != "--settings":
                    print("fake srt expects: --settings <file> <command...>", file=sys.stderr)
                    return 64
                settings = args[1]
                with open(settings, "r", encoding="utf-8") as f:
                    json.load(f)
                command = args[2:]
                if len(command) == 1:
                    proc = subprocess.run(command[0], shell=True, text=True, capture_output=True)
                else:
                    proc = subprocess.run(command, text=True, capture_output=True)
                sys.stdout.write(proc.stdout)
                sys.stderr.write(proc.stderr)
                return proc.returncode

            if __name__ == "__main__":
                raise SystemExit(main())
            """
        ).lstrip(),
        encoding="utf-8",
    )
    fake.chmod(fake.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    monkeypatch.setenv("PATH", str(bin_dir) + os.pathsep + os.environ.get("PATH", ""))
    return fake
