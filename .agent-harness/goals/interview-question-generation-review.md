---
max_turns: 24
max_minutes: 90
max_diff_lines: 3000
verify_cmd: ./.agent-harness/scripts/gate.sh all --quick
---

# Goal: 算法与问答候选题由 Runtime 智能生成并经人工审核后入库

## 背景

练习中心不能依赖 Java 内置题目目录，也不能通过未公开接口或网页抓取导入 LeetCode 内容。生成阶段由 Agent Runtime 只读工具调用模型，Java Backend 只负责协议校验和业务编排，前端负责候选编辑、逐题确认和最终导入。算法题与问答题必须使用符合各自维护语义的表单、提示和审核字段。

## 开发文档

- 已阅读的开发文档：`agent-doc/架构设计/系统架构与核心链路.md`
- 本次需要更新的开发文档：`agent-doc/业务能力/面试题库与模拟练习.md`
- 记录要点：LeetCode 来源边界、Runtime 结构化生成、生成零持久化、人工审核和事务导入。

## 目标模块

- `agent-runtime`
- `agent-backend`
- `agent-frontend`

## 完成条件

1. Runtime 单元测试证明模型输出会被结构化校验，非法来源链接和不一致测试参数会被拒绝。
2. Backend 测试证明 `/questions/generate` 只返回无 `questionId` 的候选题且不调用 Repository 保存。
3. 前端测试证明算法题与问答题使用不同表单和默认提示，问答题型切换会显示对应选项或评分字段。
4. 前端测试证明生成成功后进入审核页，未逐题确认时不能调用 `/questions/import`。
5. `./.agent-harness/scripts/gate.sh all --quick` 退出码为 0。
6. 本地启动服务并在浏览器完成算法题与问答题表单差异走查，以及“生成候选题、编辑、确认、导入”路径验证。

## 允许修改的范围

- `agent-runtime/app/tools_builtin/`
- `agent-runtime/config/prompts/artifacts/`
- `agent-runtime/tests/`
- `agent-backend/src/main/java/com/jobbuddy/backend/modules/interview/`
- `agent-backend/src/main/java/com/jobbuddy/backend/modules/chat/service/`
- `agent-backend/src/test/java/com/jobbuddy/backend/`
- `agent-frontend/src/components/interview/`
- `agent-frontend/src/components/InterviewBank.vue`
- `agent-frontend/src/api/interview.js`
- `agent-frontend/src/utils/interviewForm.js`
- `agent-frontend/src/styles/modules/practice-and-project.css`
- `agent-frontend/tests/`
- `agent-doc/业务能力/面试题库与模拟练习.md`

## 禁止事项

- 不允许在代码中维护固定算法题目录或固定答案。
- 不允许调用 LeetCode 未公开 GraphQL、抓取网页或绕过访问限制。
- 不允许在候选生成阶段写数据库。
- 不允许跳过人工确认直接导入。
- 不允许为通过测试弱化 `codingMeta` 和测试用例校验。

## 浏览器验证记录

- 访问地址：`http://127.0.0.1:5173/practice`
- 启动命令：`START_ALL_READY_TIMEOUT_SECONDS=90 ./scripts/start-all.sh`
- 用户路径：进入练习中心算法题库，打开新增题目并切换 AI 生成；填写数组与哈希表方向、LeetCode 标准题目链接、合法取得的参考题面和生成要求；生成 1 道候选题；在审核页验证未确认时无法入库；人工修改标题以及 4 组函数参数和期望结果；逐题确认并完成事务导入。
- 观察结果：真实模型成功返回包含题干、Python 函数入口、代码模板、参考答案和 4 组测试数据的候选题；候选阶段题库数量保持为 0；未勾选时页面显示“请先核对并确认候选题 1”，不会调用入库；人工修订并确认后题库数量变为 1。浏览器验证题随后通过精确标题删除，题库恢复为 0。页面重新加载后，三步生成流程、LeetCode 来源边界提示、上传入口和人工审核提示均正常展示。走查发现保存后分类统计未随题目列表刷新，已补充元数据同步刷新并由 `writtenExamCenter.test.js` 回归覆盖。
- 表单差异补充走查：算法题手动页显示“算法题标题”“数组与排序”“双指针”等算法提示，不显示问答题型；算法题 AI 页显示算法主题、代码语言、LeetCode 来源和算法资料提示。问答题手动页显示知识分类、问答题型和问答场景提示；切换为单选后，第二步变为题干与选项，第三步显示正确选项编号及单选填写提示。问答题 AI 页显示知识主题、问答题型、知识资料和问答出题要求，不显示代码语言与 LeetCode 链接。两条路径均无页面错误。
- 未覆盖项及原因：未在浏览器中上传真实 PDF、DOC 或 DOCX 文件，文档提取成功、截断和失败保留原文本路径由前端自动化测试覆盖。
