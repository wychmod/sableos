---
description: "Task list for 003-lesson18-cli"
---

# Tasks: CLI——SableOS 的命令行入口与会话持久化

**Input**: Design documents from `/specs/003-lesson18-cli/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: 课件「验收 harness」明确要求 `SessionManagerTest`/`SessionRepositoryTest` 两个测试类，故**测试为必需**（非可选），且**harness 先行**（测试任务先于/伴随对应实现）。CLI 命令分流与 `--help` 属进程级行为，课件明确**不进 harness**（留人工清单）。测试方法名英文驼峰，课件中文原文进 `@DisplayName`。

**Organization**: 按 user story 分组。**阶段顺序 = 依赖顺序（非严格优先级）**：US1(chat)/US3(boot 收敛) 依赖 US2(会话层) 与 US4(命令树 + 聚合入口)，故 US2 先落（它也是唯一被 harness 判卷的地基）。详见 Dependencies。

**入口结构对齐 oryxos**（research D-06，用户指定参照 `D:\idea\oryxos`）：cli 升为聚合入口、新增 `SableOsRuntime` 装配根、fat JAR mainClass 改指 `SableOsCli`、重命令自启动、轻命令零 Spring（`session list` 裸 JDBC）。**原 `SableOsLauncher`/`LazySpringFactory`/`SpringBackedCommand` 草案作废。**

**软门禁标记**：`【SG#4】`=改/删前序节公共产物；`【SG#1】`=新增交付物清单外的对外概念/改归属。均已按用户指示参照 oryxos 定夺，implement 时逐条落实、收尾报告复述。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属 user story（US1–US4）
- 每条任务含确切文件路径

## Path Conventions

Maven 多模块，模块内标准布局：`sableos-<module>/src/main/java/com/sableos/...` 与 `sableos-<module>/src/test/java/com/sableos/...`；迁移在 `sableos-storage/src/main/resources/db/migration/{sqlite,postgresql}/`。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 依赖与迁移纪律核对（本节零新增第三方依赖，软门禁 #6）

- [x] T001 [P] 核对依赖与迁移口径：确认 `picocli` 4.7.7 已在父 POM `dependencyManagement`（research 附录 A 已核实 `CommandLine`/`@Command`/`@Option`/`@Spec`）；确认 `sableos-core/pom.xml` 已含 `jackson-databind`；确认 `db/migration/README.md` 已预告 `sessions`（18 节）；规划 `sableos-cli` 升聚合模块待加的 BOM 内既有构件（storage / channel-cli / spring-boot-starter-data-jpa / sqlite-jdbc / snakeyaml，T014），**均非新第三方**

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: core 会话契约（US1/US2 均依赖）；纯 core 类型，框架无关

**⚠️ CRITICAL**: US1/US2 均依赖本阶段完成

- [x] T002 [P] 新增 `SessionManager` 接口（`extends SessionStore`；`static String sessionId(String channel,String userId,String profileName)` 返回 `channel+":"+userId+":"+profileName`；`Session getOrCreate(String channel,String userId,String profileName)`；`java.util.Optional<Session> get(String sessionId)`）于 `sableos-core/src/main/java/com/sableos/core/session/SessionManager.java`（契约见 [contracts/session-manager.md](./contracts/session-manager.md)，research D-01/D-02/D-10）
- [x] T003 [P] 给既有 `Session` 追加 `public static Session rehydrate(String id,String profileName,String channel,String userId,String status,java.time.Instant createdAt,java.time.Instant lastActiveAt,java.util.List<SessionMessage> messages)` + 私有全字段构造；既有 4 参公共构造改为委托私有构造（`createdAt=now`/`status="active"`/空历史），**所有既有 getter 与 `append*` 方法签名不变**于 `sableos-core/src/main/java/com/sableos/core/session/Session.java`（[data-model.md](./data-model.md) §3、research D-04）【SG#4：追加式改 L17 类，既有 API 与 L17 测试不变】

