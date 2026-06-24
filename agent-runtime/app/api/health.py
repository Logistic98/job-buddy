
from fastapi import APIRouter

from app.api.responses import success

router = APIRouter(prefix="/health", tags=["health"])


@router.get("")
async def health():
    return success({"status": "healthy"})
