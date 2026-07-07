"""零依赖的记忆检索相关性打分与混合排序。

针对中英文混合的求职语料提供统一的分词、BM25 词法打分与时间衰减信号，供
PostgreSQL 与内存两套存储共用，保证候选召回后的重排行为一致且可单测。

排序采用 RRF（Reciprocal Rank Fusion）融合多路信号：内置"BM25 词法排序"与
"时间衰减排序"两路，调用方可经 vector_scores 并入第三路向量相似度（由
app.embedding 提供，默认关闭）。融合框架对图召回等后续信号保持开放，新增一路
只需把其排序结果并入融合即可。
"""

from __future__ import annotations

import math
import re
from collections import Counter
from datetime import datetime, timezone

_ASCII_RE = re.compile(r"[a-z0-9]+")
_CJK_RUN_RE = re.compile(r"[一-鿿]+")

# RRF 融合常数：排名靠前项权重更高，常数越大不同名次间差异越平滑。
_DEFAULT_RRF_K = 60
# BM25 参数：k1 控制词频饱和速度，b 控制文档长度归一强度。
_BM25_K1 = 1.5
_BM25_B = 0.75


def tokenize(text: str | None) -> list[str]:
    """将文本切分为可匹配的词单元。

    ASCII 字母数字按词切分；中文按"字 + 相邻二元组"切分。二元组提升中文匹配
    精度，单字保证短查询召回。
    """
    if not text:
        return []
    lowered = text.lower()
    tokens: list[str] = list(_ASCII_RE.findall(lowered))
    for run in _CJK_RUN_RE.findall(lowered):
        tokens.extend(run)
        for i in range(len(run) - 1):
            tokens.append(run[i : i + 2])
    return tokens


def significant_terms(query: str | None) -> list[str]:
    """提取用于候选召回的显著词：长度不小于 2 的 ASCII 词与中文二元组。

    过滤掉中文单字，避免候选阶段命中过宽。去重并保持稳定顺序。
    """
    seen: dict[str, None] = {}
    for term in tokenize(query):
        if len(term) >= 2:
            seen.setdefault(term, None)
    return list(seen.keys())


def _recency_boost(created_at_iso: str | None, now: datetime | None, half_life_days: float) -> float:
    if not created_at_iso or half_life_days <= 0:
        return 0.0
    try:
        created = datetime.fromisoformat(created_at_iso)
    except (ValueError, TypeError):
        return 0.0
    if created.tzinfo is None:
        created = created.replace(tzinfo=timezone.utc)
    reference = now or datetime.now(timezone.utc)
    age_days = max(0.0, (reference - created).total_seconds() / 86400.0)
    return 0.5 * (0.5 ** (age_days / half_life_days))


def relevance_score(
    query: str,
    content: str,
    created_at_iso: str | None = None,
    now: datetime | None = None,
    half_life_days: float = 30.0,
) -> float:
    """计算单条记忆相对查询的相关性分值（单文档启发式）。

    分值由"查询词命中的饱和词频 + 查询词覆盖率加权 + 时间衰减"构成。查询无词时
    退化为纯时间衰减。该函数用于单文档场景与单测，集合排序请使用 rank。
    """
    query_terms = set(tokenize(query))
    recency = _recency_boost(created_at_iso, now, half_life_days)
    if not query_terms:
        return recency

    content_terms = tokenize(content)
    if not content_terms:
        return 0.0
    counts = Counter(content_terms)

    matched = 0
    score = 0.0
    for term in query_terms:
        tf = counts.get(term, 0)
        if tf:
            matched += 1
            weight = 2.0 if len(term) >= 2 else 1.0
            score += weight * (tf / (tf + 1.0))
    if matched == 0:
        return 0.0

    coverage = matched / len(query_terms)
    return score * (0.5 + 0.5 * coverage) + recency


