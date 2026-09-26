# job-buddy Agent Harness

`.agent-harness` 提供仓库统一的验证、行为评估、Goal/Loop 执行和运行报告能力。项目规则以根目录
`AGENTS.md`、`agent-doc/` 主题文档和各模块构建清单为准，Harness 只负责执行，不复制业务实现细节。

## 目录

`scripts/` 保存验证、评估、Goal 和 Loop 执行入口，`tests/` 保存 Harness 自身回归测试，
`goals/` 与 `loops/` 保存可执行任务定义，`browser-validation.md` 记录真实交互验证要求。
具体文件由目录和脚本帮助自动发现，不在文档中维护第二份文件清单。

运行产物写入 `.agent-harness/runs/`，默认保留 30 天且不提交 Git。

## 日常命令

```bash
# 本机依赖与脚本可用性
$ ./.agent-harness/scripts/doctor.sh

# 自动列出带构建清单的 agent-* 模块
$ ./.agent-harness/scripts/verify.sh --list

# 单模块测试、格式与构建
$ ./.agent-harness/scripts/verify.sh agent-backend --quick
$ ./.agent-harness/scripts/verify.sh agent-runtime --quick
$ ./.agent-harness/scripts/verify.sh agent-frontend --quick

# 交付门禁：模块验证 + 确定性评估
$ ./.agent-harness/scripts/gate.sh agent-backend --quick
$ ./.agent-harness/scripts/gate.sh all --quick
$ ./.agent-harness/scripts/gate.sh all --full

# Harness 自身测试
$ python3 -m unittest discover -s .agent-harness/tests -p 'test_*.py'

# 构建 Sandbox 镜像并验证真实 bwrap 与三语言运行时
$ ./.agent-harness/scripts/smoke_sandbox_container.sh
```

`verify.sh` 从顶层 `agent-*` 目录中的 `pom.xml`、`pyproject.toml` 或 `package.json` 自动发现模块，不维护第二份模块清单。Java 使用 Maven/Gradle，Python 使用 `uv`、Ruff 和 Pytest，前端使用 package scripts，并通过 npm 官方 Registry 审计生产依赖中的高危与严重漏洞。全仓验证还会检查根目录环境文件位置、Shell 语法，以及仅应用和完整基础设施两种 Compose 部署模式的渲染。

`--quick` 对 Java 使用 `test` 而不是 `verify`。Python 和前端仍执行完整的格式、测试与构建命令，避免“快速模式”变成跳过质量检查。

ORM 数据访问遵循 [ORM 数据访问规范](../agent-doc/工程规范/ORM数据访问规范.md)。Backend 的 `ChatAttachmentOrmTest` 使用 Testcontainers 验证真实 PostgreSQL 映射及属主、绑定条件。Runtime 的 `tests/test_checkpoint_postgres.py` 在提供 `CHECKPOINT_TEST_DATABASE_URL` 时，在随机隔离 schema 内验证 JSONB、保留数量、租户隔离和并发续跑领取，结束后删除该测试 schema；该连接应指向独立测试数据库，缺少连接时显式跳过，不代表数据库验收通过。Memory 的 `tests/test_migration.py` 也使用该测试连接验证旧表反射、只读迁移、重复执行及历史回滚。`verify.sh` 和 `gate.sh` 自动发现这些测试，无需额外维护用例列表。

Sandbox 的普通模块测试使用 fake srt，负责快速验证协议、参数和策略收窄；`smoke_sandbox_container.sh` 负责构建真实 Linux 镜像，以与 Compose 相同的轻量 PID 1 init、非 root、无 capability、只读根文件系统和 namespace 兼容边界验证 `/ready`、Python、Java、JavaScript、一次性 Python wheel 依赖、文件、网络、Unix socket 隔离以及子进程退出后无 zombie 泄漏。依赖目标目录位于独立的 `exec,nosuid,nodev` tmpfs 并随执行删除；普通 `/tmp` 与独立限额的下载缓存 tmpfs 保持 `noexec`，缓存可跨请求复用但不会挤占普通代码工作区。只有安装阶段可访问固定 PyPI 域名，代码阶段仍验证为无网络。轻量 init 只负责回收 `srt` 遗留的孙进程，不扩大容器权限。为避免 macOS Docker 虚拟机与不同 Linux 发行版依赖宿主机预装自定义 profile，Sandbox 容器固定使用 `apparmor=unconfined`；该设置仅作用于此容器，其他容器和宿主机全局 AppArmor 不受影响。不得启用 privileged、添加 `CAP_SYS_ADMIN` 或挂载 Docker socket。

