"""Job Buddy Sandbox Runtime 服务入口。"""

from __future__ import annotations

import os

import uvicorn

from app.server.app import create_app

app = create_app()


if __name__ == "__main__":
    uvicorn.run(
        "server:app",
        host=os.getenv("HOST", "127.0.0.1"),
        port=int(os.getenv("PORT", "8061")),
        reload=os.getenv("RELOAD", "false").lower() == "true",
    )
