# 工具体系

本目录沉淀 job-buddy 的工具体系设计原则，是 CLAUDE.md 中"工具体系"小节的展开说明。涉及 agent-runtime 工具注册、agent-tool 工具服务、MCP 接入的改动必须先对照本文档。

## 工具的定位

- 工具单位是任务而非资源：围绕真实任务封装（如 boss_browser 的岗位搜索、详情懒加载），避免把底层 API 全量暴露给模型。
- 工具是 ACI（Agent-Computer Interface）而非 API 包装：参数清晰、返回结构化、错误可恢复、危险操作有权限拦截。
- 工具不得直接依赖平台内部数据库；必要配置通过运行时入参或配置文件传入。

## 命名与描述

- 工具命名用"业务域_资源_动作"格式，语义化、可检索。
- 描述写明适用场景、不适用场景、参数、返回、错误处理，等同 Prompt 资产，变更需评估对模型行为的影响。

## 登记与版本

工具登记 8 要素，缺一不可：

1. 名称
2. 描述
3. 参数 Schema
4. 返回结构
5. 错误码与错误结构
6. 权限声明（是否只读、是否破坏性）
7. 示例
8. 评测用例

破坏性变更必须走新版本，不允许原地修改已发布工具的语义。

## 返回结构

- 工具返回统一字段：status、summary、data、warnings、next_actions、trace_id。
- 错误结构必须包含 retryable 与 suggested_action，让模型能判断是否重试、如何修复。runtime 侧由 ToolGateway `_normalize_result` 统一补齐。
- 工具结果默认不可信：进入上下文前经过 Prompt Injection 探针打标（`app/core/tool/injection_probe.py`）。

## 规模化与延迟加载

- 大规模工具采用 Tool Search 延迟加载：稳定前缀常驻，按需展开 Schema，避免全量工具定义撑爆上下文。runtime 侧对应 ToolSearchService 与 ToolGateway.search 的能力收窄。
- 数据密集与中间数据搬运用 Programmatic Tool Calling 或代码执行，避免大块中间数据污染上下文。

## 可执行对象

可执行对象只有四类：MCP、API、CLI、代码。

- Skills 仅作操作手册，不直接执行。
- CLI 与代码必须进沙箱执行：本仓库 shell_exec 统一走 agent-sandbox（srt），沙箱不可用时 fail-closed。
- 高风险或破坏性工具必须显式声明，由 PermissionService 拦截。