**Checkpoint**: 会话契约就位——US2 持久化实现可开始

---

## Phase 3: User Story 2 - 会话有身份、跨重启连续 (Priority: P2) 🎯 harness 地基

**Goal**: `SessionManager` 幂等/隔离/单点 id + `sessions` 表跨重启持久化；取代 L17 进程内存储

**Independent Test**: `SessionManagerTest` + `SessionRepositoryTest`（真 Flyway + 临时 SQLite）全绿即判卷

### Tests for User Story 2（harness 先行）⚠️

- [x] T004 [P] [US2] 写 `SessionManagerTest`（`@SpringBootTest(classes=StorageTestApplication.class)`，`@DynamicPropertySource` 临时 SQLite + `preferred_instant_jdbc_type=TIMESTAMP`，同 `ToolInvocationRepositoryTest` 口径）于 `sableos-storage/src/test/java/com/sableos/storage/SessionManagerTest.java`：①`getOrCreate_sameTriple_returnsSameSession`（**课件 harness 原样落地**：`getOrCreate("cli","wang","default")` 两次 `assertEquals(first.id(),second.id())`；`@DisplayName("同一三元组_历次getOrCreate都是同一个Session")`）②`getOrCreate_differentChannel_returnsDifferentSession`（`getOrCreate("web","wang","default")` 与 cli 版 `assertNotEquals`；user/profile 任一不同同理）③`sessionId_matchesFormula`（id == `channel:user:profile`）
- [x] T005 [P] [US2] 写 `SessionRepositoryTest` 于 `sableos-storage/src/test/java/com/sableos/storage/SessionRepositoryTest.java`：④`sessionsTable_hasRequiredColumns_viaPragma`（PRAGMA 含 9 列）+ `flyway_schema_history` 含 version 1/2/3 ⑤`messagesJson_roundTrips_allFields`（带 `toolCalls` 的 ASSISTANT、带 `toolName`/`success` 的 TOOL、`success=null` 的 USER 逐字段还原）⑥`restart_newContext_historyPersists`（新建上下文重查历史仍在）

### Implementation for User Story 2

- [x] T006 [P] [US2] 新增 `SessionEntity`（`@Entity @Table(name="sessions")`；`@Id String sessionId`**无 `@GeneratedValue`**；9 列映射 `profile_name`/`channel`/`user_id`/`messages_json`/`status`/`created_at`/`last_active_at`/`archived_at`；时间戳 `Instant`；protected 无参构造）于 `sableos-storage/src/main/java/com/sableos/storage/SessionEntity.java`（[data-model.md](./data-model.md) §2、research D-03）
- [x] T007 [P] [US2] 新增 `SessionRepository extends JpaRepository<SessionEntity,String>` 于 `sableos-storage/src/main/java/com/sableos/storage/SessionRepository.java`
- [x] T008 [P] [US2] 新增 `V3__sessions.sql` 双轨（sqlite：`session_id TEXT PRIMARY KEY` + `created_at/last_active_at/archived_at INTEGER`；postgresql：`VARCHAR(255) PRIMARY KEY` + `TIMESTAMPTZ`；9 列 1:1，`messages_json TEXT NOT NULL`、`status` 默认 `active`；**只追加不改 V1/V2**）于 `sableos-storage/src/main/resources/db/migration/sqlite/V3__sessions.sql` 与 `.../postgresql/V3__sessions.sql`（[contracts/sessions-schema.md](./contracts/sessions-schema.md)）
- [x] T009 [US2] 实现 `JpaSessionManager implements SessionManager`（构造注入 `SessionRepository`；`getOrCreate`：`id=SessionManager.sessionId(...)`→`findById` 命中则 `Session.rehydrate(...)`（`ObjectMapper.readValue(messagesJson, new TypeReference<List<SessionMessage>>(){})`）否则新建 entity(status=active)+save 并返回新 `Session`；`get`：命中 rehydrate / 否则 `Optional.empty()`；`save`：`findById` 命中→`writeValueAsString(session.messages())`+`lastActiveAt=now`→save；`ObjectMapper` 空历史 `[]`、序列化失败显式抛不静默）于 `sableos-storage/src/main/java/com/sableos/storage/JpaSessionManager.java`（依赖 T002/T003/T006/T007；[contracts/session-manager.md](./contracts/session-manager.md)、[data-model.md](./data-model.md) §3、research D-05）**写前先跑 D-05 的 round-trip 核实点**
- [x] T010 [US2] 删除 `sableos-storage/src/main/java/com/sableos/storage/InMemorySessionStore.java`；改 `sableos-boot/.../react/ReactConfiguration.java`：去掉 `sessionStore()`（`InMemorySessionStore`），改为 `@Bean SessionManager sessionManager(SessionRepository repo){return new JpaSessionManager(repo);}`（`agentService(...,SessionStore)` 注入不变——`SessionManager` is-a `SessionStore`）；改 `sableos-boot/src/test/.../ReactAssemblyTest.java` 把 `assertThat(sessionStore).isInstanceOf(InMemorySessionStore.class)` 改为断言其为 `JpaSessionManager`（其余八 bean 断言与 process 落库断言不变）【SG#4：删 L17 类 + 改 L17 装配/测试，research D-08/D-10】
- [x] T011 [US2] 跑 `mvn -q -pl sableos-storage -am test -Dtest=SessionManagerTest,SessionRepositoryTest` 全绿；再跑 `mvn -q -pl sableos-storage,sableos-boot test`（含 L16/17 的 `LlmCallRepositoryTest`/`ToolInvocationRepositoryTest`/`ReactAssemblyTest` 回归）全绿

