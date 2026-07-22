---
name: ci_health
verify_cmd: bash -n .agent-harness/scripts/loop.sh && ./.agent-harness/scripts/doctor.sh && ./.agent-harness/scripts/verify.sh --list
max_minutes: 10
---

请围绕以下任务执行一次：

## 任务描述

对仓库内 CI 与质量门禁配置执行一次只读健康巡检。检查 `.github/workflows/`、`.agent-harness/scripts/` 及各模块实际存在的构建配置，确认工作流引用的脚本、模块目录和验证命令仍然存在，并使用以下真实命令收集证据：

```bash
git status --short
git diff --check
bash -n .agent-harness/scripts/*.sh
./.agent-harness/scripts/doctor.sh
./.agent-harness/scripts/verify.sh --list
```

只允许读取文件和执行不会修改源码、依赖、配置或外部系统状态的检查命令。不得运行 `loop.sh`、`run_goal.sh`、`gate.sh`、模块测试或构建，也不得安装依赖、访问生产服务或触发远程 CI。

## 预期产出

在运行日志中输出简洁的中文巡检报告，列出实际执行的命令及退出状态，并按“正常、警告、阻塞”归类发现的问题。每个问题应包含对应文件路径或命令证据以及人工跟进建议；没有充分证据时不得推断 CI 已通过。

## 允许修改的范围

- 全程只读，不允许修改任何仓库文件。
- 不允许创建提交、分支、标签、工作树或拉取请求。

## 预算与停止条件

- 最长执行时间由 front matter 限制为 10 分钟。
- 最多执行 10 条本地只读检查命令。
- 任一命令超过 2 分钟、要求凭据、需要联网、会安装依赖或可能改变环境时立即跳过，并在报告中说明原因。
- 发现阻塞问题后继续完成其余安全检查，但不尝试自动修复。

## 禁止事项

- 不要调用 Claude 或其他模型命令。
- 不要修改生产配置、密钥、数据库迁移、公共 API、业务代码或 CI 配置。
- 不要执行具有写入、副作用或远程触发能力的命令。
- 不要在缺少验证证据时宣称 CI 健康或任务完成。
- 失败时输出人工跟进建议，不要重试或扩大检查范围。
