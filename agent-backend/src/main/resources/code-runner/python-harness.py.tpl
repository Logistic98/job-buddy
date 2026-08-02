"""执行用户 Python 代码并汇总测试结果。"""

import inspect
import json
import sys


def _build_tree(values, node_class):
    """将层序数组转换为用户代码声明的 TreeNode。"""
    if not isinstance(values, list) or not values or values[0] is None:
        return None
    root = node_class(values[0])
    queue = [root]
    value_index = 1
    queue_index = 0
    while queue_index < len(queue) and value_index < len(values):
        node = queue[queue_index]
        queue_index += 1
        if value_index < len(values) and values[value_index] is not None:
            node.left = node_class(values[value_index])
            queue.append(node.left)
        value_index += 1
        if value_index < len(values) and values[value_index] is not None:
            node.right = node_class(values[value_index])
            queue.append(node.right)
        value_index += 1
    return root


def _build_linked_list(values, node_class):
    """将数组转换为用户代码声明的 ListNode。"""
    dummy = node_class(0)
    tail = dummy
    for value in values:
        tail.next = node_class(value)
        tail = tail.next
    return dummy.next


def _adapt_argument(value, parameter):
    """只按显式节点类型或标准节点参数名还原 LeetCode 结构。"""
    if not isinstance(value, list):
        return value
    annotation = parameter.annotation
    annotation_name = "" if annotation is inspect.Parameter.empty else str(annotation)
    tree_class = globals().get("TreeNode")
    if isinstance(tree_class, type) and ("TreeNode" in annotation_name or parameter.name == "root"):
        return _build_tree(value, tree_class)
    list_class = globals().get("ListNode")
    if isinstance(list_class, type) and ("ListNode" in annotation_name or parameter.name == "head"):
        return _build_linked_list(value, list_class)
    return value


def _normalize(value):
    """将常见 LeetCode 节点结果转换为可比较的 JSON 值。"""
    tree_class = globals().get("TreeNode")
    if isinstance(tree_class, type) and isinstance(value, tree_class):
        result = []
        queue = [value]
        queue_index = 0
        while queue_index < len(queue):
            node = queue[queue_index]
            queue_index += 1
            if node is None:
                result.append(None)
                continue
            result.append(node.val)
            queue.extend([getattr(node, "left", None), getattr(node, "right", None)])
        while result and result[-1] is None:
            result.pop()
        return result
    list_class = globals().get("ListNode")
    if isinstance(list_class, type) and isinstance(value, list_class):
        result = []
        seen = set()
        node = value
        while node is not None and id(node) not in seen and len(result) < 10000:
            seen.add(id(node))
            result.append(node.val)
            node = getattr(node, "next", None)
        return result
    if isinstance(value, tuple):
        return [_normalize(item) for item in value]
    if isinstance(value, list):
        return [_normalize(item) for item in value]
    if isinstance(value, dict):
        return {str(key): _normalize(item) for key, item in value.items()}
    return value


def _stable(value):
    """将结果序列化为可稳定比较的 JSON 文本。"""
    return json.dumps(_normalize(value), ensure_ascii=False, sort_keys=True, separators=(",", ":"))


tests = json.loads(sys.stdin.read() or "[]")
fn = globals().get("__FUNCTION_NAME__")
if not callable(fn):
    solution_class = globals().get("Solution")
    fn = getattr(solution_class(), "__FUNCTION_NAME__", None) if isinstance(solution_class, type) else None
parameters = list(inspect.signature(fn).parameters.values()) if callable(fn) else []
rows = []
for test in tests:
    has_expected = "expected" in test
    row = {"name": test.get("name", "用例"), "input": _stable(test.get("args", []))}
    if has_expected:
        row["expected"] = _stable(test.get("expected"))
    try:
        if not callable(fn):
            raise Exception("未找到函数或 Solution 方法：__FUNCTION_NAME__")
        args = test.get("args") or []
        adapted_args = [
            _adapt_argument(value, parameters[index]) if index < len(parameters) else value
            for index, value in enumerate(args)
        ]
        actual = _normalize(fn(*adapted_args))
        expected = _normalize(test.get("expected"))
        row["actual"] = _stable(actual)
        row["passed"] = (actual == expected) if has_expected else True
    except Exception as exc:
        row["actual"] = "运行异常"
        row["passed"] = False
        row["error"] = str(exc)
    rows.append(row)
print(json.dumps({"passed": bool(rows) and all(r.get("passed") for r in rows), "rows": rows}, ensure_ascii=False))
