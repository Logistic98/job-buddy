from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, ConfigDict, Field, model_validator


class WorkflowStepDefinition(BaseModel):
    """只读工作流步骤描述。

    Runtime 仅消费这些字段用于路由解释和 Trace，不把 external_action/external_event
    解释为可执行指令。
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    id: str
    name: str
    runtime_node: Optional[str] = None
    external_action: Optional[str] = None
    external_event: Optional[str] = None

    @model_validator(mode="after")
    def validate_step_kind(self) -> "WorkflowStepDefinition":
        targets = [self.runtime_node, self.external_action, self.external_event]
        if sum(bool(str(item or "").strip()) for item in targets) != 1:
            raise ValueError("workflow step 必须且只能声明 runtime_node、external_action、external_event 之一")
        return self


class WorkflowDefinition(BaseModel):
    """配置目录中的只读 Workflow 定义。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    id: str
    name: str
    profile: Optional[str] = None
    entry_capability: str
    owner: str
    summary: str = ""
    steps: List[WorkflowStepDefinition] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_workflow(self) -> "WorkflowDefinition":
        if not self.id.strip():
            raise ValueError("workflow id 不能为空")
        if not self.entry_capability.strip():
            raise ValueError("workflow entry_capability 不能为空")
        if not self.steps:
            raise ValueError("workflow steps 不能为空")
        step_ids = [step.id for step in self.steps]
        if len(step_ids) != len(set(step_ids)):
            raise ValueError("workflow step id 不能重复")
        return self

    def metadata(self) -> Dict[str, Any]:
        """返回面向 task/directive/trace 的只读描述副本。"""
        return self.model_dump(mode="json")