**Checkpoint**: 会话层地基成立、harness 判卷通过——US1/US4/US3 可开始

---

## Phase 4: User Story 1 - 在终端里跟 Agent 说上话 (Priority: P1)

**Goal**: `CliChannel` 交互循环（读 stdin→交引擎→打印→`/quit`/EOF 退出），薄壳无 Agent 智能

**Independent Test**: `CliChannelTest` 喂脚本化 stdin（mock `AgentService`），断言逐行转交、回复打印、`/quit`/EOF 即停、自身不触发模型/工具

### Implementation for User Story 1

- [x] T012 [P] [US1] 实现 `CliChannel`（构造 `(AgentService, SessionManager)`，框架无关；`void run(String profileName, String userId)`：`session=sessionManager.getOrCreate("cli",userId,profileName)`→`while(true)` 打印 `"> "`、读 stdin 一行→`null`(EOF) 或 `trim().equals("/quit")` 则打印"再见。"return→`isBlank()` 跳过→`reply=agentService.process(session,line)`→打印 reply；用户输入入日志前 CRLF 净化；对齐 oryxos `CliChannel`）于 `sableos-channel-cli/src/main/java/com/sableos/channel/cli/CliChannel.java`（[contracts/cli-commands.md](./contracts/cli-commands.md) chat 契约、research D-09）
- [x] T013 [P] [US1] 写 `CliChannelTest`（可选但建议；mock `AgentService`/`SessionManager`，`ByteArrayInputStream` 喂多行含 `/quit`，断言 `process` 调用次数=非退出非空行数、回复被打印、读到 `/quit`/EOF 停止、`CliChannel` 不直接触发模型/工具）于 `sableos-channel-cli/src/test/java/com/sableos/channel/cli/CliChannelTest.java`（依赖 T012）

**Checkpoint**: chat 交互实现就位（其命令外壳与启动在 US4/US3）

---

## Phase 5: User Story 4 - 一整套操作面：12 个子命令 + 聚合入口 (Priority: P4)

**Goal**: cli 升聚合入口；`SableOsCli` 根挂 12 个 `@Command`；`SableOsRuntime` 装配根；轻命令纯文件/裸 JDBC、重命令自启动

