---
max_turns: 3
max_minutes: 15
verify_cmd: ./.agent-harness/scripts/doctor.sh && ./.agent-harness/scripts/verify.sh --list && ./.agent-harness/scripts/verify.sh agent-runtime --quick
# model: <optional; omit to use CLI default or CLAUDE_MODEL>
---

# Goal: 验证 job-buddy 自动化 harness 的基本可用性

## 背景
这是一个低风险 smoke goal，用于在正式无人值守任务前验证 harness、项目上下文、基础依赖与已落地模块的快速验证链路是否可用。当前仓库包含 Spring Boot 后端、Vue 前端、意图识别、Python Runtime 与 Sandbox 等模块，smoke goal 不要求所有规划模块都已有构建文件。

## 完成条件
1. `./.agent-harness/scripts/doctor.sh` 退出码为 0。
2. `./.agent-harness/scripts/verify.sh --list` 能列出 `agent-backend`、`agent-frontend`、`agent-intent`、`agent-runtime` 等模块。
3. `./.agent-harness/scripts/verify.sh agent-runtime --quick` 退出码为 0。
4. 若验证失败，输出最直接失败原因和下一步人工处理建议。

## 允许修改的范围
- 默认不修改源代码。
- 仅当发现 harness 脚本本身存在明显问题时，可修改 `.agent-harness/scripts/` 或 `.agent-harness/README.md`。

## 禁止事项
- 不允许修改业务模块代码。
- 不允许安装全局依赖。
- 不允许提交密钥或本地机器路径到业务配置。

## 软着陆报告
若无法通过，请输出：已检查项、失败命令、失败摘要、建议人工补齐的依赖或配置。
