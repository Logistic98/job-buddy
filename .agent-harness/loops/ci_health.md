---
name: ci_health
verify_cmd: ./.agent-harness/scripts/gate.sh all --quick
max_minutes: 20
# model: <optional; omit to use CLI default or CLAUDE_MODEL>
---

请对 job-buddy 仓库做一次轻量 CI 健康巡检：

1. 运行 `./.agent-harness/scripts/gate.sh all --quick`，记录测试和评估哪些通过、哪些失败、哪些因为尚未落地构建文件而跳过。
2. 对失败的模块，定位最直接的报错信息（不超过 20 行）并给出修复建议。
3. 检查已落地服务模块的 `Dockerfile` 是否存在且非空：`agent-backend/`、`agent-runtime/`、`agent-intent/`、`agent-sandbox/`。尚未落地的模块只报告缺失，不自动创建。
4. 检查各模块根目录是否存在非空 `README.md`。
5. 特别关注技术栈一致性：`agent-frontend` 应按 Vue 3 + Vite 描述，`agent-backend` 应按 Spring Boot 描述，`agent-intent` 应按意图识别与路由描述。

只输出一份纯中文报告，写明：通过的模块、失败的模块与原因、被跳过的模块、缺失的文档或基础设施、建议人工介入的事项。

本次任务为只读巡检，禁止修改任何源代码。
