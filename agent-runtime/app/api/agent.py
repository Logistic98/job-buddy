
"""目标 Agent API 契约。

/v1/agent/* 统一交给 AgentExecutor 执行，/v1/runtime/* 保留兼容。
"""

import json

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from app.api.runtime import get_executor
from app.models.schemas import AgentRunRequest

router = APIRouter(prefix="/v1/agent", tags=["agent"])


@router.post("/runs")
async def run_agent(request: AgentRunRequest):
    data = await get_executor().execute(request)
    return {"code": 200, "message": "ok", "data": data.model_dump()}


@router.post("/runs/stream")
async def run_agent_stream(request: AgentRunRequest):
    """Token 级流式问答入口，返回 text/event-stream。

    逐事件下发：token（答案增量）、done（终态聚合）、error（异常）。请求契约与
    非流式 /runs 一致，仅返回形态不同；非流式入口保留为兼容与回退路径。
    """

    async def event_source():
        async for chunk in get_executor().execute_stream(request):
            event = chunk.get("event", "message")
            data = json.dumps(chunk.get("data", {}), ensure_ascii=False)
            yield f"event: {event}\ndata: {data}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(
        event_source(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
