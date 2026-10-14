---
description: "Task list for 002-lesson17-react"
---

# Tasks: ReAct 循环——Agent 的核心循环引擎

**Input**: Design documents from `/specs/002-lesson17-react/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: 课件「验收 harness」明确要求五个测试类，故**测试为必需**（非可选）。每个组件任务按「实现 + 其 harness 测试一起落地」成对拆解。

**Organization**: 按 user story 分组，每个 story 可独立实现与验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属 user story（US1–US4）
- 每条任务含确切文件路径

## Path Conventions

Maven 多模块，模块内标准布局：`sableos-<module>/src/main/java/com/sableos/...` 与 `sableos-<module>/src/test/java/com/sableos/...`。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 依赖与迁移纪律核对（本节不新增第三方依赖）

- [x] T001 [P] 核对依赖与迁移口径：确认 `sableos-core/pom.xml` 已含 `jackson-databind`/`snakeyaml`/`slf4j-api`（本节零新增第三方依赖）；确认 `sableos-storage/src/main/resources/db/migration/README.md` 已预告 `tool_invocations`（17 节）与 `V2` 版本号口径

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 前向契约与共享类型（US1–US4 均依赖）；纯 core 类型，无行为分支

**⚠️ CRITICAL**: 所有 user story 均依赖本阶段完成

- [x] T002 [P] 新增 `MessageRole` 枚举（USER/ASSISTANT/TOOL）于 `sableos-core/src/main/java/com/sableos/core/session/MessageRole.java`
- [x] T003 [P] 新增 `SessionMessage` record + 静态工厂 `user/assistant/tool` 于 `sableos-core/src/main/java/com/sableos/core/session/SessionMessage.java`（字段：role/content/toolCalls/toolName/success，见 [data-model.md](./data-model.md) §2）
- [x] T004 [P] 新增 `Session` 可变聚合于 `sableos-core/src/main/java/com/sableos/core/session/Session.java`（字段 id/profileName/channel/userId/messages/status/createdAt/lastActiveAt；方法 `appendUserMessage/appendAssistantMessage/appendToolResult/messages`，规约见 [contracts/runtime-contracts.md](./contracts/runtime-contracts.md) §1）
- [x] T005 [P] 新增 `SessionStore` 接口（`void save(Session)`）于 `sableos-core/src/main/java/com/sableos/core/session/SessionStore.java`
- [x] T006 [P] 新增 `ToolCatalog` 接口（`List<SableTool> toolsFor(Profile)` / `SableTool find(String)`，未找到返回 null）于 `sableos-core/src/main/java/com/sableos/core/tool/ToolCatalog.java`
- [x] T007 [P] 新增 `ToolInvocationAuditor` 接口（七参 `record(sessionId,toolName,inputJson,resultJson,success,errorMessage,durationMs)`）于 `sableos-core/src/main/java/com/sableos/core/tool/ToolInvocationAuditor.java`
- [x] T008 [P] 新增 `MemoryProvider` 接口（`String memorySection(Profile)`，无记忆返回 null）于 `sableos-core/src/main/java/com/sableos/core/memory/MemoryProvider.java`
- [x] T009 [P] 新增 `ProfileContext` 静态工具类（`ThreadLocal<Profile>`：`set/current/clear`，私有构造）于 `sableos-core/src/main/java/com/sableos/core/profile/ProfileContext.java`

**Checkpoint**: 前向契约就位——user story 实现可开始

---

## Phase 3: User Story 1 - 自主多轮循环 (Priority: P1) 🎯 MVP

**Goal**: 组装上下文 → 调模型 → 有工具调用则执行并回填 → 无则收尾；工具执行权唯一在 `ToolExecutor`

**Independent Test**: 用可控模型桩（第一轮要求调工具、第二轮返回文本），断言工具执行一次、结果进入下一轮可见、最终答复 = 第二轮文本

### Implementation for User Story 1

- [x] T010 [P] [US1] 实现 `ContextLoader`（构造 `(Path agentsDir, Path bootstrapDir, Path skillsRoot)`，方法 `String systemPrompt(Profile)`；读 AGENT.md 正文 + identity.prompt + bootstrap 文件 + skills 软连接扫描，每次现读不缓存；自持 `---` 围栏剥离，不调 `AgentLoader` 私有方法）于 `sableos-core/src/main/java/com/sableos/core/context/ContextLoader.java`，并落地 `ContextLoaderTest`（加载正文/identity/bootstrap/skills 基本路径）于 `sableos-core/src/test/java/com/sableos/core/context/ContextLoaderTest.java`
- [x] T011 [US1] 实现 `PromptBuilder`（构造 `(ContextLoader, ToolCatalog, MemoryProvider)`，方法 `ProviderRequest build(Session, Profile)`；固定顺序组装 system prompt + 记忆段 + 最近 N 条历史为一段 content；`availableTools = toolCatalog.toolsFor(profile)`；末尾追加当前日期时间）于 `sableos-core/src/main/java/com/sableos/core/prompt/PromptBuilder.java`，并落地 `PromptBuilderTest`（组装顺序、记忆段跳过、末尾含当前日期时间）于 `sableos-core/src/test/java/com/sableos/core/prompt/PromptBuilderTest.java`（依赖 T010）
- [x] T012 [P] [US1] 实现 `ToolExecutor`（构造 `(ToolCatalog, ToolInvocationAuditor)`，方法 `ToolResult execute(String sessionId, ToolCallRequest call)`；按名解析 → **留白名单校验调用位 + `// TODO(24 节)`** → Jackson 解析入参 → try/catch 执行 → 成败两态审计；任何路径返回 `ToolResult` 不上抛）于 `sableos-core/src/main/java/com/sableos/core/tool/ToolExecutor.java`，并落地 `ToolExecutorTest`（成功执行并包装结果、未注册工具名失败不抛、入参非法 JSON 转可重试失败）于 `sableos-core/src/test/java/com/sableos/core/tool/ToolExecutorTest.java`
- [x] T013 [US1] 实现 `ReActLoop`（构造 `(ProviderService, PromptBuilder, ToolExecutor)`，方法 `String run(Session, String, Profile)`；逐条照 [contracts/react-loop.md](./contracts/react-loop.md) §2 骨架）于 `sableos-core/src/main/java/com/sableos/core/react/ReActLoop.java`，并落地 `ReActLoopTest`（无工具调用即返回该轮文本；工具结果回填、下一轮可见；一轮多工具顺序执行）于 `sableos-core/src/test/java/com/sableos/core/react/ReActLoopTest.java`（依赖 T011、T012）

