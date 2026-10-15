# Implementation Plan: CLI——SableOS 的命令行入口与会话持久化

**Branch**: `003-lesson18-cli` | **Date**: 2026-10-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-lesson18-cli/spec.md`

## Summary

交付 SableOS 的命令行入口（Picocli，核心阶段 12 个子命令）与**会话持久化层**（`sessions` 表 + `SessionManager`）。CLI 是"消息进出的门，不是干活的人"——薄壳，只把用户输入转交第 17 节的 `AgentService`，自己不调模型、不执行工具。本节真正的地基是会话层：把第 17 节的进程内 `InMemorySessionStore` 升级为跨重启持久化，并收口 `session_id` 的单点拼接（`channel:user:profile`），供 CLI / Web / 定时三种入口复用。

技术路径（依赖倒置 + 入口结构对齐兄弟仓库 oryxos，理由见 research D-01/D-02/D-06/D-10）：

- **sableos-core**（框架无关，不引 Spring/Picocli 类型）：新增 `com.sableos.core.session.SessionManager` 契约（`extends SessionStore`，加 `getOrCreate`/`get`，并持有 `session_id` 单点拼接公式 `static String sessionId(channel,user,profile)`）；给既有 `Session` 加一个**追加式** `rehydrate(...)` 静态工厂（从持久化态重建，不改动既有构造与方法签名）。
- **sableos-storage**：新增 `SessionEntity`（映射 `sessions` 表）+ `SessionRepository` + `JpaSessionManager`（实现 `SessionManager`，负责领域 `Session` ↔ `SessionEntity` 映射与 `messages_json` 的 Jackson 序列化）；追加 Flyway 双轨 `V3__sessions.sql`。**删除** `InMemorySessionStore`（oryxos 全仓零命中，其存在理由即"18 节换真身"）。
- **sableos-channel-cli**：新增 `CliChannel`（chat 交互实现：`run(profileName,userId)` 读 stdin / 写 stdout / 维护当前会话 / `/quit` 退出 / 调 `AgentService.process`），框架无关（只依赖 core）。
- **sableos-cli**（本节升为**聚合入口**，对齐 oryxos-cli）：依赖 += storage / channel-cli / spring-boot-starter-data-jpa / sqlite-jdbc / snakeyaml（**不依赖 boot**）；`SableOsCli` 纯 Picocli 根挂 12 个 `@Command`；新增 **`SableOsRuntime`**（`@SpringBootApplication(scanBasePackages="com.sableos")` + `@EnableJpaRepositories/@EntityScan("com.sableos.storage")` + `SessionManager`/`CliChannel` @Bean）作重命令的上下文源；新增 `LightDbConfig`（轻命令裸 JDBC 解析 datasource）。重命令（chat/serve/gateway）在 `run()` 内 `new SpringApplicationBuilder(SableOsRuntime.class)...run()` 自启动；轻命令零 Spring。
- **sableos-boot**（仍产 fat JAR，仅改主类与收敛装配根）：`spring-boot-maven-plugin` 的 `<mainClass>` 改指 `com.sableos.cli.SableOsCli`；`SableOsApplication` **剥掉** `@EntityScan`/`@EnableJpaRepositories`（挪到 `SableOsRuntime`）降为最小 vestigial 应用类；`ReactConfiguration` 去掉 `sessionStore()`（`SessionManager` bean 移入 `SableOsRuntime`，`agentService(...)` 注入不变）；boot 既有 `@Configuration` 原地不动、由 `SableOsRuntime` 组件扫描收编；boot 的 `@SpringBootTest` 改指 `classes=SableOsRuntime.class`。

## Technical Context

**Language/Version**: Java 21（enforcer 钉死 `[21,)`；P3C/PMD 以 17 目标语法解析，避开 Java 18+ 语法形态：增强 switch 的 `default ->`、record pattern、pattern switch 一律不用；records/sealed 可用）

**Primary Dependencies**:

- **本节不新增任何第三方依赖**（软门禁 #6）。`sableos-cli` 升为聚合模块，新引的 `spring-boot-starter-data-jpa`/`sqlite-jdbc`/`snakeyaml` 均是 BOM 内既有构件（storage/provider 已在用），非新第三方。复用已锁定 BOM：
  - `info.picocli:picocli` 4.7.7（父 POM `dependencyManagement` 已管；`CommandLine`/`@Command`/`@Option`/`@Spec`/`RunAll` 均已在本地 jar 核实存在，见 research 附录 A）。
  - `com.fasterxml.jackson.core:jackson-databind`（core 已依赖，storage 经 core 传递可见）——用于 `messages_json` 的 `List<SessionMessage>` 序列化/反序列化（`SessionMessage`/`ToolCallRequest` 均为 record，Jackson 2 原生支持）。
  - `org.yaml:snakeyaml`（轻命令 `LightDbConfig` 读 datasource 配置，对齐 oryxos）；`org.xerial:sqlite-jdbc`（轻命令裸 JDBC 直读 sessions，运行期驱动）。
  - Spring Boot 3.5.16（`spring-boot-starter`、`spring-boot-starter-data-jpa`、`sqlite-jdbc`、`hibernate-community-dialects`、`flyway-core`、`flyway-database-postgresql`、`postgresql` 已在 storage/boot 就位；`SpringApplicationBuilder` 为标准 API）。
- 消费前序节交付：`AgentService`/`SessionStore`/`Session`/`SessionMessage`/`MessageRole`（17 节 core）、`Profile`/`ProfileRegistry`（16 节）、`TraceContext.CHANNEL_CLI/WEB/SCHEDULER`（链路内核）。

**Storage**: SQLite（sqlite-jdbc）+ Spring Data JPA + Flyway 双轨 `classpath:db/migration/{vendor}`；**手写** `V3__sessions.sql`（sqlite/postgresql 1:1，只追加不改 V1/V2），禁 `ddl-auto` 改结构。`Instant` 沿用既有绑定口径（`preferred_instant_jdbc_type=TIMESTAMP`，sqlite 以 INTEGER 毫秒往返）。

**Testing**: JUnit 5 + Mockito + AssertJ（父 POM 注入 `spring-boot-starter-test`）。本节 harness 两个测试类均在 **sableos-storage** 走 `@SpringBootTest`（真 Flyway + 临时 SQLite）：`SessionManagerTest`（幂等/隔离/id 公式）、`SessionRepositoryTest`（建表可存取 + `messages_json` 回读完整 + 模拟重启历史仍在）。CLI 命令分流与 `--help` 属进程级行为，**不进 harness**（留人工清单）。集成冒烟打 `@Tag("integration")` CI 默认跳过。测试方法名英文驼峰，课件中文原文进 `@DisplayName`。

**Target Platform**: JVM 服务端单体（fat JAR）；虚拟线程已启用。

**Project Type**: Maven 多模块库（本节改动集中在 core / storage / channel-cli / cli / boot 五个既有模块，不新增模块）。

**Performance Goals**: 轻命令"秒回"（不启动 Spring 上下文，规避 2~4s 启动开销）；重命令启动一次上下文后常驻。无其它量化指标。

**Constraints**:

- core 保持框架无关：`SessionManager` 契约与 `Session.rehydrate` 不引 Spring/Picocli/JPA 类型；`session_id` 公式是 core 内的单点静态方法（不变量 H4④）。
- 模块依赖方向：`core ← storage ← boot`、`core ← channel-cli ← cli ← boot`；**cli 不反向依赖 boot**（避免环）。重命令在 cli 侧只引用 core 契约类型取 bean，运行期实现由 fat JAR 提供。
- 只允许 `ObservabilityConfig` 那一个 `TaskDecorator`（本节不新增，AGENTS.md §5.2）。
- traceId 一律 `TraceContext`/`TraceIds`，禁 `UUID.randomUUID()`；跨线程用 `TraceContext.snapshot()`。
- `synchronized` 内不做阻塞调用（JDBC/文件）；`JpaSessionManager` 如需锁用 `ReentrantLock`（本节 getOrCreate 依赖 DB 主键幂等，通常无需显式锁）。
- 用户可控字符串（含 `--profile`/`--user` 入参、stdin 行）入日志前先 `replaceAll("[\\r\\n]", "_")` 净化，保持字面形态（findsecbugs 只认这种写法）。
- 凭证走环境变量占位，CLI 不硬编码任何 key。

**Scale/Scope**: 12 个子命令（核心阶段口径；第 13 个 `agent import` 归 025）；1 张新表（`sessions`）；每实例多 Agent、多会话并存。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据/说明 |
| --- | --- | --- |
| I. JDK 21 + Spring Boot 3.x 单体 | ✅ | 目标字节码 21；不引响应式；不新增模块；fat JAR 单二进制不变 |
| II. 自实现 ReAct 循环 | ✅ | 本节不触碰循环；CLI 只调 `AgentService.process`，循环仍由 core 自持 |
| III. Spring AI 只用一半 | ✅ | 本节不引 Spring AI 类型；chat 经 `AgentService`→`ReActLoop`→`ToolExecutor`，无自动工具执行路径 |
| IV. Provider 显式映射 | ✅ | 本节不触碰 Provider 路由；`provider list` 为只读展示（读配置，不做类型扫描） |
| V. Tool 三档且单模块；Agent 目录不当 Tool | ✅ | 本节不新增工具模块；`tool list` 为只读展示（工具注册归 20 节，当前 `ToolCatalog` 为空）；`profile *` 读 `.sableos/agents/` 目录，不当 Tool |
| VI. 审计 day one 落库 | ✅ | 会话持久化落 `sessions`（手写双轨 V3）；chat 经 `AgentService.process` 复用 16/17 节 `llm_calls`/`tool_invocations` 审计路径，不新增旁路 |
| VII. Sandbox 接口先行 | ✅(不触碰) | 本节不新建 Sandbox 类型；CLI 自身不做涉外 IO（`init` 只在工作区写骨架文件，属本地工程初始化，非工具涉外动作） |
| VIII. 范围收敛 | ✅ | serve 完整 Web 归 26 节、gateway 多通道归后续渠道节（本节仅命令外壳）；无会话按条拆表、无归档状态机流转（status 固定 active）、无 agent import |
| IX. 每个 US 可演示 Demo | ✅ | chat 可在终端与 Agent 多轮对话（配 16/17 节即成 Demo 一对话版）；会话层由两个 harness 测试类判卷 |

**Post-Phase 1 re-check**: 设计未引入新违规。四处设计取舍（core `Session` 追加 `rehydrate` 工厂、删除 `InMemorySessionStore` 并改 boot 两处 L17 装配/测试、cli 升聚合入口 + 新增 `SableOsRuntime` 装配根 + boot 主类改指 `SableOsCli`、`session list` 轻命令裸 JDBC）记入 Complexity Tracking，均为 clarify 拍板 + oryxos 成熟落地对齐，非违规；其中"改/删 L17 产物""改 fat JAR 主类归属与 boot 装配根/测试"属软门禁 #4/#1，**已按用户指示参照 oryxos 定夺**（见 research D-06/D-08/D-10）。

## Project Structure

### Documentation (this feature)

```text
specs/003-lesson18-cli/
├── plan.md              # 本文件
├── spec.md              # /speckit-specify + /speckit-clarify 产出
├── research.md          # Phase 0：D-01~D-09 决策与第三方 API 核实证据
├── data-model.md        # Phase 1：sessions 表（双轨）+ SessionEntity + 领域 Session ↔ 实体映射
├── quickstart.md        # Phase 1：可执行的验收向导
├── contracts/           # Phase 1
│   ├── session-manager.md      # SessionManager 契约 + session_id 公式 + rehydrate
│   ├── sessions-schema.md      # sessions 表列口径（双轨 1:1）
│   ├── cli-commands.md         # 12 个子命令的轻/重分类、参数、最小行为
│   └── cli-bootstrap.md        # boot 主类委托 Picocli + 惰性 Spring 工厂契约
└── tasks.md             # /speckit-tasks 产出
```

### Source Code (repository root)

```text
sableos-core/src/main/java/com/sableos/core/session/
├── Session.java              # 既有：追加 rehydrate(...) 静态工厂 + 私有全字段构造（既有公共 API 不变）
├── SessionStore.java         # 既有：零改动（save(Session)）
├── SessionMessage.java       # 既有：零改动
├── MessageRole.java          # 既有：零改动
└── SessionManager.java       # 本节新增：extends SessionStore；getOrCreate/get + static sessionId(...)

