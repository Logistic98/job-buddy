from dataclasses import FrozenInstanceError
from datetime import date

import pytest

from app.tools_builtin.official_source_resolver import (
    OfficialArticle,
    canonicalize_official_url,
    normalize_published_date,
    parse_official_article,
    parse_official_listing,
    select_latest_article,
)

TRUSTED_HOSTS = ("www.anthropic.com",)
ALLOWED_PATH_PREFIXES = ("/engineering",)


def test_parse_official_listing_extracts_only_allowlisted_article_cards():
    html = """
    <main>
      <article class="post-card">
        <a href="/engineering/building-effective-agents?utm_source=home#intro">
          <h2>Building effective agents</h2>
        </a>
        <time datetime="2024-12-19">December 19, 2024</time>
        <p class="summary">Lessons from production agent deployments.</p>
      </article>
      <article class="post-card">
        <a href="/engineering/plain-visible-date"><h2>Plain visible date</h2></a>
        <span>July 31, 2026</span>
      </article>
      <a class="featured-card" href="https://www.anthropic.com/engineering/multi-agent-research-system">
        <h2>How we built our multi-agent research system</h2>
        <p>Inside the architecture of our Research feature.</p>
      </a>
      <article>
        <a href="/news/company-update"><h2>Company update</h2></a>
        <time datetime="2026-07-31"></time>
      </article>
      <article>
        <a href="https://evil.example/engineering/fake"><h2>Fake official post</h2></a>
      </article>
      <nav><a href="/engineering">Engineering home</a></nav>
    </main>
    """

    articles = parse_official_listing(
        html,
        base_url="https://www.anthropic.com/engineering",
        trusted_hosts=TRUSTED_HOSTS,
        allowed_path_prefixes=ALLOWED_PATH_PREFIXES,
    )

    assert articles == (
        OfficialArticle(
            title="Building effective agents",
            url="https://www.anthropic.com/engineering/building-effective-agents",
            published_date="2024-12-19",
            summary="Lessons from production agent deployments.",
            featured=False,
        ),
        OfficialArticle(
            title="Plain visible date",
            url="https://www.anthropic.com/engineering/plain-visible-date",
            published_date=None,
            summary="",
            featured=False,
        ),
        OfficialArticle(
            title="How we built our multi-agent research system",
            url="https://www.anthropic.com/engineering/multi-agent-research-system",
            published_date=None,
            summary="Inside the architecture of our Research feature.",
            featured=True,
        ),
    )
    assert articles[2].needs_metadata_fetch is True


@pytest.mark.parametrize(
    ("raw_value", "expected"),
    [
        ("2026-08-01", "2026-08-01"),
        ("2026-08-01T09:30:00Z", "2026-08-01"),
        ("August 1, 2026", "2026-08-01"),
        ("Aug 1st, 2026", "2026-08-01"),
        ("Published: 1 August 2026", "2026-08-01"),
        ("发布于 2026年8月1日", "2026-08-01"),
        ("2026-02-30", None),
        ("not a date", None),
        (None, None),
    ],
)
def test_normalize_published_date(raw_value, expected):
    assert normalize_published_date(raw_value) == expected


def test_parse_official_article_extracts_heading_description_and_published_date():
    html = """
    <html>
      <head>
        <meta name="description" content="How Anthropic's engineering team built the system.">
        <meta property="article:published_time" content="2026-07-30T16:00:00Z">
        <meta property="og:title" content="Fallback title">
      </head>
      <body>
        <h1>Building a safer research system</h1>
        <div class="byline">Published July 30, 2026</div>
      </body>
    </html>
    """

    metadata = parse_official_article(html)

    assert metadata.title == "Building a safer research system"
    assert metadata.description == "How Anthropic's engineering team built the system."
    assert metadata.published_date == "2026-07-30"


def test_parse_official_article_uses_visible_published_date_when_metadata_is_absent():
    metadata = parse_official_article(
        """
        <main>
          <h1>Scaling long-running agents</h1>
          <p class="published">Published: 2026年7月29日</p>
        </main>
        """
    )

    assert metadata.published_date == "2026-07-29"


def test_parse_official_article_does_not_treat_title_event_date_as_publish_date():
    metadata = parse_official_article(
        """
        <main>
          <h1>Lessons from the May 25, 2026 incident</h1>
          <p>Published April 1, 2025</p>
          <p>The incident was reviewed again on June 2, 2026.</p>
        </main>
        """
    )

    assert metadata.published_date == "2025-04-01"


def test_parse_official_article_rejects_unlabelled_body_dates():
    metadata = parse_official_article(
        """
        <main>
          <h1>Lessons from the May 25, 2026 incident</h1>
          <p>The review began on April 1, 2025 and ended on June 2, 2026.</p>
        </main>
        """
    )

    assert metadata.published_date is None


@pytest.mark.parametrize(
    "url",
    [
        "http://www.anthropic.com/engineering/post",
        "https://anthropic.com/engineering/post",
        "https://www.anthropic.com.evil.example/engineering/post",
        "https://user@www.anthropic.com/engineering/post",
        "https://user:secret@www.anthropic.com/engineering/post",
        "https://www.anthropic.com:444/engineering/post",
        "https://www.anthropic.com/news/post",
        "https://www.anthropic.com/engineering-archive/post",
        "https://www.anthropic.com/engineering/../news/post",
        "https://www.anthropic.com/engineering/%252e%252e/news/post",
    ],
)
def test_canonicalize_official_url_rejects_unsafe_or_out_of_scope_urls(url):
    assert (
        canonicalize_official_url(
            url,
            base_url="https://www.anthropic.com/engineering",
            trusted_hosts=TRUSTED_HOSTS,
            allowed_path_prefixes=ALLOWED_PATH_PREFIXES,
        )
        is None
    )


def test_canonicalize_official_url_accepts_https_default_port_and_exact_path_boundary():
    assert (
        canonicalize_official_url(
            "https://www.anthropic.com:443/engineering/post?ref=homepage#details",
            base_url="https://www.anthropic.com/engineering",
            trusted_hosts=TRUSTED_HOSTS,
            allowed_path_prefixes=ALLOWED_PATH_PREFIXES,
        )
        == "https://www.anthropic.com/engineering/post"
    )


def test_select_latest_article_excludes_future_posts_relative_to_as_of_date():
    articles = (
        OfficialArticle("Older", "https://www.anthropic.com/engineering/older", "2026-07-29"),
        OfficialArticle("Latest valid", "https://www.anthropic.com/engineering/latest", "2026-08-01"),
        OfficialArticle("Future", "https://www.anthropic.com/engineering/future", "2026-08-02"),
        OfficialArticle("Featured pending", "https://www.anthropic.com/engineering/featured", None, featured=True),
    )

    selected = select_latest_article(articles, as_of_date=date(2026, 8, 1))

    assert selected is articles[1]


def test_select_latest_article_respects_calendar_year_lower_bound():
    articles = (
        OfficialArticle("Previous year", "https://www.anthropic.com/engineering/older", "2023-12-31"),
        OfficialArticle("Future year", "https://www.anthropic.com/engineering/newer", "2025-01-01"),
    )

    selected = select_latest_article(
        articles,
        as_of_date="2024-12-31",
        not_before_date="2024-01-01",
    )

    assert selected is None


def test_official_article_dataclass_is_immutable():
    article = OfficialArticle("Title", "https://www.anthropic.com/engineering/title", "2026-08-01")

    with pytest.raises(FrozenInstanceError):
        article.title = "Changed"

    metadata = parse_official_article("<h1>Title</h1>")
    with pytest.raises(FrozenInstanceError):
        metadata.title = "Changed"
