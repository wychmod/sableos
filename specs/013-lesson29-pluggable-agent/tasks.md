# Tasks: 插件化 Agent——一个目录定义一个会自己跑的 Agent

**Input**: Design documents from `specs/013-lesson29-pluggable-agent/`

**Prerequisites**: plan.md、spec.md、research.md（D-01~D-09）、data-model.md、contracts/（3 份）、quickstart.md

**Tests**: 课件「验收 harness」明确列出 6 个测试类，故测试任务为必需项；每个用户故事的测试先于对应实现落地，实现后当场跑红转绿。

**Organization**: 按用户故事分阶段。Phase 2 是三个被改造类的共同地基（`ProfileRegistry.remove`、`AgentLoader` 工具名单、`AgentScheduler` 句柄），先于所有故事完成。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1~US4）
- 每个任务给出精确文件路径

## Path Conventions

Maven 多模块：`sableos-core/`、`sableos-boot/`。本节只触及这两个模块。

---

## Phase 1: Setup

**Purpose**: 基线确认与示例 Agent 夹具；不新增第三方依赖。

- [x] T001 跑 `mvn -B clean verify` 确认基线全绿（当前 532 例），并确认本节零新增依赖（SnakeYAML / SLF4J / spring-context 均已在 core）
- [x] T002 在 `sableos-core/src/test/resources/agents/daily-reconcile/` 落示例 Agent 四件套：`AGENT.md`（frontmatter 不含 `notify_channels`）、`REFERENCE.md`、`skills/report-format/SKILL.md`、`scripts/reconcile.py`（内容按课件 §1.3–1.4，`skills/` 按软连接形态的组织方式见 research D-04）

---

## Phase 2: Foundational（阻塞所有用户故事）

**Purpose**: 三个被改造类的运行时能力；没有它，US1/US3/US4 都无法落地。

- [x] T003 [P] 在 `sableos-core/src/main/java/com/sableos/core/profile/ProfileRegistry.java` 补 `public boolean remove(String name)`：命中并移除返回 `true`，未命中 `false`；`get` 保持返回 `null` 的既有语义（research D-03，契约见 `contracts/profile-registry.md`）
- [x] T004 [P] 在 `sableos-core/src/main/java/com/sableos/core/agent/AgentLoader.java` 增加三参构造器 `AgentLoader(Path, Set<String> knownProviders, Set<String> knownTools)`；两参构造器委派为 `knownTools = Set.of()`，保持既有行为
- [x] T005 在 `AgentLoader` 增加 `Profile deriveProfile(Path agentDir) throws IOException`，读文件后委派既有 `deriveProfile(String, String)`（research D-07：运行期登记必须走同一入口）
- [x] T006 在 `AgentLoader` 增加未注册工具告警：派生完成后逐个比对 `profile.tools()` 与 `knownTools`，未命中记 WARN「Agent X 引用了未注册的能力: Y」，**不拒绝该 Agent**（FR-005、research D-02）
- [x] T007 在 `sableos-core/src/main/java/com/sableos/core/scheduler/AgentScheduler.java` 抽出 `public void registerProfile(Profile profile)`：循环体取自既有 `registerOne`，逐条 reconcile + schedule（契约见 `contracts/agent-scheduler.md`）
- [x] T008 在 `AgentScheduler` 增加字段 `ConcurrentMap<Long, ScheduledFuture<?>> scheduledTasks` 与查询 `public boolean hasScheduledTask(long scheduleId)`；`registerProfile` 中 `put` 后若旧值非空且引用不同则 `previous.cancel(false)`（FR-008/FR-009、research D-05/D-06）
- [x] T009 把 `registerAll()` 改为遍历 `profileRegistry.all()` 后逐条委派 `registerProfile(profile)`；`retireMissing` 逻辑保持在外层不变
- [x] T010 在 `sableos-boot/src/main/java/com/sableos/boot/agent/AgentConfiguration.java` 注入 `ToolRegistry`，取其全部工具名作为第三参传给 `AgentLoader`（research D-08：只传 `Set<String>`，core 不 import tool 模块类型）
- [x] T011 跑 `mvn -B -pl sableos-core,sableos-boot -am test`，确认既有用例（含 `AgentLoaderTest`、`AgentSchedulerTest`、`MockAgentE2ETest`、`ArchitectureTest`）不回归

**Checkpoint**: 三个类的运行时能力与装配就位；US1~US4 可以并行推进。

---

## Phase 3: User Story 1 - 丢一个目录就多一个会自己跑的 Agent (Priority: P1) 🎯 MVP

**Goal**: 扫描 → 派生 Profile → 注册 → 定时进调度；`schedules` 原样透传；未注册工具只告警。

**Independent Test**: 在临时目录放 N 个 Agent 目录，`loadAll()` 后注册表恰好 N 个；带 `schedules` 的在 `registerProfile` 后有句柄。

### Tests for User Story 1

