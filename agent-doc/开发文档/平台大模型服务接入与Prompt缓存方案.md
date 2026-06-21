# 平台大模型服务接入与 Prompt 缓存方案

本次调整将平台设置中的大模型服务收敛为 3 类固定来源：ChatGPT Pro、Claude Max 和 DeepSeek API。用户可以配置多个连接，但新增连接只能从这 3 类来源中选择，并且只能把其中一个连接设置为默认来源。平台设置页不再要求用户选择具体模型，模型列表和最新模型选择由 Runtime 在执行时处理，避免配置页固化模型名导致模型更新后不可用。

ChatGPT Pro 与 Claude Max 按官网会员 Token 来源处理，配置项中使用 `official_token` 类型凭据，不再把它们当作 API Key 连接。DeepSeek API 保留 API Key 和 API Endpoint 配置。Java 后端保存本地设置后，在调用 Runtime 前读取默认连接，把来源、授权类型、凭据、缓存策略、超时和预算参数放入 `metadata.llm_service`。如果默认连接没有填写凭据，则不覆盖 Runtime 环境变量配置。

Runtime 侧的模型客户端支持 `chatgpt_pro`、`claude_max` 和 `deepseek_api` 三类来源。客户端从请求元数据构建临时 LLM Client，并同步注入任务理解与 Planner，保证当前请求使用平台默认来源。模型名不从平台设置读取；执行前会尝试拉取来源可用模型列表并取最新可用项，拉取失败时使用来源级兜底最新模型名。Prompt Cache 保持稳定前缀策略，Claude Max 来源会在 Anthropic 消息结构上添加 `cache_control` 标记，DeepSeek API 和 ChatGPT Pro 来源保持稳定 system/tool 前缀并继续使用 Runtime 请求级缓存。

本方案当前完成配置和运行时传递链路。官网会员 Token 的实际后端协议仍依赖对应来源的可用网关或官方 Web 会话接口，后续如果需要完整生产化，应补充 Token 获取、刷新、失效检测和模型列表拉取接口的端到端联调用例。
