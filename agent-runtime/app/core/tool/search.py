import math
from collections import Counter
from typing import Dict, List, Tuple

from app.core.common.settings import settings
from app.core.tool.registry import ToolRegistry
from app.models.schemas import ToolDefinition


class ToolSearchService:
    """Tool Search 服务。

    本地实现采用 BM25 风格词项召回 + 轻量语义字段召回，并用 RRF 融合；返回结果按分数和工具名稳定排序，
    避免工具 Schema 顺序抖动影响 Prompt Cache。
    """

    def __init__(self, registry: ToolRegistry):
        self.registry = registry

    async def search(self, query: str, limit: int = 8) -> List[ToolDefinition]:
        tools = self.registry.list_definitions()
        if not tools:
            return []
        terms = self._tokens(query)
        always_loaded = [tool for tool in tools if tool.always_load]
        if not terms:
            return self._dedupe(always_loaded + tools, limit)

        bm25_rank = self._rank_bm25(terms, tools)
        semantic_rank = self._rank_semantic(terms, tools)
        fused = self._rrf([bm25_rank, semantic_rank])
        ranked = sorted(tools, key=lambda tool: (-fused.get(tool.name, 0.0), tool.name))
        selected = [tool for tool in ranked if fused.get(tool.name, 0.0) > 0]
        fallback_limit = min(limit, settings.config.tool_search.fallback_limit)
        return self._dedupe(always_loaded + selected, limit) or tools[:fallback_limit]

    def _rank_bm25(self, terms: List[str], tools: List[ToolDefinition]) -> List[Tuple[str, float]]:
        docs = {tool.name: self._tokens(self._document(tool)) for tool in tools}
        avgdl = sum(len(tokens) for tokens in docs.values()) / max(1, len(docs))
        doc_freq: Dict[str, int] = {}
        for term in set(terms):
            doc_freq[term] = sum(1 for tokens in docs.values() if term in tokens)
        scores = []
        for tool in tools:
            tokens = docs[tool.name]
            counts = Counter(tokens)
            score = 0.0
            for term in terms:
                if counts[term] <= 0:
                    continue
                idf = math.log(1 + (len(docs) - doc_freq.get(term, 0) + 0.5) / (doc_freq.get(term, 0) + 0.5))
                tf = counts[term]
                denom = tf + 1.2 * (1 - 0.75 + 0.75 * len(tokens) / max(avgdl, 1))
                score += idf * (tf * 2.2) / denom
            if score > 0:
                scores.append((tool.name, score))
        return sorted(scores, key=lambda item: (-item[1], item[0]))

    def _rank_semantic(self, terms: List[str], tools: List[ToolDefinition]) -> List[Tuple[str, float]]:
        scores = []
        query = " ".join(terms)
        for tool in tools:
            name_text = " ".join([tool.name, *tool.aliases]).lower()
            hint_text = " ".join([tool.search_hint or "", " ".join(tool.tags), tool.description]).lower()
            score = 0.0
            for term in terms:
                if term in name_text:
                    score += 3.0
                if term in hint_text:
                    score += 1.0
            if query and query in hint_text:
                score += 2.0
            if score > 0:
                scores.append((tool.name, score))
        return sorted(scores, key=lambda item: (-item[1], item[0]))

    def _rrf(self, ranks: List[List[Tuple[str, float]]], k: int = 60) -> Dict[str, float]:
        fused: Dict[str, float] = {}
        for rank in ranks:
            for index, (name, _) in enumerate(rank):
                fused[name] = fused.get(name, 0.0) + 1.0 / (k + index + 1)
        return fused

    def _document(self, tool: ToolDefinition) -> str:
        return " ".join(
            [
                tool.name,
                " ".join(tool.aliases),
                tool.search_hint or "",
                tool.description,
                " ".join(tool.tags),
            ]
        )

    def _tokens(self, text: str) -> List[str]:
        raw = (text or "").replace("_", " ").replace(".", " ").replace("-", " ").lower()
        tokens = [item.strip() for item in raw.split() if item.strip()]
        # 中文短查询通常无法按空格切词，保留原始短语作为召回项。
        compact = raw.strip()
        if compact and compact not in tokens:
            tokens.append(compact)
        return tokens

    def _dedupe(self, tools: List[ToolDefinition], limit: int) -> List[ToolDefinition]:
        deduped = []
        seen = set()
        for tool in tools:
            if tool.name in seen:
                continue
            seen.add(tool.name)
            deduped.append(tool)
            if len(deduped) >= limit:
                break
        return deduped
