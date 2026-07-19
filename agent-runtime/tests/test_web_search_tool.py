from app.tools_builtin.web_search_tool import WebSearchTool


def test_web_search_tool_parses_bocha_web_results():
    payload = {
        "data": {
            "webPages": {
                "value": [
                    {
                        "name": "Agent 面试题",
                        "url": "https://example.com/agent",
                        "summary": "RAG、Tool Calling、Agent Loop 高频题。",
                        "datePublished": "2026-05-01",
                        "siteName": "Example",
                    }
                ]
            }
        }
    }
    tool = WebSearchTool()

    rows = tool._parse_bocha_web(payload, 3)

    assert rows == [
        {
            "title": "Agent 面试题",
            "url": "https://example.com/agent",
            "snippet": "RAG、Tool Calling、Agent Loop 高频题。",
            "published_date": "2026-05-01",
            "site_name": "Example",
        }
    ]


def test_web_search_tool_parses_duckduckgo_html_results():
    html = """
    <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fjob">Agent 面试指南</a>
    <a class="result__snippet">围绕 RAG、Tool Calling 和 Agent 工程准备面试。</a>
    """
    tool = WebSearchTool()

    rows = tool._parse_results(html, 3)

    assert rows == [
        {
            "title": "Agent 面试指南",
            "url": "https://example.com/job",
            "snippet": "围绕 RAG、Tool Calling 和 Agent 工程准备面试。",
        }
    ]