本地服务启停测试覆盖端口监听者归属、未记录仓库进程清理、外部进程保护、PID 复用、停止后的端口释放、就绪监听与受管进程树一致性，以及启动失败后的回滚边界。前端启动固定使用 `strictPort`，避免端口冲突被 Vite 静默转换为其他端口。

## Flyway 检查

`check_flyway_migrations.py` 检查以下稳定约束：

- 文件名符合 `V<major>_<minor>_<patch>__<English_description>.sql`，英文描述以 `Create`、`Insert`、`Add`、`Alter`、`Update`、`Delete`、`Drop` 或 `Rename` 等 SQL 动作动词开头，版本不重复。
- 迁移中的 DML 只允许维护共享系统元数据、受控默认身份或同一迁移内声明的临时辅助表，用户私有业务数据必须通过受鉴权 API 写入。
- 表结构演进只需追加合法的新版本迁移，不需要同步修改 Harness。

Harness 不保存 SQL 内容哈希、迁移快照、当前最大版本或历史版本豁免。已部署数据库的不可变性由 Flyway schema history 的 checksum 在启动和部署时校验；仓库门禁只保留长期稳定的静态规则。

单独执行：

```bash
$ ./.agent-harness/scripts/check_flyway_migrations.py
$ python3 .agent-harness/tests/quality/test_check_flyway_migrations.py
```

## 评估与 Gate

`verify.sh` 负责模块自身的测试、格式和构建。`evaluate.sh` 运行 `agent-eval` 的评分器测试与 Engine Eval self-check，并执行 `agent-runtime/tests/contracts/test_runtime_delivery_contract.py` 的真实 Runtime 代码契约，覆盖终态 Trace、token 预算、持久化脱敏和高风险工具复核。前端行为测试属于 Vitest，由 `verify.sh agent-frontend` 执行。

评估模块测试包含预先标注的合成正反例校准与 runner 统计/失败报告契约，`evaluate.sh` 通过 `scripts/validate_cases.py` 自动发现并校验整个统一 YAML 目录的格式、类型、断言与引用；`business-job.yaml` 是业务规格，`memory-baseline.yaml` 由 Memory runner 读取，校准集只验证评分器，均不能当作真实模型结果。真实运行通过 `agent-eval/scripts/run_engine_eval.py` 单独采集，并记录部署标签、用例摘要、全部失败尝试和跳过原因。

`gate.sh` 先执行 Verify，再执行 Evaluate，并把日志和摘要写入：

```text
.agent-harness/runs/gate-<timestamp>-<target>/
├── verify.log
├── evaluate.log
├── gate.log
├── metadata-test.log
├── metadata.log
├── metadata.md
└── summary.md
```

Gate 先运行元数据契约测试，再生成 `metadata.md`；测试会用合成敏感 JVM 参数验证采集器不会把环境变量值写入产物。`summary.md` 在成功和失败时都会包含运行前采集的 Git SHA、dirty 状态、OS/架构、CPU/内存、Java/Python/Node/Docker 版本和依赖清单摘要。确定性 Gate 不调用真实模型或 Judge，元数据会明确记录这一边界；真实模型、浏览器、完整容器和远程 CI 仍需保存独立验收证据。元数据只记录工具版本与文件哈希，不读取或输出环境变量值。

仅在定位问题时使用 `--no-eval`；交付结果不应以该模式作为最终证据。

## Goal 与 Loop

创建单任务 Goal：

```bash
$ cp .agent-harness/goals/_template.md .agent-harness/goals/<task_slug>.md
$ ./.agent-harness/scripts/run_goal.sh .agent-harness/goals/<task_slug>.md
```