**Independent Test**: 断言根命令注册 12 个子命令且各有其名；轻命令不起上下文（进程级，主留人工）

### Implementation for User Story 4

- [x] T014 [US4] `sableos-cli/pom.xml` 升聚合模块：加依赖 `sableos-storage`、`sableos-channel-cli`、`spring-boot-starter-data-jpa`、`org.xerial:sqlite-jdbc`、`org.yaml:snakeyaml`（均 BOM 内既有构件，**不依赖 boot**、不新增第三方）【SG#1：cli 依赖面扩大，知会】
- [x] T015 [US4] 改 `SableOsCli` 为纯 Picocli 根命令（`@Command(name="sableos", mixinStandardHelpOptions=true, subcommands={...})`，`main` = `System.exit(new CommandLine(new SableOsCli()).execute(args))`，**不含 Spring**）；挂 12 子命令（chat/serve/gateway/init/status/profile/provider list/tool list/session list；`profile` 组含 list/create/show/delete，合计 12 口径见 §8.7）于 `sableos-cli/src/main/java/com/sableos/cli/SableOsCli.java`（依赖 T014、T016–T022）
- [x] T016 [P] [US4] `ChatCommand`（`@Command(name="chat")`，`--profile` 默认 `default`；`run()` 内 `try(var ctx=new SpringApplicationBuilder(SableOsRuntime.class).web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).run()){ ctx.getBean(CliChannel.class).run(profileName, currentUser()); }`；`currentUser()=System.getProperty("user.name","unknown")`，**无 `--user`**）于 `sableos-cli/src/main/java/com/sableos/cli/command/ChatCommand.java`（依赖 T012/T022；对齐 oryxos `ChatCommand`）
- [x] T017 [P] [US4] `ServeCommand` + `GatewayCommand`（重命令外壳：`run()` 内 `new SpringApplicationBuilder(SableOsRuntime.class).web(SERVLET).run()` 起上下文后打印占位「Web Service 归 26 节」/「多通道归后续渠道节」，不实现完整功能）于 `sableos-cli/src/main/java/com/sableos/cli/command/ServeCommand.java` 与 `GatewayCommand.java`（spec 边界）
- [x] T018 [P] [US4] 轻命令 `InitCommand`（建 `.sableos/` 骨架：agents/ 等目录 + 最小配置，已存在则提示不覆盖）+ `StatusCommand`（报工作区根、DB 路径、`.sableos/agents/` 下 Agent 计数）于 `sableos-cli/src/main/java/com/sableos/cli/command/InitCommand.java` 与 `StatusCommand.java`（**不构造 SpringApplicationBuilder**）
- [x] T019 [P] [US4] 轻命令 `ProfileCommand`（`@Command(name="profile")` 组，子命令 list/create/show/delete，读写 `.sableos/agents/`；delete 前提示确认）于 `sableos-cli/src/main/java/com/sableos/cli/command/ProfileCommand.java`（不起 Spring）
- [x] T020 [P] [US4] 轻命令 `ProviderListCommand`（读 providers 配置列名与 type，**不做类型扫描**，原则 IV）+ `ToolListCommand`（列已注册工具，当前 `ToolCatalog` 空→提示「工具注册归 20 节」）于 `sableos-cli/src/main/java/com/sableos/cli/command/ProviderListCommand.java` 与 `ToolListCommand.java`（不起 Spring）
- [x] T021 [P] [US4] `LightDbConfig`（轻命令 datasource 解析：读配置 url、`${ENV:default}` 占位、缺省 `jdbc:sqlite:${workspace.root:.sableos}/sableos.db`、SQLite 库文件缺失判定）+ 轻命令 `SessionListCommand`（`LightDbConfig` 裸 JDBC `SELECT session_id, profile_name, status, last_active_at FROM sessions ORDER BY last_active_at DESC`，库未建提示"暂无会话"）于 `sableos-cli/src/main/java/com/sableos/cli/command/LightDbConfig.java` 与 `SessionListCommand.java`（对齐 oryxos，不起 Spring）
- [x] T022 [US4] 新增 `SableOsRuntime`（`@SpringBootApplication(scanBasePackages="com.sableos")` + `@EnableJpaRepositories(basePackages="com.sableos.storage")` + `@EntityScan(basePackages="com.sableos.storage")` + `@Bean CliChannel cliChannel(AgentService, SessionManager)`；`SessionManager`/`AgentService` 等 bean 由扫描 boot 既有 `@Configuration` 收编）于 `sableos-cli/src/main/java/com/sableos/cli/SableOsRuntime.java`（依赖 T009/T012；[contracts/cli-bootstrap.md](./contracts/cli-bootstrap.md)、research D-06）

