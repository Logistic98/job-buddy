import uuid

from fastapi import FastAPI
from loguru import logger

from .models import IntentRequest, IntentResult
from .service import classify_intent

app = FastAPI(title="agent-intent", version="0.1.0")


@app.get("/health")
def health() -> dict:
    return {"code": 0, "message": "success", "data": {"status": "UP", "service": "agent-intent"}}


@app.post("/v1/intent/classify", response_model=dict)
def classify(request: IntentRequest) -> dict:
    request_id = uuid.uuid4().hex[:12]
    bound = logger.bind(service="agent-intent", request_id=request_id)
    try:
        result: IntentResult = classify_intent(request.message)
    except Exception as exc:  # noqa: BLE001 统一兜底，保证返回标准信封而非裸 500
        bound.exception(f"意图分类异常 error={exc}")
        return {"code": 500, "message": "意图分类失败，请稍后重试", "data": {}}
    bound.info(
        f"意图分类完成 domain={result.domain} intent={result.intent} "
        f"confidence={result.confidence} risk={result.risk} needs_clarification={result.needs_clarification}"
    )
    return {"code": 0, "message": "success", "data": result.model_dump()}