Goal 需要写清完成条件、允许范围、禁止事项、验证命令、预算和软着陆报告。`run_goal.sh` 默认使用 Claude CLI 的 `acceptEdits` 权限模式，相关环境变量和 front matter 字段以模板及脚本帮助为准。

创建周期性只读巡检：

```bash
$ cp .agent-harness/loops/_template.md .agent-harness/loops/<loop_name>.md
$ ./.agent-harness/scripts/loop.sh .agent-harness/loops/<loop_name>.md
```

`ci_health.md` 是只读巡检示例。允许写入的 Loop 必须显式设置权限并限定目录、命令、时间和停止条件。

## 浏览器验证

前端、登录弹窗、SSE、岗位卡片、会话恢复或其他用户可见交互改动，必须在自动化门禁之外执行浏览器验证。启动方式、Boss 风控红线和证据模板见
[`browser-validation.md`](browser-validation.md)。

浏览器验证至少记录访问地址、实际用户路径、观察结果和未覆盖原因。Boss 相关验证必须低频执行，出现验证码、访问异常或限速信号时立即停止。

## 提交前检查

```bash
$ ./.agent-harness/scripts/install-git-hooks.sh
```

安装脚本为当前 checkout 配置仓库内维护的 `pre-commit` Hook。每次提交先执行暂存区
`git diff --check`，再从 `verify.sh --list` 读取并验证改动涉及的模块；修改 Harness、构建脚本或工作流等共享验证入口时执行全仓快速验证。前端验证包含与 CI 同源的生产依赖审计，因此高危漏洞会在提交前直接阻断。新 clone 或新 worktree 需要执行一次安装脚本，`doctor.sh` 会检查并提示安装状态；CI 的 `quality-gate.yml` 仍是最终门禁。

## 维护原则

- 模块、依赖和命令从构建清单自动发现，不在多个脚本重复登记。
- 不新增需要随源码手工更新的 checksum、快照、文件数量或测试文件名单。
- 稳定规则放在 Harness；具体业务行为放在所属模块测试和 `agent-eval` 用例。
- Compose 检查关注应用与基础设施隔离，不锁死未来可扩展的服务清单。
- 临时历史残留字符串、一次性迁移例外和实现类名不应成为长期全仓正则门禁。
- 修改启动命令、构建清单、Flyway 规则或评估入口时，同步更新本文件和相关测试。

## 测试覆盖率

统一验证入口同时生成覆盖率报告：Python 在各模块的 `coverage/` 输出 HTML、JSON 和 XML，前端在 `agent-frontend/coverage/` 输出 HTML、JSON 和 LCOV，Java 在 `agent-backend/target/site/jacoco/` 输出 HTML、XML 和 CSV。所有报告为本地构建产物，不提交 Git。

Python 使用 `uv run --extra dev python -m pytest --cov --cov-report=html --cov-report=json --cov-report=xml`，前端使用 `npm run test:coverage`，Java 使用 `mvn test`。统计范围包含未被导入的生产源码；不排除低覆盖的业务模块。Java 默认报告包含现有 Spring 和数据库集成测试，不能将其解释为纯单元测试覆盖率。

所有模块的行覆盖率门禁为 80%。Python 用 JSON 报告中的缺失行数进行精确校验，避免将 pytest-cov 的行与分支合计指标误作行覆盖率；原有合计 80% 防退化门禁继续保留。前端同时保留语句 80%、分支 70%、函数 60% 的最低门槛。行覆盖率达标不等于分支覆盖率达标。JaCoCo 每次执行覆盖旧采样，防止历史测试结果累积掩盖退化。定向调试 Java 用例可用 `mvn -Dtest=ExampleTest -Djacoco.skip=true test`，交付时必须重新执行完整门禁。

Java 的 Spring / Testcontainers 用例标记为 `integration`。使用 `mvn -DexcludedGroups=integration test` 单独执行单元测试并检查 80% 行覆盖率，`mvn -Dgroups=integration -Djacoco.skip=true test` 单独执行集成测试。Harness 先执行纯单元测试门禁，将报告保存在 `target/site/jacoco-unit/`，再执行包含集成测试的原有完整测试流程；两者独立采样，后者报告保存在 `target/site/jacoco/`。缺少 Docker 导致跳过的数据库用例必须在交付记录中列出。
