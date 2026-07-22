import json
import sys


def _stable(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


tests = json.loads(sys.stdin.read() or "[]")
fn = globals().get("__FUNCTION_NAME__")
if not callable(fn):
    solution_class = globals().get("Solution")
    fn = getattr(solution_class(), "__FUNCTION_NAME__", None) if isinstance(solution_class, type) else None
rows = []
for test in tests:
    has_expected = "expected" in test
    row = {"name": test.get("name", "用例"), "input": _stable(test.get("args", []))}
    if has_expected:
        row["expected"] = _stable(test.get("expected"))
    try:
        if not callable(fn):
            raise Exception("未找到函数或 Solution 方法：__FUNCTION_NAME__")
        actual = fn(*(test.get("args") or []))
        row["actual"] = _stable(actual)
        row["passed"] = (actual == test.get("expected")) if has_expected else True
    except Exception as exc:
        row["actual"] = "运行异常"
        row["passed"] = False
        row["error"] = str(exc)
    rows.append(row)
print(json.dumps({"passed": bool(rows) and all(r.get("passed") for r in rows), "rows": rows}, ensure_ascii=False))
