from __future__ import annotations

from pathlib import Path
from typing import Dict, List, Optional

import yaml
from loguru import logger

from app.core.capability.registry import CapabilityRegistry
from app.core.common.settings import settings
from app.core.workflow.models import WorkflowDefinition


class WorkflowRegistry:
    """加载并校验 config/workflows，仅提供只读匹配能力。"""

    def __init__(
        self,
        workflows_dir: str | None = None,
        capability_registry: CapabilityRegistry | None = None,
    ):
        configured_dir = Path(workflows_dir or settings.workflows_dir)
        if not configured_dir.is_absolute() and not configured_dir.exists():
            module_relative = Path(__file__).resolve().parents[3] / configured_dir
            if module_relative.exists():
                configured_dir = module_relative
        self.workflows_dir = configured_dir
        self.capability_registry = capability_registry or CapabilityRegistry()
        self._workflows: Dict[str, WorkflowDefinition] = {}
        self._by_entry: Dict[tuple[str, str], WorkflowDefinition] = {}
        self.reload()

    def reload(self) -> None:
        """重新加载全部工作流，并原子替换当前只读索引。"""
        workflows: Dict[str, WorkflowDefinition] = {}
        by_entry: Dict[tuple[str, str], WorkflowDefinition] = {}
        if self.workflows_dir.exists():
            for path in sorted(self.workflows_dir.glob("*.y*ml")):
                workflow = self._load_workflow(path)
                self._validate_capability_reference(workflow, path)
                if workflow.id in workflows:
                    raise ValueError(f"workflow id 重复: {workflow.id}")
                key = (workflow.profile or "", workflow.entry_capability)
                if key in by_entry:
                    raise ValueError(
                        f"workflow entry_capability 重复: profile={workflow.profile or '*'}, "
                        f"entry_capability={workflow.entry_capability}"
                    )
                workflows[workflow.id] = workflow
                by_entry[key] = workflow
                logger.info(f"Workflow 加载成功：workflow={workflow.id}, entry_capability={workflow.entry_capability}")
        self._workflows = workflows
        self._by_entry = by_entry

    def get(self, workflow_id: str) -> Optional[WorkflowDefinition]:
        """按工作流标识返回不可变定义；不存在时返回 ``None``。"""
        return self._workflows.get(workflow_id)

    def list_workflows(self) -> List[WorkflowDefinition]:
        """按工作流标识排序返回当前注册表快照。"""
        return [self._workflows[key] for key in sorted(self._workflows)]

    def match(self, entry_capability: str | None, profile: str | None = None) -> Optional[WorkflowDefinition]:
        """优先匹配指定 Profile 的入口能力，未命中时回退到全局工作流。"""
        capability = str(entry_capability or "").strip()
        if not capability:
            return None
        profile_id = str(profile or "").strip()
        return self._by_entry.get((profile_id, capability)) or self._by_entry.get(("", capability))

    def _validate_capability_reference(self, workflow: WorkflowDefinition, path: Path) -> None:
        profiles = {profile.id: profile for profile in self.capability_registry.list_profiles()}
        if workflow.profile:
            profile = profiles.get(workflow.profile)
            if profile is None:
                raise ValueError(f"Workflow 配置交叉校验失败: path={path}, profile 不存在: {workflow.profile}")
            if profile.capability_by_id(workflow.entry_capability) is None:
                raise ValueError(
                    f"Workflow 配置交叉校验失败: path={path}, profile={workflow.profile} "
                    f"不存在 entry_capability={workflow.entry_capability}"
                )
            return
        if not any(profile.capability_by_id(workflow.entry_capability) for profile in profiles.values()):
            raise ValueError(
                f"Workflow 配置交叉校验失败: path={path}, entry_capability 不存在: {workflow.entry_capability}"
            )

    def _load_workflow(self, path: Path) -> WorkflowDefinition:
        try:
            data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            return WorkflowDefinition.model_validate(data)
        except Exception as exc:
            raise ValueError(f"Workflow 配置校验失败: path={path}, error={exc}") from exc
