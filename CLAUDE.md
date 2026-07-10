# CLAUDE.md

Claude Code 在本仓库中的项目级规则以 [AGENTS.md](./AGENTS.md) 为唯一完整事实来源。开始任务、修改代码或运行验证前必须先阅读并遵循 `AGENTS.md`，同时继续遵循目标模块的 README、`agent-doc/` 专题文档以及更深目录中的项目上下文文件。

## Claude Code 补充约束

- 每次生成代码前，先明确目标、边界、约束、禁止事项和可验证完成条件；优先交付小范围、可闭环改动。
- 不得弱化 `AGENTS.md` 中关于安全边界、私有数据、Flyway 历史迁移不可变、测试门禁和文档同步的要求。
- Java 新代码不得在 Controller、Service、Client 等跨层接口新增无 schema 的 `Map<String, Object>` 或 `MapBackedDto`；Map 仅允许保留在 Repository/Mapper 行数据与 JSON 编解码等边界内部。
- 完成实现后运行与改动范围匹配的 `.agent-harness/scripts/gate.sh`；无法运行的环境依赖必须明确报告为未验证，不得宣称通过。

如本文件与 `AGENTS.md` 冲突，以约束更严格者为准，并应通过修改 `AGENTS.md` 消除长期歧义，禁止再次复制完整规范形成双份维护。