- [x] T012 [P] [US1] 新建 `sableos-core/src/test/java/com/sableos/core/agent/DeriveProfileTest.java`：frontmatter 各字段正确映射到 `Profile`（name/description/identity/provider/tools/mcp_servers/channels/bootstrap/settings），且 **`schedules` 原样带进派生 Profile**（cron/zone/message 逐字相等）；方法名用英文，`@DisplayName` 保留课件原文
- [x] T013 [P] [US1] 新建 `sableos-core/src/test/java/com/sableos/core/agent/AgentScanRegisterTest.java`：扫 3 个目录得 3 个 Agent、不产生别的条目；根目录不存在返回空表不抛；普通文件被忽略
- [x] T014 [P] [US1] 扩展 `sableos-core/src/test/java/com/sableos/core/agent/AgentLoaderTest.java`：补「`tools` 含未注册能力 → WARN 但 Agent 仍注册」（用 `@TempDir` + logback `ListAppender` 断言告警且 `registry.get(...)` 非空）

### Implementation for User Story 1

- [x] T015 [US1] 修改 `AgentLoader.loadAll()`：内部改调 `deriveProfile(Path)`，保持「坏目录只记日志跳过、不阻断」语义不变
- [x] T016 [US1] 跑 `mvn -B -pl sableos-core -am test -Dtest='AgentLoaderTest,DeriveProfileTest,AgentScanRegisterTest'`，红转绿

**Checkpoint**: US1 在 core 层独立可验。

---

## Phase 4: User Story 2 - 非法 Agent 只废自己，不废整台机器 (Priority: P1)

**Goal**: 缺文件 / YAML 坏 / name 与目录名不符 / provider 未登记 —— 四类非法各留点名 ERROR，其余 Agent 全部注册，进程照常启动。

**Independent Test**: 临时目录混放合法与非法 Agent，`loadAll()` 不抛异常，合法者全在、非法者全不在，日志各自点名。

### Tests for User Story 2

- [x] T017 [US2] 扩展 `sableos-core/src/test/java/com/sableos/core/agent/AgentLoaderTest.java`：四类非法各一例，断言（a）该 Agent 未被注册、（b）日志含目录名与具体原因、（c）同批次合法 Agent 仍在
- [x] T018 [US2] 在 `sableos-core/src/test/java/com/sableos/core/agent/AgentScanRegisterTest.java` 补一例：3 个合法 + 2 个非法 → 注册表恰 3 个

### Implementation for User Story 2

- [x] T019 [US2] 校核 `AgentLoader` 的四类校验消息均已点名原因；缺失则补齐（既有实现已覆盖前三类，provider 未登记消息含 provider 名与 Agent 名）
- [x] T020 [US2] 跑 `mvn -B -pl sableos-core -am test -Dtest='AgentLoaderTest,AgentScanRegisterTest'`，红转绿

**Checkpoint**: US1 + US2 均独立可验。

---

## Phase 5: User Story 3 - 运行时新增的 Agent 立即可用 (Priority: P2)

**Goal**: `ProfileRegistry` 运行期增删查；`AgentScheduler.registerProfile` + 句柄查询；重复登记取消旧句柄；运行时与启动路径同异常同消息。

**Independent Test**: 运行中的注册表登记 → 立即可查；移除 → 不可查；非法声明经运行期入口与启动扫描抛同类型同消息异常；调度器登记后每条定时可查句柄；重复登记后句柄数为 1 且旧句柄被取消。

### Tests for User Story 3

- [x] T021 [P] [US3] 新建 `sableos-core/src/test/java/com/sableos/core/profile/ProfileRegistryRuntimeTest.java`：`register` 后 `get` 立即可见；`remove` 命中返回 `true`、再查为 `null`、重复 `remove` 返回 `false`；**非法声明经 `deriveProfile` 与启动扫描抛出的异常 `getClass()` 与 `getMessage()` 完全一致**
- [x] T022 [P] [US3] 新建 `sableos-core/src/test/java/com/sableos/core/scheduler/AgentSchedulerRegisterTest.java`：`registerProfile` 后 `hasScheduledTask(scheduleId)` 为 `true`；cron/zone 取自 `Profile.schedules`（用 `ArgumentCaptor<Trigger>` 断言语义或 `nextExecution` 行为）；**重复登记同一任务后句柄仅 1 个且旧 `ScheduledFuture.cancel` 被调用**（Mockito 捕获）

### Implementation for User Story 3

- [x] T023 [US3] 为 `ProfileRegistryRuntimeTest` 所需的「同异常同消息」提供可比较的入口：确认 `AgentLoader.deriveProfile(Path)` 与 `loadAll()` 对同一非法声明抛出同一 `IllegalArgumentException` 文本；若不一致则统一（改动落在 `AgentLoader`，不动 `loadAll` 的跳过语义）
- [x] T024 [US3] 跑 `mvn -B -pl sableos-core -am test -Dtest='ProfileRegistryRuntimeTest,AgentSchedulerRegisterTest'`，红转绿

**Checkpoint**: US3 独立可验，第 30 节的注销/更新有句柄可依。

---

## Phase 6: User Story 4 - 资源按需进上下文 (Priority: P2)

**Goal**: 正文进 system prompt；参考/子指令/脚本不预载；改正文即时生效。

**Independent Test**: 组装 system prompt 含正文、不含 `REFERENCE.md` 与 `scripts/` 内容；改正文后再次组装读到新内容。

