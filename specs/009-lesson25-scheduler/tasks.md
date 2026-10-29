---
description: "Task list for 第25节 定时任务调度"
---

# Tasks: 定时任务调度——第三种触发源

**Input**: Design documents from `specs/009-lesson25-scheduler/`

**Prerequisites**: plan.md（必需）、spec.md（用户故事）、research.md（D-01 ~ D-09）、data-model.md（无新表）、contracts/agent-scheduler.md

**Tests**: 课件「验收 harness」明确要求 `AgentSchedulerTest`，故测试任务为必需项；测试与实现同任务组落地（先写断言、再实现、红了当场修）。

**Organization**: 按用户故事分阶段；`AgentScheduler` 为单文件，故事内任务串行执行（无 `[P]` 冲突）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1 / US2 / US3）
- 每个任务给出精确文件路径

## Path Conventions

Maven 多模块：`sableos-core/`、`sableos-boot/`、`sableos-cli/`（见 plan.md 的 Project Structure）。

---

## Phase 1: Setup

**Purpose**: 让 `sableos-core` 能编译 Spring 调度 API

- [x] T001 在 `sableos-core/pom.xml` 增加 compile 作用域的 `org.springframework:spring-context`（不写 `<version>`，由 Boot BOM 管理；旁边留一行注释说明 §8.5 的归口依据）

---

## Phase 2: Foundational（阻塞所有用户故事）

**Purpose**: `AgentScheduler` 的骨架与协作方接入

- [x] T002 新建 `sableos-core/src/main/java/com/sableos/core/scheduler/AgentScheduler.java`：`final class`，字段 `TaskScheduler taskScheduler` / `ProfileRegistry profileRegistry` / `AgentService agentService` / `SessionManager sessionManager` / `ConcurrentMap<String, Lock> taskLocks`；四参构造（按 `PromptBuilder` 先例加 `@SuppressFBWarnings("EI_EXPOSE_REP2")` 及理由）；包级 `Lock lockFor(String taskId)`（`computeIfAbsent` + `ReentrantLock`）
- [x] T003 跑 `mvn -pl sableos-core -am spotless:apply compile`，确认骨架可编译且格式通过

**Checkpoint**: 依赖与骨架就位，三个用户故事可按序推进

---

## Phase 3: User Story 1 - 到点自动触发，走同一入口 (Priority: P1) 🎯 MVP

**Goal**: 启动注册全部 `Profile.schedules`（cron + 时区），触发时以固定三元组取会话并交给 `AgentService.process`

**Independent Test**: mock `TaskScheduler` 捕获注册参数（表达式 + 用 `nextExecution` 行为断言时区）；mock `SessionManager` 断言 `getOrCreate("scheduler","scheduler",profile)`；两次 `runOnce` 复用同一会话

### Tests for User Story 1

- [x] T004 [US1] 在 `sableos-core/src/test/java/com/sableos/core/scheduler/AgentSchedulerTest.java` 写 `registerAll_registersEveryScheduleWithCronAndZone`：注册两条配置 → 捕获两次 `(Runnable, Trigger)`；断言表达式等于配置；对固定 `lastActualExecution` 求 `nextExecution`，断言结果等于按 `Asia/Shanghai` 解释的时刻（同 cron 在 UTC 下相差 8 小时）
- [x] T005 [US1] 同文件写 `runOnce_usesSchedulerIdentityAndReusesSession`：`verify(sessionManager).getOrCreate(TraceContext.CHANNEL_SCHEDULER, "scheduler", PROFILE_NAME)`；两次 `runOnce` → `getOrCreate` 调用两次、`process` 两次且收到同一 `Session` 实例
- [x] T006 [US1] 同文件写 `registerAll_skipsOnlyInvalidSchedule`：两条配置（一条 cron 非法）→ 仍注册成功的那一条，且 `registerAll` 不抛异常（FR-010）

### Implementation for User Story 1

- [x] T007 [US1] `AgentScheduler.registerAll()`：遍历 `profileRegistry.all()` 与 `profile.schedules()`；逐条校验 `id` / `cron` / `zone` 非空白，构造 `new CronTrigger(cron, ZoneId.of(zone))`，注册 `TracedExecutors.wrapRoot(() -> runOnce(profile, sc), TraceContext.CHANNEL_SCHEDULER)`；单条失败记 error 并跳过，不中断循环
- [x] T008 [US1] `AgentScheduler.runOnce(Profile, ScheduleConfig)` 主路径：`sessionManager.getOrCreate(CHANNEL_SCHEDULER, SCHEDULER_USER_ID, profile.name())` 后 `agentService.process(session, sc.message())`
- [x] T009 [US1] 跑 `mvn -pl sableos-core test -Dtest=AgentSchedulerTest`，红则当场修

**Checkpoint**: 注册参数与会话身份可独立验证（MVP）

---

## Phase 4: User Story 2 - 上一次没跑完，本次跳过 (Priority: P2)

**Goal**: 按任务 id 的 `tryLock` 防重叠，拿不到即跳过并留 WARN

**Independent Test**: 测试先 `lockFor("task-1").lock()` 占住执行权 → `runOnce` → `verify(agentService, never()).process(any(), any())`；释放后再次 `runOnce` 能进入

### Tests for User Story 2

