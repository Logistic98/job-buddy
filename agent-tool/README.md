# agent-tool

`agent-tool` 是独立工具执行服务，负责工具实现、元数据和统一执行入口。`agent-runtime` 负责候选选择、权限与编排；代码中的 Registry 是本服务目录，不代表已接入外部工具市场。

## 接口

| 方法与路径                      | 用途                   |
| ------------------------------- | ---------------------- |
| `GET /health`                   | 健康检查               |
| `GET /v1/tools`                 | 返回工具定义           |
| `POST /v1/tools/{name}/execute` | 按统一结果信封执行工具 |

当前内置工具包括记忆搜索、沙箱执行、Trace 摘要和 Boss 直聘工具 `boss_browser`。

## Boss 直聘工具

Boss 能力位于 [app/tools/boss_browser](app/tools/boss_browser)，对外使用稳定的 `boss_browser` 工具名，由 Runtime 统一调用；Backend 通过 Runtime 代理发起确定性业务请求，前端只访问 Backend。底层复用 [jackwener/boss-cli](https://github.com/jackwener/boss-cli) 的 Cookie 与 HTTP API 能力。

推荐登录流程：

1. 默认使用二维码登录，或复用后端 PostgreSQL `auth_state` 中已保存并随请求注入内存的凭证。
2. `agent-tool` 的状态检查、搜索失败回退和 `refresh_auth` 默认都不读取本机浏览器 Cookie，避免 macOS 弹出 Chrome Safe Storage 钥匙串授权框。
3. 只有明确接受系统钥匙串授权时，才设置 `BOSS_CLI_AUTO_IMPORT_BROWSER_COOKIES=true`；如需指定来源，再设置 `BOSS_CLI_COOKIE_SOURCE=chrome`、`firefox`、`edge`、`brave` 或 `arc`。

`boss_browser` 的固定操作集合为 `status`、`refresh_auth`、`qr_start`、`qr_status`、`qr_cancel`、`search`、`favorite_list`、`detail`、`profile` 和 `rate`。Registry 描述、执行白名单和业务文档必须同步维护。

| 配置                                                              | 作用                                                   |
| ----------------------------------------------------------------- | ------------------------------------------------------ |
| `BOSS_CLI_RATE_REDIS_URL` / `AGENT_TOOL_REDIS_URL`                | 指定限速 Redis；未配置时复用 `SPRING_REDIS_*`          |
| `BOSS_CLI_AUTO_IMPORT_BROWSER_COOKIES` / `BOSS_CLI_COOKIE_SOURCE` | 显式开启并选择浏览器 Cookie 来源；默认关闭             |
| `BOSS_CLI_TIMEOUT_SECONDS` / `BOSS_CLI_MAX_RETRIES`               | 单请求超时与最大重试次数，默认 20 秒、2 次             |
| `BOSS_CLI_STATUS_VERIFY`                                          | 是否让状态检查访问 Boss；默认关闭                      |
| `BOSS_CLI_MAX_SEARCH_PAGE`                                        | 最大搜索页码，默认 5                                   |
| `BOSS_CLI_SEARCH_PER_HOUR` / `BOSS_CLI_DETAIL_PER_HOUR`           | 搜索与详情小时配额                                     |
| `BOSS_CLI_DELAY_MIN_MS` / `BOSS_CLI_DELAY_MAX_MS`                 | 动作前抖动；根环境模板为 1.5–4 秒，模块回退为 0.8–2 秒 |

非真实访问的限速状态验证会返回 `*_used_*` 与 `*_limit_*` 字段，可直接确认当前配置是否生效：

```bash
curl -X POST http://localhost:8040/v1/tools/boss_browser/execute \
  -H 'X-Internal-Service-Token: <internal-token>' \
  -H 'Content-Type: application/json' \
  -d '{"arguments":{"operation":"rate","payload":{}},"confirm":true}'
```

未配置 `AGENT_INTERNAL_SERVICE_TOKEN` 的本地开发环境可以省略该请求头；production/prod 环境必须配置并传递。

二维码登录后若缺少 `__zp_stoken__` 这类由网页 JavaScript 生成的关键 Cookie，工具会在配置允许时使用一次性 headless Chromium 补齐；该流程只使用项目保存的 Cookie，不读取 Chrome Safe Storage。若补齐失败，将返回登录态不完整，不会自动访问本机浏览器钥匙串。

完整登录、检索和风控契约见[Boss 直聘集成与岗位检索](../agent-doc/业务功能/Boss直聘集成与岗位检索.md)。

## 启动与验证

```bash
uv sync --extra dev
uv run python server.py
uv run python -m pytest
```
