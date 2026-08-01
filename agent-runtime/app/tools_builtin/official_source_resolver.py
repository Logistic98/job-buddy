"""Parse trusted official listing pages without performing network access."""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime
from html import unescape
from html.parser import HTMLParser
from typing import Iterable, Sequence
from urllib.parse import unquote, urljoin, urlsplit, urlunsplit

_IGNORED_TEXT_TAGS = frozenset({"script", "style", "template", "noscript", "svg"})
_VOID_TAGS = frozenset(
    {
        "area",
        "base",
        "br",
        "col",
        "embed",
        "hr",
        "img",
        "input",
        "link",
        "meta",
        "param",
        "source",
        "track",
        "wbr",
    }
)
_CARD_CLASS_MARKERS = frozenset({"article", "blog", "card", "featured", "post", "story"})
_FEATURED_CLASS_MARKERS = frozenset({"feature", "featured", "hero", "spotlight"})
_DATE_CLASS_MARKERS = frozenset({"date", "datetime", "publish", "published", "timestamp"})
_MONTHS = {
    "jan": 1,
    "january": 1,
    "feb": 2,
    "february": 2,
    "mar": 3,
    "march": 3,
    "apr": 4,
    "april": 4,
    "may": 5,
    "jun": 6,
    "june": 6,
    "jul": 7,
    "july": 7,
    "aug": 8,
    "august": 8,
    "sep": 9,
    "sept": 9,
    "september": 9,
    "oct": 10,
    "october": 10,
    "nov": 11,
    "november": 11,
    "dec": 12,
    "december": 12,
}
_MONTH_PATTERN = "|".join(sorted(_MONTHS, key=len, reverse=True))


@dataclass(frozen=True, slots=True)
class OfficialArticle:
    """An immutable article candidate discovered on an official listing page."""

    title: str
    url: str
    published_date: str | None = None
    summary: str = ""
    featured: bool = False

    @property
    def needs_metadata_fetch(self) -> bool:
        """Whether the article page must be inspected before date-based ranking."""

        return self.published_date is None


@dataclass(frozen=True, slots=True)
class OfficialArticleMetadata:
    """Immutable metadata parsed from one official article page."""

    title: str = ""
    description: str = ""
    published_date: str | None = None


class _Node:
    __slots__ = ("attrs", "contents", "parent", "tag")

    def __init__(self, tag: str, attrs: dict[str, str], parent: _Node | None = None) -> None:
        self.tag = tag
        self.attrs = attrs
        self.parent = parent
        self.contents: list[_Node | str] = []


class _TreeParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = _Node("document", {})
        self._stack = [self.root]

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        normalized_tag = tag.lower()
        node = _Node(
            normalized_tag,
            {str(key).lower(): str(value or "") for key, value in attrs},
            parent=self._stack[-1],
        )
        self._stack[-1].contents.append(node)
        if normalized_tag not in _VOID_TAGS:
            self._stack.append(node)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)
        if tag.lower() not in _VOID_TAGS:
            self._stack.pop()

    def handle_endtag(self, tag: str) -> None:
        normalized_tag = tag.lower()
        for index in range(len(self._stack) - 1, 0, -1):
            if self._stack[index].tag == normalized_tag:
                del self._stack[index:]
                return

    def handle_data(self, data: str) -> None:
        if data:
            self._stack[-1].contents.append(data)


def normalize_published_date(value: str | date | datetime | None) -> str | None:
    """Normalize supported ISO, English, and Chinese dates to ``YYYY-MM-DD``."""

    if isinstance(value, datetime):
        return value.date().isoformat()
    if isinstance(value, date):
        return value.isoformat()
    text = re.sub(r"\s+", " ", str(value or "")).strip()
    if not text:
        return None

    iso_match = re.search(r"(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)", text)
    if iso_match:
        return _validated_date(*iso_match.groups())

    chinese_match = re.search(r"(?<!\d)(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日?", text)
    if chinese_match:
        return _validated_date(*chinese_match.groups())

    month_first_match = re.search(
        rf"\b({_MONTH_PATTERN})\.?\s+(\d{{1,2}})(?:st|nd|rd|th)?\s*,?\s*(\d{{4}})\b",
        text,
        re.IGNORECASE,
    )
    if month_first_match:
        month_name, day_value, year_value = month_first_match.groups()
        return _validated_date(year_value, _MONTHS[month_name.lower()], day_value)

    day_first_match = re.search(
        rf"\b(\d{{1,2}})(?:st|nd|rd|th)?\s+({_MONTH_PATTERN})\.?\s*,?\s*(\d{{4}})\b",
        text,
        re.IGNORECASE,
    )
    if day_first_match:
        day_value, month_name, year_value = day_first_match.groups()
        return _validated_date(year_value, _MONTHS[month_name.lower()], day_value)
    return None


