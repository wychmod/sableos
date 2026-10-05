# 实施计划：SableOS 运行时内核（五大核心能力）

**功能分支**：`001-runtime-core` | **日期**：2026-10-05 | **规格**：[spec.md](./spec.md)

**输入**：`specs/001-runtime-core/spec.md`（5 个用户故事、41 条功能需求、12 条成功标准）

**依据**：技术方案以 `docs/TechnicalSolution.md` 为准（仓库内编码的权威依据）；非协商条款以 `.specify/memory/constitution.md` 为准。

## 摘要

本功能交付 SableOS 的**运行时内核**：五大核心能力（Provider 抽象、自实现 ReAct 循环、Memory 三层门面、Tool 体系与 Sandbox、Web Service）加上让它们跑起来的支撑模块（Profile 加载、Session 持久化、CLI、定时触发、配置与密钥加载）。

技术路径：在既有 Maven 多模块骨架上落地「Provider 供养 ReAct、ReAct 调度 Tool 与 Memory、三条触发入口（CLI / Web / 定时）汇入同一个 `AgentService`」这条主链路；审计表 day one 落库；Sandbox 与应用层白名单作为核心阶段唯一的工具治理手段。

## 技术上下文

**语言 / 版本**：JDK 21（构建锁定 Java 21 目标；本机曾出现 JDK 8 下静默产出 Java 8 字节码的先例）

**主要依赖**：Spring Boot 3.x（单体，virtual thread）｜Spring AI + Spring AI Alibaba（只用 Provider 抽象、协议转换、`@Tool` schema 生成）｜Spring MVC｜Picocli｜SnakeYAML｜MCP Java SDK｜Logback + SLF4J

**存储**：SQLite + Spring Data JPA（表结构管理走 Flyway 双轨目录 `db/migration/{vendor}/`，SQLite 为默认零配置档）｜长期记忆：默认档用 `.sableos/memory/MEMORY.md` 文件，可选档用 `memory_entries` 表

**测试**：JUnit 5 + Spring Boot Test（单元与集成）；端到端验收按 quickstart 的场景脚本执行。技术方案未细化测试策略，本计划按此默认执行，若与后续约定不一致以仓库约定为准

**目标平台**：企业自托管的单节点服务（Linux/macOS 均可），单二进制 fat JAR，`java -jar` 启动

**项目类型**：CLI + Web 服务一体的单体多模块应用

**性能目标**：单节点 10 个 Agent 连续 4 小时稳定；100 个并发会话；会话创建 P99 < 200ms；入口转发到内部处理的额外开销 < 50ms；单次 Agent 调用最长 60 秒超时（返回 504）

**约束**：禁用 Spring AI 自动 tool 执行；禁用 `SecurityManager`；Provider 必须显式映射；Tool 只允许一个模块；审计表必须真实落库；核心阶段无认证/无多租户（假设受信内网）

**规模与范围**：本功能涉及 14 个模块中的 **9 个既有模块**（core / provider / memory / tool / channel-cli / web / storage / cli / boot），persona / knowledge / 三渠道模块只预留位置不创建；41 条 FR、12 个关键实体、10 个 REST 端点、12 个 CLI 子命令

## 宪章检查

*门禁：Phase 0 之前通过；Phase 1 设计完成后复检。*

| 原则 | 本计划的落实方式 | 结论 |
| --- | --- | --- |
| I JDK 21 + Spring Boot 3.x 单体 | pom 锁定 `maven.compiler.release=21`；单二进制 fat JAR；virtual thread 处理并发；模块划分以技术方案第 10 章为准，本功能只落地 9 个既有模块 | 通过 |
| II 自实现 ReAct 循环 | `ReActLoop` 自写，迭代、消息累积、终止判断全在本仓库；不引入 Spring AI 的 Agent 抽象 | 通过 |
| III Spring AI 只用一半 | 仅用 `ChatClient` 调用与 `@Tool` schema 生成；禁止注册 Spring AI 的自动 tool 执行链；`ToolExecutor` 是唯一工具执行入口 | 通过 |
| IV Provider 显式映射 | `ProviderService` 维护 provider name → `ChatModel` 的显式映射表，不做类型扫描 | 通过 |
| V Tool 三档接入且单模块 | 内置 Tool、MCP Tool、`@Tool` 注解 Tool 统一包装为 `SableTool` 注册进 `ToolRegistry`，全部落在 `sableos-tool` 一个模块；`AGENT.md`/`AgentLoader` 归 core 的 `ContextLoader` | 通过 |
| VI 审计 day one 落库 | `tool_invocations` 与 `llm_calls` 两张表在本次交付中即写入（含被 Sandbox 拒绝的调用），查询接口不在本次范围 | 通过 |
| VII Sandbox 接口先行 | 定义不携带实现细节的 `Sandbox`/`SandboxAction`；核心阶段只挂 `WhitelistSandbox`；不使用 `SecurityManager` | 通过 |
| VIII 范围收敛 | 核心阶段不实现多租户 / SSO / 完整审计查询 / Tool Policy；扩展阶段新增 Channel 或 Tool 只加新模块；入站渠道契约与知识库契约的位置只预留 | 通过 |
| IX 可演示 Demo + analyze 门槛 | 每个用户故事完成后跑一次 `/speckit.analyze` 并打 commit；验收以端到端 Demo 为准 | 通过 |