**Checkpoint**: US1 可独立跑通——循环 MVP 成立

---

## Phase 4: User Story 2 - 循环可控 (Priority: P2)

**Goal**: 迭代上限（默认 10、Profile 可覆盖）转满强制收尾；历史只留最近 N 条（默认 20）

**Independent Test**: 模型桩每轮都要求调工具 → 恰好 `maxIterations` 次后停并返回固定文案；超长历史 → 进入模型的内容只含最近 N 条

### Implementation for User Story 2

- [x] T014 [US2] 在 `ReActLoopTest` 增补**关键回归**（课件原样落地）：`run_modelAlwaysRequestsTools_stopsExactlyAtMaxIterations`（断言模型调用次数恰好 = `maxIterations`，返回含「达到最大轮数」的固定文案，且不拼接最后一条输出）与 `run_profileOverridesMaxIterations_usesDeclaredValue`；`@DisplayName` 保留课件中文于 `sableos-core/src/test/java/com/sableos/core/react/ReActLoopTest.java`
- [x] T015 [US2] 在 `PromptBuilderTest` 增补**关键回归**：`build_historyExceedsLimit_keepsOnlyLatestNMessages`（构造 > N 条消息，断言 content 只含最近 N 条、system prompt 仍在、当前用户消息不丢，默认 20 与 Profile 覆盖两例）；`@DisplayName` 保留课件中文于 `sableos-core/src/test/java/com/sableos/core/prompt/PromptBuilderTest.java`

**Checkpoint**: US2 成立——循环不会死循环、上下文不无限膨胀

---

## Phase 5: User Story 3 - 过程留痕可审计 (Priority: P3)

**Goal**: 每轮痕迹进会话（US1 已覆盖）；每次工具调用成败两态落 `tool_invocations`

**Independent Test**: 分别驱动成功/失败工具调用，断言审计两条都在、失败那条带原因；真 Flyway 建表可存可读

### Implementation for User Story 3

