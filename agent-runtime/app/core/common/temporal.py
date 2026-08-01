"""跨任务理解、Planner 与工具共享的时间选择语义。"""

import re

LATEST_SELECTION_PATTERN = re.compile(
    r"(?:最新(?:一篇|一个|一款)?|最近一篇|"
    r"最近(?:发布|发表|更新|上线|推出)(?:的)?(?:一篇|一个|一款)?|"
    r"最晚(?:发布|发表|更新|上线|推出)(?:的)?|"
    r"\b(?:latest|newest)\b)",
    re.IGNORECASE,
)


def requests_latest_selection(*values: object) -> bool:
    """判断文本是否要求按时间选出唯一最新项，而非普通时效性检索。"""

    return any(LATEST_SELECTION_PATTERN.search(str(value or "")) for value in values)
