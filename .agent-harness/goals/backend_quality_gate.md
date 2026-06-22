---
max_turns: 3
max_minutes: 20
verify_cmd: ./.agent-harness/scripts/gate.sh agent-backend --quick
---

# Goal: 后端开发质量门禁

## 背景
用于后续任何 `agent-backend` 相关开发任务的默认验收目标。任务完成前必须同时通过后端测试和行为评估，不能只凭编译通过或人工观察判断完成。

## 完成条件
1. `./.agent-harness/scripts/gate.sh agent-backend --quick` 退出码为 0。
2. Maven 后端测试通过，至少包含健康检查、聊天接口冒烟和核心 Trace 契约测试。
3. `agent-eval` 评分器测试通过，核心 Trace 必需节点契约通过。
4. 若失败，必须输出失败命令、失败摘要、修复建议和下一步动作，不能声明完成。

## 允许修改的范围
- `agent-backend/**`
- `.agent-harness/**`
- 与后端测试/评估直接相关的文档

## 禁止事项
- 不允许提交 `.env`、真实密钥、生产地址或本地临时产物。
- 不允许为了通过测试删除核心断言或跳过评估。
- 不允许依赖本地正在运行的生产服务完成单元测试。

## 软着陆报告
如果质量门禁无法通过，请说明：已运行的命令、失败日志位置、最小复现命令、已尝试修复、建议人工介入点。
