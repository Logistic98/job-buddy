# Boss 直聘取数引擎迁移至 boss-cli 方案

## 为什么做

Boss 直聘相关能力需要在本地求职工作台中保持低频、可控、可降级的取数链路。当前方案选择以 `agent-tool` 承载 Boss 工具实现，复用 `kabi-boss-cli` 的本地浏览器 Cookie 提取、Boss HTTP API Client、请求抖动、限速退避、Cookie 合并和结构化错误能力，避免在 Java 后端或 Runtime 内部直接耦合 Boss 上游细节。

## 方案是什么

对外工具名保持 `boss_browser`，以兼容 Runtime、Java 后端和前端调用链路；工具内部由 `agent-tool` 的 boss-cli 适配层完成登录态检查、岗位搜索、岗位详情懒加载和在线简历读取。推荐登录方式是用户先在本机常用浏览器中登录 Boss 直聘网页端，再由 boss-cli 导入 zhipin.com Cookie，并保存到仓库根目录 `.run/boss-cli-home/credential.json`。Java 后端和 Runtime 不读取、不保存真实 Cookie，只通过工具响应判断非敏感登录态。

## 具体怎么做

`agent-tool/app/tools/boss_browser/core/boss_cli_engine.py` 负责配置 boss-cli 凭证目录、读取本地凭证、按需从浏览器 Cookie 导入登录态、串行调用 `BossClient.search_jobs`、`get_job_detail`、`get_resume_baseinfo`、`get_resume_expect` 和 `get_resume_status`，并将 boss-cli 异常归类为登录态失效、上游限速、风控信号或普通错误。`status` 默认只做本地凭证检查，不主动请求 Boss；如果需要强校验，可通过 `BOSS_CLI_STATUS_VERIFY=true` 显式开启。

`agent-tool/app/tools/boss_browser/core/service.py` 保留 RateLimiter、风控冷却和连续失败硬停。boss-cli 本地依赖不可用属于基础设施失败，必须在访问配额前抛出，不消耗限速窗口；Boss 上游限速映射到 4003；验证码、安全验证、账号异常等信号映射到 4002，并触发全局冷却。

需要登录（4001）不是风险信号，不计入连续失败硬停。`service.py` 在 `search`、`detail`、`profile` 的需要登录分支只抛 `AuthRequiredError`，不记录为失败；`_acquire` 在命中硬停时先做本地登录态判定，未登录则转为 `auth_required` 并引导扫码或浏览器 Cookie 导入，确有有效登录仍反复失败时才维持硬停并交由人工排查。`status` 一旦确认登录态有效即清零硬停计数，保证登录恢复后可继续使用。

`agent-runtime/app/tools_builtin/boss_browser_tool.py` 只代理到 `agent-tool`。`agent-backend` 通过 Runtime 工具入口调用 Boss 能力，共享凭证目录统一为 `BOSS_CLI_HOME` / `.run/boss-cli-home`，业务库只保存非敏感登录状态与会话状态。

### 二维码登录 Cookie 补齐

二维码登录路径中，`scan → confirm → dispatch` 可能只拿到部分 Cookie，而 `__zp_stoken__` 这类 Web 安全 Cookie 需要浏览器加载页面后由前端脚本生成。为保证扫码登录后的搜索登录态可用，`boss_cli_engine.py` 在 dispatch 后检查必要 Cookie；如果缺失，则调用 `_complete_qr_credential` / `_run_headless_cookie_completion` 临时启动 headless Chromium，访问 Boss Web 页面补齐 Web Cookie 后回收完整凭证。

该能力由 `BossCliConfig.headless_cookie_completion`（环境变量 `BOSS_CLI_HEADLESS_COOKIE`，默认开启）与 `headless_cookie_timeout_ms`（环境变量 `BOSS_CLI_HEADLESS_COOKIE_TIMEOUT_MS`，默认 8000）控制。补齐失败时保留原凭证，由上层落到 `auth_required` 终态并提示用户改用浏览器 Cookie 导入；前端据此停止轮询，避免对同一张已确认二维码无限轮询触发风控。

### 搜索登录态失效后的令牌静默重生

`__zp_stoken__` 是 Boss 网页反爬动态下发的临时令牌，多次搜索（典型如“换一批”跨页抓取）时容易被上游判为失效，而 `wt2`、`zp_at` 等持久登录身份 Cookie 仍然有效，说明用户并未退出登录。`boss_cli_engine.py` 的 `_refresh_after_auth_failure` 在搜索/详情登录态失效时，先经 `_refresh_stoken_from_persisted` 复用磁盘上已保存的登录身份 Cookie，调用 `_run_headless_cookie_completion` 让前端 JS 重新下发 `__zp_stoken__` 后回收落盘并重试当前请求；只有该路径也失败时才回退到本机浏览器 Cookie 导入，最终仍不可用才落到 `auth_required` 引导扫码。判定持久登录的身份 Cookie 集合由 `LOGIN_IDENTITY_COOKIES`（`wt2` + `zp_at`）定义。该路径复用既有 headless 补齐能力，受同一组 `BOSS_CLI_HEADLESS_COOKIE` 开关与 60 秒刷新节流约束，避免“用户明明已登录、换一批却又被要求重新扫码”，同时不放宽串行、低频、风控立即停手的账号保护约束。区别于扫码登录后的一次性补齐，交互翻页热路径上的令牌重生走 `_run_headless_cookie_completion(..., lean=True)` 轻量分支：只访问一次 Boss 首页、用 `domcontentloaded` 而非 `networkidle`、收紧单页超时（`_LEAN_COOKIE_VISIT_TIMEOUT_MS`，4000ms）与加载后静置时间（`_LEAN_COOKIE_SETTLE_MS`，600ms），绝不再做多页兜底，避免冷启动后再多页等待把“换一批”拖到几十秒；扫码登录补齐仍走 `lean=False` 的多页兜底以保证首次拿到可用搜索登录态。

## 涉及模块和接口

涉及 `agent-tool` 的 Boss 工具实现、配置、依赖和单元测试，`agent-runtime` 的工具描述，`agent-backend` 的 Boss 登录态代理和启动脚本，前端二维码登录弹窗和 Boss API 封装。对外接口仍是 `POST /v1/tools/boss_browser/execute` 与 `POST /v1/runtime/tools/boss_browser/invoke`，操作类型保留 `status`、`qr_start`、`qr_status`、`search`、`detail`、`profile` 和 `rate`。

## 风险与约束

Boss 取数仍有账号风控风险。本方案不能绕过 Boss 风控，只能通过串行、低频、默认只取第一页、详情懒加载、命中风控立即停手等方式降低风险。不得要求用户在聊天中粘贴 Cookie，也不得在日志或业务库中输出真实 Cookie。

二维码登录的 headless Chromium 只用于扫码确认后补齐 Web 安全 Cookie，是一次性、低频、串行的内部步骤，不对外提供浏览器入口。最稳妥的登录方式仍是用户先在常用浏览器登录 Boss 网页端，再由 boss-cli 导入完整 Cookie。

## 如何验证

自动化测试不得触达真实 Boss。需要覆盖：boss-cli 凭证目录重定向、登录态降级、搜索最大页数本地拦截、城市解析、筛选项映射、详情 securityId/lid 解析、boss-cli 本地依赖失败不消耗限速配额、工具入口响应信封和 Runtime 代理契约。真实联调仅在人工确认账号状态正常、低频条件下执行；优先验证 `rate` 和 `status`，再最小化执行一次搜索或详情懒加载。出现验证码、安全验证、访问异常、环境异常、操作过于频繁或账号限制时立即停手。
