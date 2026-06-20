
from fastapi import APIRouter

router = APIRouter(prefix="/health", tags=["health"])


@router.get("")
async def health():
    return {"code": 200, "message": "ok", "data": {"status": "healthy"}}
