from datetime import datetime, timedelta, timezone

from app.relevance import bm25_scores, rank, relevance_score, significant_terms, tokenize
from app.store import MemoryStore


def test_tokenize_mixed_cn_en():
    tokens = tokenize("Java 后端 经验")
    assert "java" in tokens
    assert "后" in tokens
    assert "后端" in tokens  # CJK bigram


def test_significant_terms_drops_cjk_singletons():
    terms = significant_terms("Java 后端")
    assert "java" in terms
    assert "后端" in terms
    assert "后" not in terms  # single CJK char excluded from candidate filter


def test_multi_word_out_of_order_query_matches():
    score = relevance_score("Java 后端 经验", "具备后端开发经验，熟悉 Java 与微服务")
    assert score > 0


def test_substring_only_query_no_longer_required():
    # 旧实现 LIKE '%Java 后端%' 整串匹配会返回 0；新实现按分词命中。
    score = relevance_score("Java 后端", "熟悉 Java，做过后端")
    assert score > 0


def test_ranking_orders_more_relevant_first():
    high = "Java 后端 高并发 微服务 后端架构"
    low = "前端 Vue 项目经验"
    items = [low, high]
    ranked = rank(
        "Java 后端",
        items,
        content_getter=lambda x: x,
        created_getter=lambda x: None,
        top_k=10,
    )
    assert ranked[0] == high


def test_top_k_truncation():
    items = [f"Java 后端 项目 {i}" for i in range(20)]
    ranked = rank("Java 后端", items, lambda x: x, lambda x: None, top_k=5)
    assert len(ranked) == 5


def test_recency_breaks_ties_for_empty_query():
    now = datetime.now(timezone.utc)
    older = (now - timedelta(days=10)).isoformat()
    newer = (now - timedelta(days=1)).isoformat()
    old_score = relevance_score("", "anything", older, now=now)
    new_score = relevance_score("", "anything", newer, now=now)
    assert new_score > old_score


def test_bm25_ranks_higher_idf_term_match_first():
    # "微服务" 在候选池中更稀有（高 IDF），命中它的文档应排在仅命中常见词的文档之前。
    docs = [
        tokenize("Java 后端 项目 Java 后端"),
        tokenize("Java 后端 微服务 架构"),
    ]
    scores = bm25_scores(set(tokenize("微服务")), docs)
    assert scores[1] > scores[0]


def test_rank_excludes_non_lexical_match_for_specific_query():
    # 有显著查询词时，完全不命中词法的近期记忆不应被时间信号带入结果。
    items = ["Java 后端 微服务经验", "今天天气晴朗心情不错"]
    ranked = rank("Java 后端", items, lambda x: x, lambda x: None, top_k=10)
    assert ranked == ["Java 后端 微服务经验"]


def test_memory_store_search_returns_ranked():
    store = MemoryStore()
    store.add("session", "前端 Vue 项目经验")
    store.add("session", "Java 后端 高并发 微服务经验")
    results = store.search("Java 后端", "session")
    assert results
    assert "Java" in results[0].content
