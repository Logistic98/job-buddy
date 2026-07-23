# job-buddy Agent Harness

`.agent-harness` 提供仓库统一的验证、行为评估、Goal/Loop 执行和运行报告能力。项目规则以根目录
`AGENTS.md`、`agent-doc/` 主题文档和各模块构建清单为准，Harness 只负责执行，不复制业务实现细节。

## 目录

```text
.agent-harness/
├── browser-validation.md
├── goals/
│   └── _template.md
├── loops/
│   ├── _template.md
│   └── ci_health.md
├── scripts/
│   ├── check_flyway_migrations.py
│   ├── doctor.sh
│   ├── evaluate.sh
│   ├── gate.sh
│   ├── judge.sh
│   ├── loop.sh
│   ├── new_worktree.sh
│   ├── pre-commit-hook.sh
│   ├── run_goal.sh
│   ├── status.sh
│   └── verify.sh
└── tests/
    ├── test_check_flyway_migrations.py
    ├── test_infrastructure_init.py
    ├── test_start_all.py
    └── test_stop_all.py
```

运行产物写入 `.agent-harness/runs/`，默认保留 30 天且不提交 Git。

## 日常命令

```bash
# 本机依赖与脚本可用性
./.agent-harness/scripts/doctor.sh

# 自动列出带构建清单的 agent-* 模块
./.agent-harness/scripts/verify.sh --list

# 单模块测试、格式与构建
./.agent-harness/scripts/verify.sh agent-backend --quick
./.agent-harness/scripts/verify.sh agent-runtime --quick
./.agent-harness/scripts/verify.sh agent-frontend --quick

# 交付门禁：模块验证 + 确定性评估
./.agent-harness/scripts/gate.sh agent-backend --quick
./.agent-harness/scripts/gate.sh all --quick
./.agent-harness/scripts/gate.sh all --full

# Harness 自身测试
python3 -m unittest discover -s .agent-harness/tests -p 'test_*.py'
```

`verify.sh` 从顶层 `agent-*` 目录中的 `pom.xml`、`pyproject.toml` 或 `package.json` 自动发现模块，不维护第二份模块清单。Java 使用 Maven/Gradle，Python 使用 `uv`、Ruff 和 Pytest，前端使用 package scripts。全仓验证还会检查根目录环境文件位置、Shell 语法和 Compose 渲染。

`--quick` 对 Java 使用 `test` 而不是 `verify`。Python 和前端仍执行完整的格式、测试与构建命令，避免“快速模式”变成跳过质量检查。

本地服务启停测试覆盖端口监听者归属、未记录仓库进程清理、外部进程保护、PID 复用、停止后的端口释放、就绪监听与受管进程树一致性，以及启动失败后的回滚边界。前端启动固定使用 `strictPort`，避免端口冲突被 Vite 静默转换为其他端口。

## Flyway 检查

`check_flyway_migrations.py` 检查以下稳定约束：

- 文件名符合 `V<major>_<minor>_<patch>__<English_description>.sql`，版本不重复。
- 迁移中的 DML 只允许维护共享系统元数据或受控默认身份，用户私有业务数据必须通过受鉴权 API 写入。
- 表结构演进只需追加合法的新版本迁移，不需要同步修改 Harness。

Harness 不保存 SQL 内容哈希、迁移快照、当前最大版本或历史版本豁免。已部署数据库的不可变性由 Flyway schema history 的 checksum 在启动和部署时校验；仓库门禁只保留长期稳定的静态规则。

单独执行：

```bash
./.agent-harness/scripts/check_flyway_migrations.py
python3 .agent-harness/tests/test_check_flyway_migrations.py
```

## 评估与 Gate

`verify.sh` 负责模块自身的测试、格式和构建。`evaluate.sh` 只运行 `agent-eval` 的评分器测试和 Engine Eval self-check，不再维护一套与模块测试重复的测试文件列表。前端行为测试属于 Vitest，由 `verify.sh agent-frontend` 执行。

`gate.sh` 先执行 Verify，再执行 Evaluate，并把日志和摘要写入：

```text
.agent-harness/runs/gate-<timestamp>-<target>/
├── verify.log
├── evaluate.log
├── gate.log
└── summary.md
```

仅在定位问题时使用 `--no-eval`；交付结果不应以该模式作为最终证据。

## Goal 与 Loop

创建单任务 Goal：

```bash
cp .agent-harness/goals/_template.md .agent-harness/goals/<task_slug>.md
./.agent-harness/scripts/run_goal.sh .agent-harness/goals/<task_slug>.md
```

Goal 需要写清完成条件、允许范围、禁止事项、验证命令、预算和软着陆报告。`run_goal.sh` 默认使用 Claude CLI 的 `acceptEdits` 权限模式，相关环境变量和 front matter 字段以模板及脚本帮助为准。

创建周期性只读巡检：

```bash
cp .agent-harness/loops/_template.md .agent-harness/loops/<loop_name>.md
./.agent-harness/scripts/loop.sh .agent-harness/loops/<loop_name>.md
```

`ci_health.md` 是只读巡检示例。允许写入的 Loop 必须显式设置权限并限定目录、命令、时间和停止条件。

## 浏览器验证

前端、登录弹窗、SSE、岗位卡片、会话恢复或其他用户可见交互改动，必须在自动化门禁之外执行浏览器验证。启动方式、Boss 风控红线和证据模板见
[`browser-validation.md`](browser-validation.md)。

浏览器验证至少记录访问地址、实际用户路径、观察结果和未覆盖原因。Boss 相关验证必须低频执行，出现验证码、访问异常或限速信号时立即停止。

## 可选 pre-commit hook

```bash
ln -s ../../.agent-harness/scripts/pre-commit-hook.sh .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

Hook 从 `verify.sh --list` 读取模块，并只验证当前暂存改动涉及的模块。它默认不安装，CI 的 `quality-gate.yml` 仍是最终门禁。

## 维护原则

- 模块、依赖和命令从构建清单自动发现，不在多个脚本重复登记。
- 不新增需要随源码手工更新的 checksum、快照、文件数量或测试文件名单。
- 稳定规则放在 Harness；具体业务行为放在所属模块测试和 `agent-eval` 用例。
- Compose 检查关注应用与基础设施隔离，不锁死未来可扩展的服务清单。
- 临时历史残留字符串、一次性迁移例外和实现类名不应成为长期全仓正则门禁。
- 修改启动命令、构建清单、Flyway 规则或评估入口时，同步更新本文件和相关测试。