def canonicalize_official_url(
    value: str,
    *,
    base_url: str,
    trusted_hosts: Sequence[str],
    allowed_path_prefixes: Sequence[str],
) -> str | None:
    """Return a canonical trusted article URL, or ``None`` when any boundary fails."""

    candidate = urljoin(str(base_url or "").strip(), unescape(str(value or "").strip()))
    try:
        parsed = urlsplit(candidate)
        port = parsed.port
    except ValueError:
        return None
    host = (parsed.hostname or "").lower()
    normalized_hosts = {str(item).strip().lower() for item in trusted_hosts if str(item).strip()}
    if parsed.scheme.lower() != "https" or not host or host not in normalized_hosts:
        return None
    if parsed.username is not None or parsed.password is not None or port not in (None, 443):
        return None

    raw_path = parsed.path or "/"
    decoded_path = _fully_unquote_path(raw_path)
    if _has_unsafe_path(decoded_path):
        return None
    normalized_prefixes = tuple(_normalize_path_prefix(item) for item in allowed_path_prefixes)
    if not any(_path_matches_prefix(decoded_path, prefix) for prefix in normalized_prefixes if prefix):
        return None

    canonical_path = raw_path.rstrip("/") or "/"
    return urlunsplit(("https", host, canonical_path, "", ""))


def parse_official_listing(
    html: str,
    *,
    base_url: str,
    trusted_hosts: Sequence[str],
    allowed_path_prefixes: Sequence[str],
) -> tuple[OfficialArticle, ...]:
    """Parse trusted article cards while retaining undated featured candidates."""

    root = _parse_tree(html)
    articles_by_url: dict[str, OfficialArticle] = {}
    for anchor in _iter_nodes(root, tag="a"):
        url = canonicalize_official_url(
            anchor.attrs.get("href", ""),
            base_url=base_url,
            trusted_hosts=trusted_hosts,
            allowed_path_prefixes=allowed_path_prefixes,
        )
        if not url or _is_collection_root(url, allowed_path_prefixes):
            continue
        card = _article_card(anchor)
        title = _article_title(anchor, card)
        if not title:
            continue
        article = OfficialArticle(
            title=title,
            url=url,
            published_date=_published_date_in_node(card),
            summary=_article_summary(card, title),
            featured=_is_featured(anchor, card),
        )
        existing = articles_by_url.get(url)
        if existing is None or _article_completeness(article) > _article_completeness(existing):
            articles_by_url[url] = article
    return tuple(articles_by_url.values())


def parse_official_article(html: str) -> OfficialArticleMetadata:
    """Parse an article heading, meta description, and published date from HTML."""

    root = _parse_tree(html)
    heading = next((node for node in _iter_nodes(root, tag="h1") if _node_text(node)), None)
    title = _node_text(heading) if heading else _meta_content(root, ("og:title", "twitter:title"))
    if not title:
        title_node = next(_iter_nodes(root, tag="title"), None)
        title = _node_text(title_node) if title_node else ""
    description = _meta_content(root, ("description",), prefer_name=True)
    if not description:
        description = _meta_content(root, ("og:description", "twitter:description"))
    return OfficialArticleMetadata(
        title=title,
        description=description,
        published_date=_published_date_in_document(root),
    )


def select_latest_article(
    articles: Iterable[OfficialArticle],
    *,
    as_of_date: str | date | datetime,
    not_before_date: str | date | datetime | None = None,
) -> OfficialArticle | None:
    """Select the newest dated article that was published no later than ``as_of_date``."""

    normalized_cutoff = normalize_published_date(as_of_date)
    if normalized_cutoff is None:
        raise ValueError("as_of_date must contain a valid supported date")
    cutoff = date.fromisoformat(normalized_cutoff)
    normalized_start = normalize_published_date(not_before_date)
    if not_before_date is not None and normalized_start is None:
        raise ValueError("not_before_date must contain a valid supported date")
    start = date.fromisoformat(normalized_start) if normalized_start else None
    if start is not None and start > cutoff:
        raise ValueError("not_before_date must be no later than as_of_date")
    eligible: list[tuple[date, OfficialArticle]] = []
    for article in articles:
        normalized_date = normalize_published_date(article.published_date)
        if normalized_date is None:
            continue
        published = date.fromisoformat(normalized_date)
        if (start is None or published >= start) and published <= cutoff:
            eligible.append((published, article))
    return max(eligible, key=lambda item: item[0])[1] if eligible else None


def _validated_date(year: str | int, month: str | int, day: str | int) -> str | None:
    try:
        return date(int(year), int(month), int(day)).isoformat()
    except (TypeError, ValueError):
        return None


def _parse_tree(value: str) -> _Node:
    parser = _TreeParser()
    parser.feed(str(value or ""))
    parser.close()
    return parser.root


def _iter_nodes(node: _Node, *, tag: str | None = None) -> Iterable[_Node]:
    for item in node.contents:
        if not isinstance(item, _Node):
            continue
        if tag is None or item.tag == tag:
            yield item
        yield from _iter_nodes(item, tag=tag)


def _node_text(node: _Node | None) -> str:
    if node is None or node.tag in _IGNORED_TEXT_TAGS:
        return ""
    parts: list[str] = []
    for item in node.contents:
        parts.append(_node_text(item) if isinstance(item, _Node) else item)
    return re.sub(r"\s+", " ", " ".join(parts)).strip()


