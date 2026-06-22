---
name: <loop_name>
verify_cmd: ./.agent-harness/scripts/verify.sh --quick
max_minutes: 15
# model: <optional claude model name; omit to use CLI default or CLAUDE_MODEL>
---

请围绕以下任务执行一次：

## 任务描述
<例如：扫描 agent-runtime 的依赖更新，列出可升级项及风险。>

## 预期产出
<例如：在运行日志中输出依赖清单、风险等级和建议，不直接修改代码。>

## 允许修改的范围
- 默认只读分析。
- 如需修改，请仅限：<路径>。

## 禁止事项
- 不要直接修改生产配置、密钥、数据库迁移或公共 API。
- 不要在缺少验证证据时宣称完成。
- 失败时输出人工跟进建议，不要无限重试。
