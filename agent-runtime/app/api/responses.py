"""统一 HTTP 响应信封。

Runtime 与后端、工具服务保持同一业务码语义：成功使用 2xx，客户端错误使用 4xx，服务端错误使用 5xx；
HTTP 状态码仍由 FastAPI 负责表达传输层状态。
"""

from typing import Any, Dict


def success(data: Any = None) -> Dict[str, Any]:
    return {"code": 200, "message": "success", "data": data}