### Tests for User Story 4

- [x] T025 [US4] 新建 `sableos-core/src/test/java/com/sableos/core/context/ProgressiveDisclosureTest.java`：用 `@TempDir` 造 Agent 目录（含 `AGENT.md` 正文、`REFERENCE.md`、`scripts/x.py`），断言 system prompt **含**正文、**不含**参考与脚本内容；改正文后再次 `systemPrompt` 立即读到新值；断言 `skills/` 下普通 `.md` 会抛越界异常（钉死 research D-04 的约束）
- [x] T026 [US4] 复用 T002 的示例夹具：用 `ContextLoader` 对 `src/test/resources/agents/daily-reconcile` 组装一次，断言正文进 prompt、`REFERENCE.md` 与 `scripts/reconcile.py` 不进

### Implementation for User Story 4

- [x] T027 [US4] 本节**不改** `ContextLoader`（research D-09）；如 T025/T026 暴露缺陷，先停下报告，不擅自改 17 节已冻结的实现
- [x] T028 [US4] 跑 `mvn -B -pl sableos-core -am test -Dtest=ProgressiveDisclosureTest`，红转绿

**Checkpoint**: 四个用户故事全部独立可验。

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T029 跑 `mvn spotless:apply`，再跑 `mvn -B clean verify` 全绿；贴关键输出
- [x] T030 逐条核对 H4 全局不变量：涉外 IO 首行过 `Sandbox.enforce`（本节无新增涉外 IO）；审计不建旁路；无明文 key；`session_id` 只在 core 单点拼接；无 Reactor / `CompletableFuture` / 自建线程池；无 Spring AI 自动工具执行
- [x] T031 逐条核对 AGENTS §5.2：traceId 显式传播（`registerProfile` 仍用 `TracedExecutors.wrapRoot`）、唯一 `TaskDecorator`、`TraceIds`、`synchronized` 内无阻塞（句柄表用 `ConcurrentHashMap`）、日志 CRLF 净化
- [x] T032 更新 `AGENTS.md` §10：记录本节落地口径（示例目录落位、`skills/` 软连接约束、未注册工具告警、`remove`/`registerProfile`/`hasScheduledTask` 三个新成员）
- [x] T033 写 `specs/013-lesson29-pluggable-agent/acceptance-report.md`：六项证据 + 课件交付物映射 + 课件偏差（research D-04 的 `skills/` 形态）+ 剩余人工项
- [x] T034 输出变更总结（改动点 / 重点 review 清单 / 如何验证），以 `git status --short` / `git diff --stat` 实测为准

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，T001 先跑，T002 可与 T001 并行
- **Foundational (Phase 2)**: 依赖 T002（示例夹具不阻塞，但 T006 的测试要用）；T003/T004 可并行，T005/T006 依赖 T004，T007 依赖既有 store，T008/T009 依赖 T007，T010 依赖 T004，T011 收口
- **US1 (Phase 3)**: 依赖 Phase 2
- **US2 (Phase 4)**: 依赖 US1（复用同一测试文件与加载路径）
- **US3 (Phase 5)**: 依赖 Phase 2；T022 与 US1 的 `AgentScheduler` 改动无关，可并行
- **US4 (Phase 6)**: 依赖 Phase 2；实际只依赖既有 `ContextLoader`，与 US1~US3 并行
- **Polish (Phase 7)**: 依赖全部故事

### Within Each Story

- 测试先写并确认红；实现后当场跑该模块测试，红转绿再进下一个任务。
- 一个类改完立刻跑该类相关测试，不攒到最后。

### Parallel Opportunities

- T003 / T004 可并行（两个不同类的独立成员）。
- T012 / T013 / T014 可并行（三个测试文件）。
- T021 / T022 可并行（profile 包与 scheduler 包）。
- T025 / T026 可并行（同一测试类的两个用例，可先后写）。
- US3 与 US4 在 Phase 2 完成后可与 US1/US2 并行。

---

## Parallel Example: Foundational

```bash
# 两个类的独立成员可并行：
Task: "在 ProfileRegistry 补 remove(String)"
Task: "在 AgentLoader 加三参构造器"
```

---

## Implementation Strategy

### MVP First

1. Phase 1 + Phase 2：三个类的运行时能力 + 装配。
2. Phase 3（US1）：扫描 → 派生 → 注册 → 定时进调度。
3. **STOP and VALIDATE**：跑 US1 测试，确认 N 个目录得 N 个 Agent。
4. Phase 4（US2）：非法目录隔离。
5. Phase 5（US3）：运行时注册 + 句柄。
6. Phase 6（US4）：渐进式披露守点。

### Notes

- 每个任务完成后跑对应模块测试；不攒到最后。
- 任何断言不得删除、`@Disabled` 或放宽阈值；实现错修实现。
- `ContextLoader` 与 `Profile` 记录字段已由前序节冻结，本节不改（如测试暴露缺陷先停下报告）。
- 示例 Agent 的 `skills/` 必须是软连接形态或指向公共 Skill 根的组织方式；普通 `.md` 会让 system prompt 构造抛越界异常（research D-04）。
