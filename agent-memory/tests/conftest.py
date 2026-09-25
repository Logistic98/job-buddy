import hashlib
import math
import os

os.environ["MEM0_TELEMETRY"] = "false"

import pytest
from mem0 import Memory
from mem0.utils.factory import EmbedderFactory

from app.store import MemoryStore


class DeterministicEmbedding:
    """Test double only: never used to claim real semantic quality."""

    def embed(self, text, memory_action=None):
        vector = [0.0] * 32
        for char in text:
            vector[int(hashlib.sha256(char.encode()).hexdigest(), 16) % 32] += 1
        norm = math.sqrt(sum(value * value for value in vector)) or 1
        return [value / norm for value in vector]


@pytest.fixture
def memory_store(monkeypatch, tmp_path):
    monkeypatch.setattr(EmbedderFactory, "create", lambda *args: DeterministicEmbedding())
    engine = Memory.from_config(
        {
            "vector_store": {"provider": "qdrant", "config": {"path": ":memory:", "embedding_model_dims": 32}},
            "llm": {"provider": "openai", "config": {"api_key": "unit-test-only"}},
            "history_db_path": str(tmp_path / "history.db"),
        }
    )
    store = MemoryStore(engine)
    yield store
    store.close()