**Checkpoint**: 12 命令树 + 聚合入口 + 装配根就位

---

## Phase 6: User Story 3 - boot 收敛 + fat JAR 主类 + 测试改指 (Priority: P3)

**Goal**: fat JAR 主类改指 `SableOsCli`；boot 应用类降为 vestigial（JPA 扫描唯一在 `SableOsRuntime`）；boot JPA 测试改指 `SableOsRuntime`

**Independent Test**: `mvn -pl sableos-boot test` 全绿（装配根迁移后 16/17 节 bean 仍齐、审计仍落库）

### Implementation for User Story 3

- [x] T023 [US3] 改 `sableos-boot/.../SableOsApplication.java`：**剥掉** `@EntityScan`/`@EnableJpaRepositories`，降为最小 `@SpringBootApplication(scanBasePackages="com.sableos")`（vestigial，对齐 oryxos `OryxOsApplication`；JPA 扫描唯一在 `SableOsRuntime`，避免重复注册）【SG#4：改 L16/L17 boot 应用类】
- [x] T024 [US3] `sableos-boot/pom.xml` 的 `spring-boot-maven-plugin` 配 `<mainClass>com.sableos.cli.SableOsCli</mainClass>`（fat JAR 主类由 `SableOsApplication` 改为 Picocli 根；boot 仍产 fat JAR，AGENTS.md 口径不变）【SG#1：改 fat JAR 主类归属，知会】
- [x] T025 [US3] boot 的 `@SpringBootTest` 改指 `classes=SableOsRuntime.class`（因 `SableOsApplication` 已剥 JPA 扫描）：`ReactAssemblyTest`、`ProviderAssemblyTest`、`ProviderSmokeIT`（及其它依赖 JPA/装配的 boot 测试）；核对 `ObservabilityConfigTest`/`TraceTaskDecoratorTest` 是否受影响；`mvn -q -pl sableos-boot test` 全绿（依赖 T022/T023）

**Checkpoint**: 轻重分流 + 聚合入口 + 主类归属成立；三种运行模式共享同一装配

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T026 全仓 `mvn spotless:apply`（Spotless 绑 validate，格式违规即挂）
- [x] T027 `mvn -q -B clean verify` 全绿（Spotless/Checkstyle/P3C-PMD/SpotBugs/FindSecBugs 全过）——**完成的定义**
- [x] T028 全局不变量自查（H4 + AGENTS.md §5.1/5.2）：`grep -rn '":"' --include=*.java` 确认 session id 仅 `SessionManager.sessionId` 一处拼接；grep 无明文 key；无 Reactor/`CompletableFuture`/自建线程池；无 Spring AI 自动工具执行路径；用户可控字符串入日志前 `replaceAll("[\\r\\n]","_")` 净化；未新增第二个 `TaskDecorator`；traceId 走 `TraceContext`/`TraceIds`
- [x] T029 前序全节回归：`mvn -q -pl sableos-core,sableos-storage,sableos-provider,sableos-boot test` 全绿（16/17 节测试不破）；整理 quickstart 人工项清单
- [x] T030 输出变更总结导读（改动点按模块 / 重点 review 清单 / 如何验证命令块 / 剩余人工项）——对话内输出，不另开文件

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1)** → **Foundational (P2)**：core 契约（T002/T003）阻塞 US2 实现。
- **US2 (P3 阶段)**：依赖 Foundational；唯一被 harness 判卷的地基，**先落**。T009 依赖 T002/T003/T006/T007；T010 依赖 T009；T011 依赖 T004–T010。
- **US1 (P4 阶段)**：`CliChannel`（T012）依赖 T002（`SessionManager` 契约）。
- **US4 (P5 阶段)**：T015 根命令依赖 T014/T016–T022；T016 `ChatCommand` 依赖 T012（`CliChannel`）+T022（`SableOsRuntime`）；T022 依赖 T009/T012。
- **US3 (P6 阶段)**：T023/T024 独立；T025 依赖 T022（`SableOsRuntime` 存在）+T023（`SableOsApplication` 已剥扫描）。
- **Polish (P7)**：依赖全部。

