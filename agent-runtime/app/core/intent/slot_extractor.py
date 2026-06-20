
"""配置驱动的槽位抽取器。

槽位抽取是任务理解中一个独立职责：根据 Profile 声明的 keyword_map / regex 抽取器，
从用户消息中解析结构化槽位。Runtime Core 只理解通用抽取类型，城市、岗位、薪资等
具体规则全部来自 Profile YAML。
"""

from __future__ import annotations

import re
from typing import Any, Dict

from app.core.capability.models import ProfileDefinition, SlotExtractorConfig
from app.core.intent.text_match import coerce_value, normalize_text, phrase_match


class SlotExtractor:
    def extract(self, profile: ProfileDefinition, message: str) -> Dict[str, Any]:
        slots: Dict[str, Any] = {}
        text = normalize_text(message)
        for extractor in profile.slot_extractors:
            if extractor.type == "keyword_map":
                self._extract_keyword_map_slots(extractor, text, slots)
            elif extractor.type == "regex":
                self._extract_regex_slots(extractor, message, slots)
        return slots

    def _extract_keyword_map_slots(self, extractor: SlotExtractorConfig, normalized_message: str, slots: Dict[str, Any]) -> None:
        for row in extractor.values:
            aliases = [str(item) for item in row.get("aliases", [])]
            value = row.get("value")
            if any(phrase_match(alias, normalized_message) for alias in aliases + ([str(value)] if value else [])):
                if row.get("set_slots") and isinstance(row.get("set_slots"), dict):
                    slots.update(row["set_slots"])
                elif extractor.name == "preferences":
                    slots.setdefault("preferences", [])
                    if value not in slots["preferences"]:
                        slots["preferences"].append(value)
                else:
                    slots[extractor.name] = value

    def _extract_regex_slots(self, extractor: SlotExtractorConfig, message: str, slots: Dict[str, Any]) -> None:
        flags = re.IGNORECASE
        for pattern in extractor.patterns:
            match = re.search(pattern, message, flags)
            if not match:
                continue
            groups = match.groupdict()
            if groups:
                for key, value in groups.items():
                    if value is not None:
                        slots[key] = coerce_value(value)
            else:
                for target_slot, group_name in extractor.target_slots.items():
                    value = match.group(group_name) if group_name.isdigit() else match.groupdict().get(group_name)
                    if value is not None:
                        slots[target_slot] = coerce_value(value)
            break