- [x] T010 [US2] `AgentSchedulerTest` 新增 `runOnce_whenPreviousStillRunning_skipsThisTrigger`（课件「上一次还没跑完_本次触发直接跳过」的英文名版，`@DisplayName` 保留课件原文）

### Implementation for User Story 2

- [x] T011 [US2] `runOnce` 开头改为 `Lock lock = lockFor(sc.id()); if (!lock.tryLock()) { LOG.warn(...); return; }`，并把主路径裹进 `try/finally { lock.unlock(); }`
- [x] T012 [US2] 跑 `mvn -pl sableos-core test -Dtest=AgentSchedulerTest`，红则当场修

**Checkpoint**: US1 + US2 均独立可验

---

## Phase 5: User Story 3 - 失败隔离与锁释放 (Priority: P3)

**Goal**: 异常不外抛、记 error、`finally` 释锁

**Independent Test**: `when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"))` → `assertDoesNotThrow`；再 `runOnce` → `verify(process, times(2))`

### Tests for User Story 3

- [x] T013 [US3] `AgentSchedulerTest` 新增 `runOnce_whenProcessThrows_doesNotRethrowAndReleasesLock`（课件「任务抛异常_不外抛且锁必须被释放」的英文名版，`@DisplayName` 保留原文）

### Implementation for User Story 3

- [x] T014 [US3] `runOnce` 加 `catch (Exception e)`：`LOG.error("定时任务执行失败：id={} reason={}", safe(sc.id()), safe(e.getMessage()), e)`；`safe()` 用 `replaceAll("[\\r\\n]", "_")` 字面形态
- [x] T015 [US3] 跑 `mvn -pl sableos-core test -Dtest=AgentSchedulerTest`，红则当场修

**Checkpoint**: 三个故事全部独立可验

---

## Phase 6: 装配与收尾

**Purpose**: 让 `serve` / `gateway` 真正常驻调度，并过全量门禁

- [x] T016 [P] 新建 `sableos-boot/src/main/java/com/sableos/boot/scheduler/SchedulerConfiguration.java`：`@Bean agentTaskScheduler()`（`ThreadPoolTaskScheduler`，线程名前缀 `sableos-scheduler-`、池大小 ≥ 2）+ `@Bean agentScheduler(...)` 注入四个协作方
- [x] T017 [P] 修改 `sableos-cli/src/main/java/com/sableos/cli/command/ServeCommand.java` 与 `GatewayCommand.java`：保留 `ConfigurableApplicationContext`，启动成功后调用 `context.getBean(AgentScheduler.class).registerAll()`；`ChatCommand` 不动
- [x] T018 跑 `mvn spotless:apply`，再跑 `mvn clean verify` 全绿（含 P3C / Checkstyle / SpotBugs / 全部单测）
- [x] T019 按「本节交付物」逐项 grep/ls 核对：`AgentScheduler`（`registerAll`/`runOnce`/`lockFor`）、`Profile.ScheduleConfig` 四字段、`AgentSchedulerTest` 四个关键用例、会话常量 `scheduler`、无新增表/接口/配置键

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup（Phase 1）**：无依赖，可立即开始
- **Foundational（Phase 2）**：依赖 T001；阻塞所有用户故事
- **User Story 1（Phase 3）**：依赖 T002
- **User Story 2（Phase 4）**：依赖 US1 的 `runOnce` 主路径（T008）
- **User Story 3（Phase 5）**：依赖 US2 的锁结构（T011）
- **装配与收尾（Phase 6）**：T016/T017 依赖 T008；T018/T019 依赖全部

### User Story Dependencies

- **US1（P1）**：独立可验（注册 + 会话身份）
- **US2（P2）**：在 US1 的 `runOnce` 上加锁；单独可验「锁被占即跳过」
- **US3（P3）**：在 US2 的 `try/finally` 上加异常处理；单独可验「不外抛 + 二进宫」

### Within Each User Story

- 先写测试断言 → 再实现 → 立刻跑该测试类 → 红了当场修
- 单文件（`AgentScheduler.java` / `AgentSchedulerTest.java`）内任务串行，不标 `[P]`

### Parallel Opportunities

- T016（boot 配置）与 T017（cli 命令）分属不同模块、互不依赖，可并行
- 其余任务共享同一文件或存在先后依赖，串行执行

---

## Implementation Strategy

### MVP First

1. 完成 Phase 1 + Phase 2
2. 完成 Phase 3（US1）→ 单独验证注册参数与会话身份
3. 继续 US2 → US3（同一文件增量，风险最低）
4. 最后做 Phase 6 装配与全量门禁

### Incremental Delivery

每个用户故事完成即跑 `AgentSchedulerTest`，保证三个坑（时区 / 重叠 / 失败隔离）逐条由测试钉住后再进入下一步；`mvn clean verify` 只在 Phase 6 收尾跑一次全量。

---

## Notes

- 测试方法名一律英文驼峰，课件中文方法名放进 `@DisplayName` 保留原文以便对号
- 不新增 `TaskDecorator` bean；不新增配置键；不改 `Profile` / 存储 / web
- `session_id` 不自行拼接，只调 `SessionManager.getOrCreate`（H4④）
- 提交前 `mvn spotless:apply`（Spotless 绑 validate）