### 阶段顺序说明（偏离"严格优先级"的理由）

spec 优先级 US1=P1（头部 UX）、US2=P2、US3=P3、US4=P4；但**执行顺序按依赖**：US2(会话层)→US1(CliChannel)→US4(命令树+聚合入口+装配根)→US3(boot 收敛+主类+测试改指)。因 chat(US1) 需 `SessionManager`(US2) 且其命令外壳/自启动在 US4、boot 收敛在 US3；US2 又是唯一 harness 判卷的地基，故先落。每个 story 仍可独立验证。

### Within Each Story

- harness 测试（T004/T005）先写、先失败，再落实现（T006–T009）——harness 先行。
- 契约/实体（T002/T003/T006/T007/T008）先于服务（T009）；服务先于装配（T010/T022）；命令树 + 装配根（T015/T022）先于 boot 收敛（T023–T025）。

### Parallel Opportunities

- T002/T003（不同文件，core 契约）可并行。
- T004/T005（两个测试类）、T006/T007/T008（实体/仓储/迁移，不同文件）可并行。
- T012/T013、T016–T021（各命令类不同文件）可并行。

---

## Parallel Example: User Story 2

```bash
# harness 先行（两个测试类可并行写）：
Task: "SessionManagerTest in sableos-storage/src/test/java/com/sableos/storage/SessionManagerTest.java"
Task: "SessionRepositoryTest in sableos-storage/src/test/java/com/sableos/storage/SessionRepositoryTest.java"
# 实现（实体/仓储/迁移可并行，JpaSessionManager 依赖三者）：
Task: "SessionEntity.java"
Task: "SessionRepository.java"
Task: "V3__sessions.sql (sqlite + postgresql)"
```

---

## Implementation Strategy

### MVP First

会话层（US2）是本节 MVP 与唯一 harness 判卷地基：Setup → Foundational → US2（T004–T011）跑绿即得可独立验证的持久化会话层。随后 US1(CliChannel)→US4(命令树+聚合入口+装配根)→US3(boot 收敛+主类) 逐层把"终端里跟 Agent 说话"接通。

### Incremental Delivery

1. US2 绿 → 会话可跨重启持久化（harness 判卷）。
2. +US1 → chat 交互实现就位。
3. +US4 → 12 命令树 + `SableOsRuntime` 装配根 + chat 自启动。
4. +US3 → boot 收敛、fat JAR 主类=`SableOsCli`、boot 测试改指 → `java -jar ... chat` 端到端可跑。
5. Polish → `mvn clean verify` 全绿 + 不变量自查 + 前序回归。

---

## Notes

- [P] = 不同文件、无未完成依赖。
- 反作弊：不删断言、不 `@Disabled`、不放宽阈值；实现错修实现，认为测试错停下报告。
- 每个 checkpoint 可停下独立验证；本节不自动 commit/push（同步时机由用户定）。
- 避免 Java 18+ 语法形态（增强 switch `default ->`、record/pattern switch）；PMD 按 17 级解析。