sableos-storage/src/main/java/com/sableos/storage/
├── SessionEntity.java        # 本节新增：sessions 表的 JPA 映射（@Id sessionId，非自增）
├── SessionRepository.java    # 本节新增：JpaRepository<SessionEntity,String>
├── JpaSessionManager.java    # 本节新增：implements SessionManager；Session↔SessionEntity 映射 + Jackson
└── InMemorySessionStore.java # 本节删除（被 JpaSessionManager 取代）
sableos-storage/src/main/resources/db/migration/
├── sqlite/V3__sessions.sql       # 本节新增
└── postgresql/V3__sessions.sql   # 本节新增（双轨 1:1）

sableos-channel-cli/src/main/java/com/sableos/channel/cli/
└── CliChannel.java           # 本节新增：chat 交互循环（读 stdin/写 stdout/维护会话//quit/调 AgentService）

sableos-cli/src/main/java/com/sableos/cli/            # 本节升为聚合入口（对齐 oryxos-cli）
├── SableOsCli.java           # 既有：纯 Picocli 根，挂 12 个 subcommands（main 不含 Spring）
├── SableOsRuntime.java       # 本节新增：@SpringBootApplication(scanBasePackages="com.sableos")
│                             #   + @EnableJpaRepositories/@EntityScan("com.sableos.storage")
│                             #   + @Bean SessionManager(JpaSessionManager) / CliChannel
└── command/                  # 本节新增：12 个 @Command 子命令类 + LightDbConfig
    ├── ChatCommand.java          # 重：SpringApplicationBuilder(SableOsRuntime).web(NONE).run → CliChannel.run
    ├── ServeCommand.java         # 重：外壳（web(SERVLET) 起上下文；完整 Web 归 26 节）
    ├── GatewayCommand.java       # 重：外壳（多通道归后续渠道节）
    ├── InitCommand.java          # 轻：初始化 .sableos/ 工程骨架
    ├── StatusCommand.java        # 轻：工作区/DB/Agent 计数状态
    ├── ProfileCommand.java       # 轻：profile list/create/show/delete（子命令组）
    ├── ProviderListCommand.java  # 轻：读 providers 配置列出
    ├── ToolListCommand.java      # 轻：列已注册工具（当前空，归 20 节）
    ├── SessionListCommand.java   # 轻：LightDbConfig 裸 JDBC 直读 sessions
    └── LightDbConfig.java        # 轻命令 datasource 解析（读配置 + ${ENV} 占位 + 缺省 SQLite）