**Phase 1 复检结论**：设计产物（data-model / contracts / quickstart）未引入新的违规项；`SableTool`、`ToolExecutor`、`Sandbox`、`MemoryService` 的接口边界与原则 II/III/V/VII 一致。

## 项目结构

### 本次功能的文档产物

```text
specs/001-runtime-core/
├── plan.md              # 本文件
├── spec.md              # 功能规格（已产出，含澄清记录）
├── research.md          # Phase 0：技术决策与待确认偏差
├── data-model.md        # Phase 1：实体、表结构与状态流转
├── quickstart.md        # Phase 1：可执行的验收场景
├── contracts/
│   ├── http-api.md              # 10 个 REST 端点的契约
│   ├── cli.md                   # 12 个 CLI 子命令
│   └── workspace-and-config.md  # .sableos 工作区布局与配置文件 schema
└── checklists/
    └── requirements.md  # 规格质量校验清单（已产出）
```

### 源码结构（仓库根）

```text
pom.xml                        # 聚合 POM（已存在）
sableos-core/                  # SableTool、Session、Profile、ContextLoader、AgentLoader、
                               #   ReActLoop、PromptBuilder、ToolExecutor、AgentService、
                               #   AgentScheduler、Sandbox 接口、ScheduledTaskStore 接口
sableos-provider/              # ProviderService、Function Calling 适配、provider 配置与显式映射
sableos-memory/                # MemoryService 门面、LongTermMemoryStore 接口、MarkdownMemoryStore、MemoryTools
sableos-tool/                  # FileTools、ShellTools、HttpTools、NotifyTools、ToolRegistry、
                               #   McpClientService、McpToolAdapter、WhitelistSandbox、WebhookNotifyAdapter
sableos-channel-cli/           # CliChannel（sableos chat）
sableos-web/                   # WebServer、6 个 ApiController、GlobalExceptionHandler、OpenAPI
sableos-storage/               # SQLite 数据源、SessionRepository、ToolInvocationRepository、
                               #   LlmCallRepository、db/migration/{vendor}/ 迁移脚本
sableos-cli/                   # Picocli 主入口、12 个子命令、ConfigLoader
sableos-boot/                  # 主类、自动配置、依赖聚合（产出 fat JAR）

sableos-*/src/test/java/       # 单元与集成测试（JUnit 5 + Spring Boot Test）
```

**结构决策**：沿用仓库既有的 Maven 多模块骨架（2026-09-27 已初始化，`mvn clean package` 可通过），不新增模块。persona / knowledge / channel-feishu / channel-wecom / channel-dingtalk 五个模块本次不创建，但其契约位置（`sableos-core/channel/`、`sableos-core/knowledge/`）在设计上留白，避免后续加模块时改动 core 的既有接口。

## 已确认的范围偏差

以下是本计划相对 `docs/TechnicalSolution.md`（冲突时的权威依据）的**有意偏差**，均已在 spec 的澄清记录中定案：

| 偏差 | 技术方案口径 | 本计划口径 | 依据 |
| --- | --- | --- | --- |
| 定时任务的状态持久化与管理端点 | 第 8.5/28 节：`scheduled_tasks`、`task_executions` 两张表 + `/api/v2/schedules` 管理端点 | 不做：只做 cron 到点触发 + 手动补跑复用 `POST /api/v1/agents/{name}/invoke` | 项目方在 clarify 阶段选定的最小形态（spec 澄清记录第 1 条），符合宪章原则 VIII 的范围收敛 |
| 长期记忆后端数量 | 第 5.1 节：三档后端（Markdown / SQLite / Mem0）核心阶段一次交付 | 交付后端接口 + Markdown（默认）+ SQLite 两档（零外部依赖）；**Mem0 档与语义检索升级（015）留后续** | 接口墙一次立起（技术方案的本意）已满足；Mem0 依赖自托管外部服务，离线无法验收。项目方已确认（spec 澄清记录第 7 条） |
| 通知渠道的登记途径 | 第 6.8 节：通过 Web 管理台或 `/api/v1/notify-channels` 做 CRUD | 定义源改为工作区文件 `.sableos/notify_channels.yaml`（与 `mcp_servers.yaml` 同形态，`init` 生成模板），启动时 reconcile 进 `notify_channels` 表；CRUD 端点仍留后续 | 三个 Demo 的推送目标必须可配置，而管理端点属"第二批"；文件形态与"核心阶段手动改文件"的阶段边界一致（spec 澄清记录第 8 条） |

**已对齐、不再构成偏差的两项**：

- **验收 Demo 数量**：按《技术方案》第 12 章取**三个**（每日天气、每日科技日报、每日 GitHub 日报）全部必过；《需求文档》第 13 章的"两个"记为文档漂移，已登记在 `AGENTS.md` 的已知漂移清单。
- **MCP 接入（Plugin Tool 方式二）**：本计划包含，第三个 Demo 依赖它。