- [x] T016 [P] [US3] 新增 `ToolInvocation` JPA 实体（`@Entity @Table(name="tool_invocations")`，列逐列对齐 [contracts/tool-invocations-schema.md](./contracts/tool-invocations-schema.md) §5，`@PrePersist` 补 `createdAt`）于 `sableos-storage/src/main/java/com/sableos/storage/ToolInvocation.java` 与 `ToolInvocationRepository`（`extends JpaRepository<ToolInvocation, Long>`）于 `sableos-storage/src/main/java/com/sableos/storage/ToolInvocationRepository.java`
- [x] T017 [P] [US3] 新增双轨迁移脚本 `sqlite/V2__tool_invocations.sql` 与 `postgresql/V2__tool_invocations.sql`（1:1，DDL 见 [contracts/tool-invocations-schema.md](./contracts/tool-invocations-schema.md) §1/§2，含 `idx_tool_invocations_session_id`、`idx_tool_invocations_trace_id`）于 `sableos-storage/src/main/resources/db/migration/{sqlite,postgresql}/V2__tool_invocations.sql`
- [x] T018 [US3] 实现 `JpaToolInvocationAuditor`（面向 `ToolInvocationAuditor`；`trace_id` 取 `TraceContext.traceId()`；写库失败 fail-closed 抛 `IllegalStateException`；日志 CRLF 净化保持 `replaceAll("[\\r\\n]", "_")` 字面形态）于 `sableos-storage/src/main/java/com/sableos/storage/JpaToolInvocationAuditor.java`（依赖 T016）
- [x] T019 [US3] 在 `ToolExecutorTest` 增补**关键回归**（课件原样落地）：`execute_success_auditsSuccessTrue`、`execute_toolThrows_auditsFailureWithReason`（断言 `success=false` 且 `errorMessage` 含原因、异常不抛）；于 `sableos-core/src/test/java/com/sableos/core/tool/ToolExecutorTest.java`
- [x] T020 [US3] 落地 `ToolInvocationRepositoryTest`（`@SpringBootTest`，临时 SQLite + 真 Flyway；成败两态往返、`trace_id` 跟随链路上下文、`PRAGMA table_info(tool_invocations)` 核对 required 列、`flyway_schema_history` 含 version='1' 与 '2'）于 `sableos-storage/src/test/java/com/sableos/storage/ToolInvocationRepositoryTest.java`（依赖 T017、T018）

**Checkpoint**: US3 成立——审计 day one 落库（原则 VI）

---

## Phase 6: User Story 4 - 统一编排与上下文来源 (Priority: P4)

**Goal**: `AgentService` 统一入口（set/clear `ProfileContext`、保存会话）；上下文每次现读、缺失不静默

**Independent Test**: 处理中 `ProfileContext.current()` 可取到 Profile；抛异常后已清空；正常结束后会话被保存；改文件后下次组装立即读新内容；Skill 缺失报错、Bootstrap 缺失 WARN

### Implementation for User Story 4

- [x] T021 [P] [US4] 实现 `InMemorySessionStore`（`implements SessionStore`，`Map<String, Session>`）于 `sableos-storage/src/main/java/com/sableos/storage/InMemorySessionStore.java`
- [x] T022 [US4] 实现 `AgentService`（构造 `(ProfileRegistry, ReActLoop, SessionStore)`，方法 `String process(Session, String)`；取 Profile → `ProfileContext.set` → `TraceContext.openIfAbsent()` + `attachBusiness` → 跑循环 → `sessionStore.save` → `finally ProfileContext.clear`；Profile 未命中抛 `IllegalArgumentException` 点名）于 `sableos-core/src/main/java/com/sableos/core/agent/AgentService.java`，并落地 `AgentServiceTest`（**关键回归**：`process_duringRun_profileContextHoldsCurrentProfile`、`process_loopThrows_contextStillCleared`〔课件原样落地〕、`process_afterLoop_savesSession`）于 `sableos-core/src/test/java/com/sableos/core/agent/AgentServiceTest.java`（依赖 T021）
- [x] T023 [US4] 在 `ContextLoaderTest` 增补**关键回归**：`systemPrompt_fileChanged_reflectsNewContentImmediately`（改文件后下次组装立即读到新内容、无缓存）、`systemPrompt_missingSkillBinding_throwsNamed`、`systemPrompt_missingBootstrap_logsWarn`（捕获日志或校验 WARN 分支）；`@DisplayName` 保留课件中文于 `sableos-core/src/test/java/com/sableos/core/context/ContextLoaderTest.java`
- [x] T024 [US4] 新增 boot 装配 `ReactConfiguration`（`@Configuration`；bean：`ToolInvocationAuditor`=JpaToolInvocationAuditor、`InMemorySessionStore`、`ToolCatalog`=**包内私有空实现**、`MemoryProvider`=返回 null 的 lambda、`ContextLoader`、`PromptBuilder`、`ToolExecutor`、`ReActLoop`、`AgentService`；**不新增第二个 `TaskDecorator`**）于 `sableos-boot/src/main/java/com/sableos/boot/react/ReactConfiguration.java`，并落地 `ReactAssemblyTest`（零 key 装配可升起、9 个 bean 齐备、空 ToolCatalog 生效）于 `sableos-boot/src/test/java/com/sableos/boot/ReactAssemblyTest.java`

