"""Mem0 历史表的删除映射；表结构仍由 Mem0 管理。"""

from sqlalchemy import String
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class MemoryHistory(Base):
    __tablename__ = "history"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    memory_id: Mapped[str] = mapped_column(String)
