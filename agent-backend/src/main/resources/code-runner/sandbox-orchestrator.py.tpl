import base64
import json
import os
import subprocess
import sys
import tempfile

LANGUAGE = __LANGUAGE__
CODE_B64 = __CODE_B64__
RUNNER_B64 = __RUNNER_B64__
TESTS_B64 = __TESTS_B64__
TIMEOUT_SECONDS = __TIMEOUT_SECONDS__


def decode(value):
    return base64.b64decode(value.encode("ascii")).decode("utf-8")


def emit(payload):
    print(json.dumps(payload, ensure_ascii=False))
    sys.exit(0)


def first_line(value, fallback):
    text = (value or "").strip()
    return text.splitlines()[0] if text else fallback


def write(path, content):
    with open(path, "w", encoding="utf-8") as file:
        file.write(content)


def parse_child(process):
    if process.returncode != 0:
        emit(
            {
                "passed": False,
                "rows": [],
                "message": first_line(
                    (process.stderr or "") + "\n" + (process.stdout or ""), "运行失败"
                ),
            }
        )
    lines = [line.strip() for line in (process.stdout or "").splitlines() if line.strip()]
    if not lines:
        emit({"passed": False, "rows": [], "message": "运行无输出"})
    try:
        result = json.loads(lines[-1])
        if "passed" not in result:
            result["passed"] = False
        if "rows" not in result:
            result["rows"] = []
        emit(result)
    except Exception as exc:
        emit({"passed": False, "rows": [], "message": "运行结果解析失败：" + str(exc)})


CODE = decode(CODE_B64)
RUNNER = decode(RUNNER_B64) if RUNNER_B64 else ""
TESTS = decode(TESTS_B64)

try:
    with tempfile.TemporaryDirectory(
        prefix="job-buddy-practice-", dir=os.getcwd()
    ) as workspace:
        if LANGUAGE == "python":
            write(os.path.join(workspace, "main.py"), CODE)
            process = subprocess.run(
                ["python3", "main.py"],
                cwd=workspace,
                input=TESTS,
                capture_output=True,
                text=True,
                timeout=TIMEOUT_SECONDS,
            )
            parse_child(process)
        elif LANGUAGE == "javascript":
            write(os.path.join(workspace, "main.js"), CODE)
            process = subprocess.run(
                ["node", "main.js"],
                cwd=workspace,
                input=TESTS,
                capture_output=True,
                text=True,
                timeout=TIMEOUT_SECONDS,
            )
            parse_child(process)
        elif LANGUAGE == "java":
            write(os.path.join(workspace, "Solution.java"), CODE)
            write(os.path.join(workspace, "Runner.java"), RUNNER)
            compile_process = subprocess.run(
                ["javac", "Solution.java", "Runner.java"],
                cwd=workspace,
                capture_output=True,
                text=True,
                timeout=TIMEOUT_SECONDS,
            )
            if compile_process.returncode != 0:
                emit(
                    {
                        "passed": False,
                        "rows": [],
                        "message": first_line(
                            (compile_process.stderr or "")
                            + "\n"
                            + (compile_process.stdout or ""),
                            "编译失败",
                        ),
                    }
                )
            process = subprocess.run(
                ["java", "-cp", workspace, "Runner"],
                cwd=workspace,
                input=TESTS,
                capture_output=True,
                text=True,
                timeout=TIMEOUT_SECONDS,
            )
            parse_child(process)
        else:
            emit(
                {
                    "passed": False,
                    "rows": [],
                    "message": "当前仅支持 Python、Java、JavaScript 运行样例",
                }
            )
except subprocess.TimeoutExpired:
    emit({"passed": False, "rows": [], "message": "运行超时，请检查是否存在死循环"})
except FileNotFoundError as exc:
    emit({"passed": False, "rows": [], "message": "运行环境缺少命令：" + str(exc)})
except Exception as exc:
    emit({"passed": False, "rows": [], "message": str(exc) or "沙箱运行失败"})