**Checkpoint**: US4 成立——三触发源共用入口与上下文来源治理就位

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 门禁与留证

- [x] T025 运行 `mvn -q spotless:apply` 后 `mvn clean verify`，确认 Spotless/Checkstyle/P3C/SpotBugs(+FindSecBugs) 与全部测试全绿（硬门禁）
- [x] T026 H4 全局不变量自查：①涉外 IO 首行留白名单校验位（本节点名 24 节接线）；②`llm_calls`（16 节）与 `tool_invocations` 成败两态都写入且含 `trace_id`；③`grep -rn "sk-"` 零命中；④`session_id` 单点来源一致；⑤无 Reactor/`CompletableFuture`/自建线程池；⑥无 Spring AI 自动工具执行路径；另核对 AGENTS.md §5.1/§5.2（`TraceIds` 非 `UUID.randomUUID()`、`synchronized` 内无阻塞、无第二个 `TaskDecorator`、CRLF 净化形态）
- [x] T027 按 [quickstart.md](./quickstart.md) 执行场景 A–D 并留证（单测全绿、审计表存取、零 key 装配、本地起服务）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖
- **Foundational (Phase 2)**: 依赖 Setup——**阻塞全部 user story**
- **US1 (Phase 3)**: 依赖 Foundational——MVP
- **US2 (Phase 4)**: 依赖 US1（增补 US1 已建组件的回归测试）
- **US3 (Phase 5)**: 依赖 Foundational（T016–T018 独立于 US1）；T019 依赖 US1 的 T012；T020 依赖 T016–T018
- **US4 (Phase 6)**: 依赖 US1（T022 用 `ReActLoop`）；T021 独立；T024 依赖 T016–T018/T021/T022
- **Polish (Phase 7)**: 依赖全部

### Within Each User Story

- 实现与其 harness 测试同任务落地（任务级 DoD）；模块测试当下跑绿再进下一条

### Parallel Opportunities

- Phase 2 全部任务可并行（T002–T009 不同文件）
- US1：T010 与 T012 可并行；T011 依赖 T010；T013 依赖 T011/T012
- US3：T016 与 T017 可并行；T018 依赖 T016；T020 依赖 T017/T018
- US4：T021 可与 US1 并行

---

## Parallel Example: User Story 1

```text
# 可并行：
Task: "T010 [P] [US1] ContextLoader + ContextLoaderTest"
Task: "T012 [P] [US1] ToolExecutor + ToolExecutorTest"

# 随后（依赖上述）：
Task: "T011 [US1] PromptBuilder + PromptBuilderTest"   # 依赖 T010
Task: "T013 [US1] ReActLoop + ReActLoopTest"           # 依赖 T011, T012
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 + Phase 2（foundational 阻塞项）
2. Phase 3（US1）→ **停下独立验证**：循环跑通
3. 再依次 US2 / US3 / US4（每个 story 独立可验）

### Incremental Delivery

1. Foundational → 契约就位
2. US1 → 循环可跑（MVP）
3. US2 → 兜底与截断
4. US3 → 审计落库
5. US4 → 统一入口与装配

---

## Notes

- [P] = 不同文件、无未完成依赖
- 测试为必需（课件 harness 五类）；关键回归断言逐条保真，方法名英文、课件中文进 `@DisplayName`
- core 保持框架无关；不新增第三方依赖；异常不吞（原因留痕）
- 不自动 commit / push / 运行 package.sh