def _class_markers(node: _Node) -> set[str]:
    value = " ".join((node.attrs.get("class", ""), node.attrs.get("id", ""))).lower()
    return {item for item in re.split(r"[^a-z0-9]+", value) if item}


def _has_marker(node: _Node, markers: frozenset[str]) -> bool:
    tokens = _class_markers(node)
    return bool(tokens & markers or any(marker in token for token in tokens for marker in markers))


def _article_card(anchor: _Node) -> _Node:
    current: _Node | None = anchor
    while current and current.tag not in {"main", "body", "document"}:
        if current.tag in {"article", "li"} or _has_marker(current, _CARD_CLASS_MARKERS):
            return current
        current = current.parent
    return anchor


def _article_title(anchor: _Node, card: _Node) -> str:
    for scope in (anchor, card):
        heading = next(
            (node for node in _iter_nodes(scope) if node.tag in {"h1", "h2", "h3", "h4"} and _node_text(node)),
            None,
        )
        if heading:
            return _node_text(heading)
    labelled_title = re.sub(r"\s+", " ", anchor.attrs.get("aria-label", "")).strip()
    if labelled_title:
        return labelled_title
    if card is not anchor or _has_marker(anchor, _CARD_CLASS_MARKERS):
        return _node_text(anchor)
    return ""


def _article_summary(card: _Node, title: str) -> str:
    preferred = [
        node for node in _iter_nodes(card) if _has_marker(node, frozenset({"description", "excerpt", "summary"}))
    ]
    paragraphs = list(_iter_nodes(card, tag="p"))
    for node in (*preferred, *paragraphs):
        candidate = _node_text(node)
        if candidate and candidate != title and normalize_published_date(candidate) is None:
            return candidate
    return ""


def _published_date_in_node(node: _Node) -> str | None:
    for descendant in _iter_nodes(node):
        if descendant.tag == "time":
            normalized = normalize_published_date(
                descendant.attrs.get("datetime") or descendant.attrs.get("date") or _node_text(descendant)
            )
            if normalized:
                return normalized
        if _has_marker(descendant, _DATE_CLASS_MARKERS):
            normalized = normalize_published_date(
                descendant.attrs.get("datetime") or descendant.attrs.get("content") or _node_text(descendant)
            )
            if normalized:
                return normalized
        if descendant.tag in {"p", "div", "span", "small"}:
            visible_text = _node_text(descendant)
            if len(visible_text) <= 240 and re.search(
                r"\b(?:published|posted|released)\b|发布(?:于|日期)?",
                visible_text,
                re.IGNORECASE,
            ):
                normalized = normalize_published_date(visible_text)
                if normalized:
                    return normalized
    return None


def _published_date_in_document(root: _Node) -> str | None:
    published_meta_keys = {
        "article:published_time",
        "date",
        "datepublished",
        "publish_date",
        "publishdate",
    }
    for node in _iter_nodes(root, tag="meta"):
        key = (node.attrs.get("property") or node.attrs.get("name") or node.attrs.get("itemprop") or "").lower()
        if key in published_meta_keys:
            normalized = normalize_published_date(node.attrs.get("content"))
            if normalized:
                return normalized
    return _published_date_in_node(root)


def _meta_content(root: _Node, keys: Sequence[str], *, prefer_name: bool = False) -> str:
    normalized_keys = {item.lower() for item in keys}
    for node in _iter_nodes(root, tag="meta"):
        primary = node.attrs.get("name") if prefer_name else node.attrs.get("property") or node.attrs.get("name")
        if str(primary or "").lower() in normalized_keys:
            return re.sub(r"\s+", " ", node.attrs.get("content", "")).strip()
    return ""


def _is_featured(anchor: _Node, card: _Node) -> bool:
    current: _Node | None = anchor
    while current:
        if _has_marker(current, _FEATURED_CLASS_MARKERS):
            return True
        if current is card:
            break
        current = current.parent
    return _has_marker(card, _FEATURED_CLASS_MARKERS)


def _article_completeness(article: OfficialArticle) -> tuple[int, int, int]:
    return (int(article.published_date is not None), int(bool(article.summary)), int(article.featured))


def _normalize_path_prefix(value: str) -> str:
    prefix = _fully_unquote_path(str(value or "").strip())
    if not prefix.startswith("/"):
        prefix = f"/{prefix}"
    return prefix.rstrip("/") or "/"


def _path_matches_prefix(path: str, prefix: str) -> bool:
    return prefix == "/" or path == prefix or path.startswith(f"{prefix}/")


def _has_unsafe_path(path: str) -> bool:
    if "\\" in path or any(ord(character) < 32 for character in path):
        return True
    return any(segment in {".", ".."} for segment in path.split("/"))


def _fully_unquote_path(value: str) -> str:
    decoded = value
    for _ in range(3):
        next_value = unquote(decoded)
        if next_value == decoded:
            return decoded
        decoded = next_value
    return decoded


def _is_collection_root(url: str, allowed_path_prefixes: Sequence[str]) -> bool:
    path = unquote(urlsplit(url).path).rstrip("/") or "/"
    return any(path == _normalize_path_prefix(prefix) for prefix in allowed_path_prefixes)
