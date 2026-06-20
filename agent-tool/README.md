# agent-tool

`agent-tool` 是公共工具库与工具市场模块，负责具体工具实现、工具元数据和工具执行入口。`agent-runtime` 负责工具选择、权限和编排，需要执行具体工具时调用本服务。

## 接口

- `GET /health`
- `GET /v1/tools`
- `POST /v1/tools/{name}/execute`

当前内置工具包括记忆搜索、沙箱执行、Trace 摘要和 Boss 直聘工具 `boss_browser`。

## Boss 直聘工具

Boss 能力位于 [app/tools/boss_browser](app/tools/boss_browser)，对外仍保持 `boss_browser` 工具名以兼容 Runtime、后端和前端；底层使用 [jackwener/boss-cli](https://github.com/jackwener/boss-cli) 的本地 Cookie 与 HTTP API 能力。

推荐登录流程：

1. 先在本机常用浏览器中正常登录 Boss 直聘网页端。
2. 启动 `agent-tool`，它会通过 `kabi-boss-cli` 从本机浏览器导入 Boss Cookie，并保存到 `BOSS_CLI_HOME/credential.json`。
3. 如果需要指定浏览器来源，可设置 `BOSS_CLI_COOKIE_SOURCE=chrome`、`firefox`、`edge`、`brave` 或 `arc`。

关键环境变量：

- `BOSS_CLI_HOME`：boss-cli 凭证目录，默认使用仓库根目录 `.run/boss-cli-home`。
- `BOSS_CLI_COOKIE_SOURCE`：可选，指定 Cookie 来源浏览器。
- `BOSS_CLI_STATUS_VERIFY`：默认 `false`，避免频繁状态检查触发真实 Boss 请求。
- `BOSS_CLI_MAX_SEARCH_PAGE`：默认 `1`，控制搜索最大页数。
- `BOSS_BROWSER_SEARCH_PER_HOUR`、`BOSS_BROWSER_DETAIL_PER_HOUR`、`BOSS_BROWSER_DELAY_MIN_MS`、`BOSS_BROWSER_DELAY_MAX_MS`：保留为工具层限速配置。

非真实访问的限速状态验证：

```bash
curl -X POST http://localhost:8040/v1/tools/boss_browser/execute \
  -H 'Content-Type: application/json' \
  -d '{"arguments":{"operation":"rate","payload":{}},"confirm":true}'
```

二维码登录仍保留为兜底能力，但 HTTP 二维码登录可能无法获得 `__zp_stoken__` 这类由网页 JavaScript 生成的关键 Cookie。若二维码登录后仍提示登录态不完整，请先在常用浏览器登录 Boss 直聘，再让工具重新导入浏览器 Cookie。

## 启动与验证

```bash
uv sync --extra dev
uv run python server.py
uv run python -m pytest
```