def bm25_scores(query_terms: set[str], docs_tokens: list[list[str]], k1: float = _BM25_K1, b: float = _BM25_B) -> list[float]:
    """对候选文档集合计算 BM25 词法分值。

    IDF 与平均文档长度在候选池内统计，符合 BM25 的语料相对性；候选池外文档不参与，
    保证排序只反映本次召回的相对相关性。
    """
    total = len(docs_tokens)
    if total == 0 or not query_terms:
        return [0.0] * total
    doc_freq: Counter = Counter()
    for tokens_list in docs_tokens:
        for term in set(tokens_list) & query_terms:
            doc_freq[term] += 1
    avgdl = sum(len(tokens_list) for tokens_list in docs_tokens) / total or 1.0

    scores: list[float] = []
    for tokens_list in docs_tokens:
        counts = Counter(tokens_list)
        doc_len = len(tokens_list)
        score = 0.0
        for term in query_terms:
            freq = counts.get(term, 0)
            if not freq:
                continue
            idf = math.log(1.0 + (total - doc_freq[term] + 0.5) / (doc_freq[term] + 0.5))
            denom = freq + k1 * (1.0 - b + b * doc_len / avgdl)
            score += idf * (freq * (k1 + 1.0)) / denom
        scores.append(score)
    return scores


def cosine_similarity(a: list[float] | None, b: list[float] | None) -> float:
    """余弦相似度，维度不符或零向量返回 0.0。"""
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(y * y for y in b))
    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0
    return dot / (norm_a * norm_b)


def _rrf_accumulate(fused: list[float], order: list[int], rrf_k: int) -> None:
    """按 RRF 公式把一路排序结果的贡献累加到融合分。"""
    for position, index in enumerate(order):
        fused[index] += 1.0 / (rrf_k + position + 1)


def rank(
    query: str,
    items: list,
    content_getter,
    created_getter,
    top_k: int,
    now: datetime | None = None,
    half_life_days: float = 30.0,
    rrf_k: int = _DEFAULT_RRF_K,
    vector_scores: list[float] | None = None,
    vector_min_score: float = 0.3,
) -> list:
    """对候选项做 BM25 + 时间衰减（+ 可选向量相似度）的 RRF 混合排序并截断 top-K。

    有显著查询词时，参与融合的候选为"词法命中 ∪ 向量达标"：向量一路负责补召回
    语义相近但用词不同的记忆，达标门槛 vector_min_score 防止弱相关候选借名次噪声
    挤入结果。vector_scores 缺省时行为与两路融合完全一致。查询无词时退化为按时间
    排序。平局时按创建时间降序，保证稳定可复现。
    """
    total = len(items)
    if total == 0:
        return []

    contents = [content_getter(item) for item in items]
    createds = [created_getter(item) or "" for item in items]
    docs_tokens = [tokenize(content) for content in contents]
    query_terms = set(tokenize(query))

    lexical = bm25_scores(query_terms, docs_tokens) if query_terms else [0.0] * total
    recency = [_recency_boost(created or None, now, half_life_days) for created in createds]
    if vector_scores is not None and len(vector_scores) != total:
        vector_scores = None
    vector = [score if score >= vector_min_score else 0.0 for score in vector_scores] if vector_scores else [0.0] * total

    if query_terms:
        eligible = [i for i in range(total) if lexical[i] > 0 or vector[i] > 0]
    else:
        eligible = list(range(total))
    if not eligible:
        return []

    fused = [0.0] * total
    lexical_order = sorted(
        (i for i in eligible if lexical[i] > 0 or not query_terms),
        key=lambda i: (lexical[i], recency[i], createds[i]),
        reverse=True,
    )
    _rrf_accumulate(fused, lexical_order, rrf_k)
    vector_eligible = [i for i in eligible if vector[i] > 0]
    vector_order = sorted(vector_eligible, key=lambda i: (vector[i], lexical[i], createds[i]), reverse=True)
    _rrf_accumulate(fused, vector_order, rrf_k)
    # 时间信号只在存在真实时间差异的候选间融合：无时间戳（recency 全为 0）时不参与，
    # 否则等值时间会以任意稳定顺序注入名次噪声，抵消词法排序。平局按词法分回退。
    recency_eligible = [i for i in eligible if recency[i] > 0]
    recency_order = sorted(recency_eligible, key=lambda i: (recency[i], lexical[i], createds[i]), reverse=True)
    _rrf_accumulate(fused, recency_order, rrf_k)

    scored = [(fused[i], createds[i], items[i]) for i in eligible if fused[i] > 0]
    scored.sort(key=lambda triple: (triple[0], triple[1]), reverse=True)
    limited = scored if top_k <= 0 else scored[:top_k]
    return [item for _, _, item in limited]
