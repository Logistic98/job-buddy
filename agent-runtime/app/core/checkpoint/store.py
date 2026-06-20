
import json
from pathlib import Path
from typing import Any, Dict, Optional

from app.core.common.settings import settings
from app.core.utils.time_utils import TimeUtils


class CheckpointStore:
    """JSON 文件检查点存储，后续可替换为 Redis、PostgreSQL 或 LangGraph Checkpointer。"""

    def __init__(self, base_dir: str = None):
        self.base_dir = Path(base_dir or settings.checkpoint_dir)
        self.base_dir.mkdir(parents=True, exist_ok=True)

    async def save(self, session_id: str, run_id: str, stage: str, state: Dict[str, Any]):
        if not settings.config.checkpoint.enabled:
            return
        payload = {
            "session_id": session_id,
            "run_id": run_id,
            "stage": stage,
            "saved_at": TimeUtils.get_formatted_time(),
            "state": self._json_safe(state),
        }
        path = self._path(session_id, run_id, stage)
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        self._cleanup(session_id)

    async def load_latest(self, session_id: str) -> Optional[Dict[str, Any]]:
        files = self._session_files(session_id)
        if not files:
            return None
        return json.loads(files[0].read_text(encoding="utf-8"))

    async def load_latest_by_run(self, session_id: str, run_id: str) -> Optional[Dict[str, Any]]:
        for path in self._session_files(session_id):
            payload = json.loads(path.read_text(encoding="utf-8"))
            if payload.get("run_id") == run_id:
                return payload
        return None

    async def list_snapshots(self, session_id: str) -> list[Dict[str, Any]]:
        """返回会话全部检查点的元信息（新到旧），不携带完整 state，便于列表展示。"""
        snapshots = []
        for path in self._session_files(session_id):
            payload = json.loads(path.read_text(encoding="utf-8"))
            snapshots.append(
                {
                    "session_id": payload.get("session_id"),
                    "run_id": payload.get("run_id"),
                    "stage": payload.get("stage"),
                    "saved_at": payload.get("saved_at"),
                    "file": path.name,
                }
            )
        return snapshots

    def _session_files(self, session_id: str) -> list[Path]:
        return sorted(self.base_dir.glob(f"{session_id}__*.json"), key=lambda p: p.stat().st_mtime, reverse=True)

    def _path(self, session_id: str, run_id: str, stage: str) -> Path:
        safe_stage = stage.replace("/", "_")
        sequence = int(TimeUtils.get_timestamp() * 1000)
        return self.base_dir / f"{session_id}__{run_id}__{sequence}__{safe_stage}.json"

    def _cleanup(self, session_id: str):
        max_per_session = settings.config.checkpoint.max_per_session
        if max_per_session <= 0:
            return
        for path in self._session_files(session_id)[max_per_session:]:
            path.unlink(missing_ok=True)

    def _json_safe(self, obj: Any):
        if hasattr(obj, "model_dump"):
            return obj.model_dump()
        if isinstance(obj, dict):
            return {key: self._json_safe(value) for key, value in obj.items()}
        if isinstance(obj, list):
            return [self._json_safe(item) for item in obj]
        return obj