sableos-boot/src/main/java/com/sableos/boot/
├── SableOsApplication.java   # 既有：剥掉 @EntityScan/@EnableJpaRepositories → 最小 vestigial（对齐 oryxos OryxOsApplication）
└── react/ReactConfiguration.java # 既有：去掉 sessionStore()（SessionManager bean 移入 cli 的 SableOsRuntime）

tests（与实现同行）:
sableos-storage/src/test/java/com/sableos/storage/SessionManagerTest.java     # harness
sableos-storage/src/test/java/com/sableos/storage/SessionRepositoryTest.java  # harness
sableos-boot/src/test/java/com/sableos/boot/ReactAssemblyTest.java            # 既有：@SpringBootTest(classes=SableOsRuntime) + 断言 sessionStore 为 JpaSessionManager
sableos-boot/src/test/java/com/sableos/boot/ProviderAssemblyTest.java         # 既有：@SpringBootTest 改指 classes=SableOsRuntime（JPA 扫描随迁）
sableos-channel-cli/src/test/java/com/sableos/channel/cli/CliChannelTest.java # 本节新增（可选）：喂脚本化 stdin 断言转交/退出
```

**Structure Decision**: 沿用既有模块骨架，不新增模块。会话契约与 `session_id` 单点公式落 core（框架无关、满足 H4④）；持久化实现（实体/仓储/JpaSessionManager/迁移）落 storage；chat 交互实现落 channel-cli；**命令树 + 聚合入口 + 装配根 `SableOsRuntime` 落 cli**（对齐 oryxos）；boot 收敛为"产 fat JAR（mainClass=SableOsCli）+ 既有 @Configuration 由 SableOsRuntime 扫描收编"。依赖方向严格单向：`core ← {storage, channel-cli} ← cli ← boot`，**cli 不反向依赖 boot**（无环）。

## Complexity Tracking

| 取舍 | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| 给 core `Session` 追加 `rehydrate(...)` 静态工厂 + 私有全字段构造（L17 类） | `SessionManager.get/getOrCreate` 必须能从持久化态重建带历史与原始时间戳的 `Session`；既有构造把 `createdAt=now`、`messages` 空、`status` 固定，无法承载恢复态。oryxos 实证：其 core `Session` 亦带 messages 构造供 `JpaSessionManager.restore` | 在 storage 侧另造一套会话模型 → 与 core 领域 `Session` 双轨漂移；用既有 `append*` 逐条重放 → `appendAssistantMessage` 只吃 `ProviderResponse`、无法还原任意 `SessionMessage`，且会把 `lastActiveAt` 刷成 now。**追加式改动（既有公共 API 与 L17 测试不变），软门禁 #4** |
| 删除 `InMemorySessionStore` 并改 boot 两处 L17 产物（`ReactConfiguration.sessionStore()`、`ReactAssemblyTest` 断言） | 其存在理由即"18 节换真身"（L17 javadoc/走读明载）；oryxos 全仓零命中；`AgentService` 的保存必须落库才有跨重启持久化 | 保留作死代码 → 违 AGENTS.md"不留 backwards-compat 死代码"；让它继续当 bean → 会话永不落库，FR-010/SC-003 不成立。**软门禁 #4** |
| cli 升为聚合入口 + 新增 `SableOsRuntime` 装配根 + boot 主类改指 `SableOsCli`/剥 JPA 注解/测试改指 SableOsRuntime | 对齐 oryxos 成熟落地：重命令在 cli 内 `SpringApplicationBuilder(SableOsRuntime.class)` 自启动，cli 必须能引用装配根 → 装配根落 cli；JPA 扫描注解只此一处避免与 boot 应用类重复注册；轻命令零 Spring 秒回 | boot 侧 `SableOsLauncher`+`LazySpringFactory`(IFactory)+`SpringBackedCommand` 标记（原草案）→ 多三个概念、IFactory 懒/饿边界易错，oryxos 证明"重命令自启动"更直白；`picocli-spring-boot-starter` → 新增依赖 + 饿汉起上下文。**软门禁 #1/#4：改 fat JAR 主类归属 + 动 L16/L17 boot 装配根/测试** |
| `session list` 作轻命令裸 JDBC 直读 SQLite（`LightDbConfig`），不起 Spring | 课件把 `session list` 归"查一下/列个表"的轻命令；oryxos `SessionListCommand`+`LightDbConfig` 逐字同构（读同一份配置、`${ENV}` 占位、缺省 SQLite、库未建提示"暂无会话"） | 做成重命令 → 违课件轻/重分类，为只读列表启动全上下文；本节不做真实会话查询接口（归扩展阶段） |
