# agent-tool

`agent-tool` 是公共工具库与工具市场模块，负责具体工具实现、工具元数据和工具执行入口。`agent-runtime` 负责工具选择、权限和编排，需要执行具体工具时调用本服务。

## 接口

- `GET /health`
- `GET /v1/tools`
- `POST /v1/tools/{name}/execute`

当前内置工具包括记忆搜索、沙箱执行、Trace 摘要和 Boss 直聘工具 `boss_browser`。

## Boss 直聘工具

Boss 能力位于 [app/tools/boss_browser](app/tools/boss_browser)，对外使用稳定的 `boss_browser` 工具名，供 Runtime、后端和前端调用；底层使用 [jackwener/boss-cli](https://github.com/jackwener/boss-cli) 的本地 Cookie 与 HTTP API 能力。

推荐登录流程：

1. 默认使用二维码登录，或复用后端 PostgreSQL `auth_state` 中已保存并随请求注入内存的凭证。
2. `agent-tool` 的状态检查、搜索失败回退和 `refresh_auth` 默认都不读取本机浏览器 Cookie，避免 macOS 弹出 Chrome Safe Storage 钥匙串授权框。
3. 只有明确接受系统钥匙串授权时，才设置 `BOSS_CLI_AUTO_IMPORT_BROWSER_COOKIES=true`；如需指定来源，再设置 `BOSS_CLI_COOKIE_SOURCE=chrome`、`firefox`、`edge`、`brave` 或 `arc`。

`boss_browser` 的固定操作集合为 `status`、`refresh_auth`、`qr_start`、`qr_status`、`qr_cancel`、`search`、`favorite_list`、`detail`、`profile` 和 `rate`。Registry 描述、执行白名单和业务文档必须同步维护。

关键环境变量：

- `BOSS_CLI_RATE_REDIS_URL` / `AGENT_TOOL_REDIS_URL`：可选显式指定限速缓存 Redis；未配置时复用 `SPRING_REDIS_*`。
- `BOSS_CLI_AUTO_IMPORT_BROWSER_COOKIES`：默认 `false`；关闭时所有路径都禁止读取本机浏览器 Cookie。
- `BOSS_CLI_COOKIE_SOURCE`：浏览器 Cookie 导入显式开启后，可选指定来源浏览器。
- `BOSS_CLI_TIMEOUT_SECONDS`：单次 Boss 请求超时，默认 `20` 秒。
- `BOSS_CLI_MAX_RETRIES`：单次工具调用的最大尝试次数，默认 `2`，允许一次瞬时网络故障恢复。
- `BOSS_CLI_STATUS_VERIFY`：默认 `false`，避免频繁状态检查触发真实 Boss 请求。
- `BOSS_CLI_MAX_SEARCH_PAGE`：默认 `5`，控制搜索最大页数。
- `BOSS_CLI_SEARCH_PER_HOUR`：默认 `15`，控制每小时搜索请求数。
- `BOSS_CLI_DETAIL_PER_HOUR`、`BOSS_CLI_DELAY_MIN_MS`、`BOSS_CLI_DELAY_MAX_MS`：保留为工具层限速配置；默认动作前等待为 1.5-4 秒。

非真实访问的限速状态验证会返回 `*_used_*` 与 `*_limit_*` 字段，可直接确认当前配置是否生效：

```bash
curl -X POST http://localhost:8040/v1/tools/boss_browser/execute \
  -H 'Content-Type: application/json' \
  -d '{"arguments":{"operation":"rate","payload":{}},"confirm":true}'
```

二维码登录后若缺少 `__zp_stoken__` 这类由网页 JavaScript 生成的关键 Cookie，工具会在配置允许时使用一次性 headless Chromium 补齐；该流程只使用项目保存的 Cookie，不读取 Chrome Safe Storage。若补齐失败，将返回登录态不完整，不会自动访问本机浏览器钥匙串。

## 启动与验证

```bash
uv sync --extra dev
uv run python server.py
uv run python -m pytest
```
