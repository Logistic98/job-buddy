"""SandboxRuntime 通用示例。

使用真实沙箱前先安装：npm install -g @anthropic-ai/sandbox-runtime
"""

from __future__ import annotations

from app import SandboxRuntime, default_config


def main() -> None:
    runtime = SandboxRuntime(default_config(allow_write=[]))

    print("[命令]")
    print(runtime.run(["python", "-c", "print('命令执行成功')"]).stdout.strip())

    print("[CLI]")
    print(runtime.run_cli("python", ["--version"]).stdout.strip())

    print("[Shell]")
    print(runtime.run_shell("printf 'Shell 执行成功'").stdout.strip())

    print("[Python 代码]")
    print(runtime.run_python_code("print('Python 代码执行成功')").stdout.strip())

    print("[代码文件]")
    print(runtime.run_code_file("print('临时代码文件执行成功')").stdout.strip())


if __name__ == "__main__":
    main()
