from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class SlotExtractorConfig(BaseModel):
    """配置驱动槽位抽取器。

    Runtime Core 只理解通用抽取类型，具体城市、岗位、短语、正则都放在 Profile YAML 中。
    """

    name: str
    type: str = "keyword_map"
    values: List[Dict[str, Any]] = Field(default_factory=list)
    patterns: List[str] = Field(default_factory=list)
    target_slots: Dict[str, str] = Field(default_factory=dict)


class ConversationShortcut(BaseModel):
    id: str
    phrases: List[str] = Field(default_factory=list)
    patterns: List[str] = Field(default_factory=list)
    capability_id: str
    slot_updates: Dict[str, Any] = Field(default_factory=dict)
    required_previous_slots: List[str] = Field(default_factory=list)
    reuse_previous_slots: bool = False
    resolved_query: Optional[str] = None
    retrieval_query: Optional[str] = None
    planner_query: Optional[str] = None
    context_type: List[str] = Field(default_factory=list)
    secondary: List[str] = Field(default_factory=list)
    confidence: float = 0.9
    reason: str = "conversation shortcut"


class CapabilityCard(BaseModel):
    """面向 Agent 的能力卡。

    能力卡是 Planner 前置路由的最小单元，描述任务语义、槽位、风险和下一步动作。
    """

    id: str
    name: str
    domain: str = "general"
    intent: str
    execution_intent: str = "answer_question"
    execution_mode: str = "OPEN_DOMAIN_QA"
    description: str = ""
    examples: List[str] = Field(default_factory=list)
    negative_examples: List[str] = Field(default_factory=list)
    keywords: List[str] = Field(default_factory=list)
    negative_keywords: List[str] = Field(default_factory=list)
    required_slots: List[str] = Field(default_factory=list)
    optional_slots: List[str] = Field(default_factory=list)
    risk: str = "low"
    next_action: str = "direct_answer"
    context_dependencies: List[str] = Field(default_factory=list)
    secondary: List[str] = Field(default_factory=list)
    business_tags: List[str] = Field(default_factory=list)
    capability_tags: List[str] = Field(default_factory=list)
    tool_semantics: Dict[str, Any] = Field(default_factory=dict)
    clarification_question: Optional[str] = None
    answer_template: Optional[str] = None
    planner_needed: bool = False
    required_tools: List[str] = Field(default_factory=list)
    allowed_tools: List[str] = Field(default_factory=list)
    evidence_requirements: List[str] = Field(default_factory=list)
    eval_rubric: Dict[str, Any] = Field(default_factory=dict)

    def searchable_text(self) -> str:
        parts = [
            self.id,
            self.name,
            self.domain,
            self.intent,
            self.execution_intent,
            self.execution_mode,
            self.description,
            " ".join(self.examples),
            " ".join(self.keywords),
            " ".join(self.business_tags),
            " ".join(self.capability_tags),
        ]
        return "\n".join(part for part in parts if part)


class ProfileDefinition(BaseModel):
    id: str
    name: str
    domain: str = "general"
    directive_type: Optional[str] = None
    default_capability_id: Optional[str] = None
    # 哪些 BFF 入口允许 Runtime 继续进入 Agent Loop 托管执行；其余入口只返回 directive。
    # 配置化此前散落在 Graph 节点中的 entrypoint 白名单与 chat.stream/intent.classify 硬编码。
    runtime_entrypoints: List[str] = Field(
        default_factory=lambda: ["chat.ask", "agent.run", "runtime.run", "runtime.execute"]
    )
    prompts: Dict[str, str] = Field(default_factory=dict)
    preferences: Dict[str, Any] = Field(default_factory=dict)
    planner_defaults: Dict[str, Any] = Field(default_factory=dict)
    slot_extractors: List[SlotExtractorConfig] = Field(default_factory=list)
    conversation_shortcuts: List[ConversationShortcut] = Field(default_factory=list)
    capabilities: List[CapabilityCard] = Field(default_factory=list)

    def capability_by_id(self, capability_id: str) -> Optional[CapabilityCard]:
        for item in self.capabilities:
            if item.id == capability_id:
                return item
        return None

    def capability_by_intent(self, intent: str) -> Optional[CapabilityCard]:
        for item in self.capabilities:
            if item.intent == intent:
                return item
        return None
