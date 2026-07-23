from __future__ import annotations

from pathlib import Path
from typing import Dict, List, Optional

import yaml
from loguru import logger

from app.core.capability.models import CapabilityCard, ProfileDefinition
from app.core.common.settings import settings


class CapabilityRegistry:
    """Profile / Capability Card 注册中心。

    参考 Tool Search 与 Skill Progressive Disclosure 的思想：Runtime Core 只加载稳定、结构化的能力卡；
    业务差异通过 YAML 注入，避免把 Boss、简历、岗位等业务规则写进 Python 核心代码。
    """

    def __init__(self, profiles_dir: str | None = None):
        configured_dir = Path(profiles_dir or settings.profiles_dir)
        if not configured_dir.is_absolute() and not configured_dir.exists():
            module_relative = Path(__file__).resolve().parents[3] / configured_dir
            if module_relative.exists():
                configured_dir = module_relative
        self.profiles_dir = configured_dir
        self._profiles: Dict[str, ProfileDefinition] = {}
        self.reload()

    def reload(self) -> None:
        self._profiles = {}
        if self.profiles_dir.exists():
            for path in sorted(self.profiles_dir.glob("*.y*ml")):
                try:
                    profile = self._load_profile(path)
                    self._profiles[profile.id] = profile
                    logger.info(f"Profile 加载成功：profile={profile.id}, capabilities={len(profile.capabilities)}")
                except Exception as exc:
                    logger.exception(f"Profile 加载失败：path={path}, error={exc}")
        if "default" not in self._profiles:
            self._profiles["default"] = self._default_profile()

    def get_profile(self, profile_id: str | None) -> ProfileDefinition:
        if profile_id and profile_id in self._profiles:
            return self._profiles[profile_id]
        return self._profiles.get("default") or self._default_profile()

    def list_profiles(self) -> List[ProfileDefinition]:
        return [self._profiles[key] for key in sorted(self._profiles)]

    def list_capabilities(self, profile_id: str | None) -> List[CapabilityCard]:
        profile = self.get_profile(profile_id)
        return sorted(profile.capabilities, key=lambda item: item.id)

    def find_capability(
        self, profile_id: str | None, capability_id: str | None = None, intent: str | None = None
    ) -> Optional[CapabilityCard]:
        profile = self.get_profile(profile_id)
        if capability_id:
            found = profile.capability_by_id(capability_id)
            if found:
                return found
        if intent:
            return profile.capability_by_intent(intent)
        return None

    def _load_profile(self, path: Path) -> ProfileDefinition:
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        return ProfileDefinition.model_validate(data)

    def _default_profile(self) -> ProfileDefinition:
        return ProfileDefinition(
            id="default",
            name="Default Agent Runtime Profile",
            domain="general",
            default_capability_id="general.chat",
            capabilities=[
                CapabilityCard(
                    id="general.chat",
                    name="通用问答",
                    domain="general",
                    intent="chat",
                    execution_intent="answer_question",
                    execution_mode="OPEN_DOMAIN_QA",
                    description="回答普通问题，或在没有业务 Profile 时交给通用 Planner 处理。",
                    examples=["hello runtime", "请回显 hello", "解释一个技术问题"],
                    keywords=["hello", "runtime", "回显", "解释", "是什么", "如何"],
                    next_action="run_runtime_planner",
                    planner_needed=True,
                    allowed_tools=["echo"],
                )
            ],
        )
