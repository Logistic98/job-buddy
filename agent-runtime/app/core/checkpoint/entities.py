"""映射 Flyway 管理的检查点与续跑领取表"""

from datetime import datetime
from typing import Any

from sqlalchemy import BigInteger, DateTime, String, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class RunCheckpoint(Base):
    __tablename__ = "agent_run_checkpoint"
    __table_args__ = (UniqueConstraint("session_id", "run_id", "sequence"),)

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(String(128))
    run_id: Mapped[str] = mapped_column(String(128))
    stage: Mapped[str] = mapped_column(String(128))
    sequence: Mapped[int] = mapped_column(BigInteger)
    payload_json: Mapped[dict[str, Any]] = mapped_column(JSONB)
    tenant_id: Mapped[str | None] = mapped_column(String(64))
    user_id: Mapped[str | None] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class RunResumeClaim(Base):
    __tablename__ = "agent_run_resume_claim"
    __table_args__ = (
        UniqueConstraint("session_id", "source_run_id"),
        UniqueConstraint("resumed_run_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(String(128))
    source_run_id: Mapped[str] = mapped_column(String(128))
    resumed_run_id: Mapped[str] = mapped_column(String(128))
    tenant_id: Mapped[str] = mapped_column(String(64))
    user_id: Mapped[str] = mapped_column(String(64))
    claimed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
