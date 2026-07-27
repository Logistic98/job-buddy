---
max_turns: 24
max_minutes: 90
max_diff_lines: 3000
verify_cmd: ./.agent-harness/scripts/gate.sh all --quick
---

# Goal: 根据自然语言从现有题库智能组卷并进入练习

## 背景

练习中心已有手动选题和规则随机组卷，但用户还需要用自然语言描述岗位方向、知识范围、难度、题型、数量、时长和练习模式，由 AI 从现有题库选择相应题目组成试卷。题目和练习记录仍由 Backend 管理，Runtime 只负责理解要求和返回受约束的选题结果。

## 开发文档

- 已阅读的开发文档：`agent-doc/架构设计/系统架构与核心链路.md`
- 本次需要更新的开发文档：`agent-doc/业务功能/面试题库与模拟练习.md`
- 记录要点：Backend 业务事务边界、Runtime 只读选题、候选题最小披露、题号白名单和失败关闭。

## 目标模块

- `agent-runtime`
- `agent-backend`
- `agent-frontend`

## 完成条件

1. Runtime 单元测试证明自然语言要求会生成结构化试卷方案，未知题号、重复题号和非法结构会被拒绝。
2. Backend 测试证明 `/api/interview/practices/smart` 只允许从本次启用题目候选集中选题，并以 `mode=smart` 保存可回放策略。
3. Backend 测试证明空题库、过短要求、Runtime 失败和模型返回未知题号时不会创建练习。
4. 前端测试证明练习中心顶部只保留题库与练习台，练习台以记录为首页并通过单一“创建练习”按钮打开组卷弹窗，弹窗内可切换智能组卷和规则组卷；提交期间有加载态，失败时保留用户输入，成功后关闭弹窗并直接进入新练习。
5. `./.agent-harness/scripts/gate.sh all --quick` 退出码为 0。
6. 本地启动服务并在浏览器完成“输入自然语言要求、智能组卷、进入练习台”的真实用户路径验证。

## 允许修改的范围

- `agent-runtime/app/tools_builtin/`
- `agent-runtime/config/prompts/artifacts/`
- `agent-runtime/tests/`
- `agent-backend/src/main/java/com/jobbuddy/backend/modules/interview/`
- `agent-backend/src/test/java/com/jobbuddy/backend/`
- `agent-frontend/src/components/`
- `agent-frontend/src/api/interview.js`
- `agent-frontend/src/styles/modules/`
- `agent-frontend/tests/`
- `agent-doc/业务功能/面试题库与模拟练习.md`

## 禁止事项

- 不允许 Runtime 或前端直接写入题目、试卷和作答数据。
- 不允许把题目答案、完整测试用例或用户历史作答发送给选题模型。
- 不允许接受候选集之外的题号，也不允许模型失败后静默回退为随机组卷。
- 不允许改变现有手动选题、随机组卷、作答、计时和提交契约。
