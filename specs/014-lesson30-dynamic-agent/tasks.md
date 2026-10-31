# Tasks: 动态管理 Agent——上传即上线、可生成、可浏览

**Input**: Design documents from `specs/014-lesson30-dynamic-agent/`

**Prerequisites**: plan.md、spec.md、research.md（D-01~D-16）、data-model.md、contracts/（2 份）、quickstart.md

**Tests**: 课件「验收 harness」列出 5 个测试类（`AgentLifecycleServiceTest` / `WorkspaceWatcherTest` / `WorkspaceApiControllerTest` / `AgentApiControllerTest` / `GenerateTest`），故测试任务为必需项，先于或伴随对应实现落地。

**Organization**: Phase 2 是五个用户故事的共同地基（`AgentStore` + `ToolExecutionContext` + `AgentScheduler.unregisterProfile` + `AgentLoader.parse`）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- 每个任务给出精确文件路径

## Path Conventions

Maven 多模块：`sableos-core/`、`sableos-memory/`、`sableos-web/`、`sableos-boot/`。本节不新增模块。

---

## Phase 1: Setup

- [ ] T001 确认基线全绿（`mvn -B clean verify`，已于开工前跑过 exit 0）并确认本节零新增第三方依赖（SnakeYAML / spring-context 均已在 core）
- [ ] T002 [P] 新增配置键 `sableos.author.provider` / `sableos.author.model` 到 `sableos-boot/src/main/resources/application.yaml`（留空默认，注释说明缺省与 503 语义）

---

## Phase 2: Foundational（阻塞所有用户故事）

- [ ] T003 [P] 新建 `sableos-core/src/main/java/com/sableos/core/agent/AgentStore.java`：`writeAll` / `delete` / `archive` / `read` / `readAll` / `resolveWithin` / `isValidName` / `exists` / `root` / `agentsDir` / `archiveDir`（契约见 `contracts/lifecycle-store-watcher.md` §1）
- [ ] T004 [P] 新建 `sableos-core/src/main/java/com/sableos/core/agent/ToolExecutionContext.java`：`setAgentName` / `agentName` / `clear`（`ThreadLocal`，非继承）
- [ ] T005 [P] 修改 `sableos-core/src/main/java/com/sableos/core/scheduler/AgentScheduler.java`：新增 `public void unregisterProfile(Profile)`（按 Profile 名筛 `registeredTasks`，取消句柄 + `retire`，不动锁）
- [ ] T006 [P] 修改 `sableos-core/src/main/java/com/sableos/core/agent/AgentLoader.java`：新增 `public Profile parse(String agentMarkdown, String expectedName)`，委派既有包级 `deriveProfile(String, String)`
- [ ] T007 [P] 修改 `sableos-core/src/main/java/com/sableos/core/tool/ToolExecutor.java`：`tool.execute(input)` 前 `ToolExecutionContext.setAgentName(ProfileContext.current()?.name())`，`finally` 中 `clear()`
- [ ] T008 跑 `mvn -B -pl sableos-core test -Dtest='AgentLoaderTest,AgentSchedulerTest,AgentSchedulerRegisterTest'`，确认既有用例不回归

**Checkpoint**: 地基就位；US1~US5 可并行推进。

---

## Phase 3: User Story 1 - 一句话描述就造出一个会自己跑的 Agent (Priority: P1) 🎯 MVP

**Goal**: `POST /agents {name, description}` 按模板脚手架出完整目录 + 派生注册；失败回滚；name 冲突第一步拒。

**Independent Test**: 临时工作区调 `create`，磁盘出现 `AGENT.md`/`REFERENCE.md`/`scripts/example.py`，注册表可查，带 schedules 的进调度器；同名再建 400 且零文件新增；注册失败目录被删回。

### Tests for User Story 1

- [ ] T009 [P] [US1] 新建 `sableos-core/src/test/java/com/sableos/core/agent/AgentLifecycleServiceTest.java`：覆盖
  - `create` 按序执行（`InOrder`：`agentStore.writeAll` → `profileRegistry.register` → `agentScheduler.registerProfile`）
  - **name 冲突在第一步就拒、一个目录都不写**（`verify(agentStore, never()).writeAll(any(), any())`）
  - **注册失败回滚已写目录**（`doThrow` on `register` → `verify(agentStore).delete(any())` + `verify(agentScheduler, never()).registerProfile(any())` + `assertFalse(registry.exists("half"))`）
  - **create 与 watcher 走同一段 `register(Path)`**（`create` 后 `refresh` 同一目录，两者产生同一 `Profile` 与同一注册语义）
  - `delete` 按「注销定时 → 移出索引 → 归档」顺序（`InOrder`）
  - `update` 改 schedules 时先 `unregisterProfile` 后 `registerProfile`
  - 方法名英文 + `@DisplayName` 保留课件原文
- [ ] T010 [P] [US1] 新建 `sableos-core/src/test/java/com/sableos/core/agent/AgentStoreTest.java`：`writeAll` 防穿越（`../` 路径 400）、`archive` 同名不覆盖（追加时间戳）、`delete` 幂等、`isValidName` 边界

### Implementation for User Story 1

- [ ] T011 [US1] 新建 `sableos-core/src/main/java/com/sableos/core/agent/AgentLifecycleService.java`（`generate` 留到 US3 实现，本任务先落 `create`/`register`/`refresh`/`delete`/`get`/`list`/`update`/`saveFiles` 骨架与脚手架模板常量）
- [ ] T012 [US1] 在 `AgentConfiguration` 装配 `AgentStore` 与 `AgentLifecycleService` 两个 bean（注入 `ProviderService`、`@Value("${sableos.author.provider:}")`、`@Value("${sableos.author.model:}")`、`@Value("${sableos.providers[0].name:}")` 兜底）
- [ ] T013 [US1] 扩展 `sableos-web/src/main/java/com/sableos/web/api/AgentApiController.java`：`POST /agents`、`GET /agents`、`GET /agents/{name}`、`PUT /agents/{name}`、`DELETE /agents/{name}`；新增 DTO `AgentView` / `AgentDetailView` / `CreateAgentRequest` / `UpdateAgentRequest` / `FileNode`
- [ ] T014 [US1] 跑 `mvn -B -pl sableos-core,sableos-web -am test -Dtest='AgentLifecycleServiceTest,AgentStoreTest'`，红转绿

**Checkpoint**: US1 独立可验。

---

## Phase 4: User Story 2 - 手工丢一个目录也即上线，删掉即下线 (Priority: P1)

**Goal**: `WorkspaceWatcher` 启动全量挂载 + 实时监听；事件走同一段 `register` / `unregisterByDir`；坏目录不拖垮。

**Independent Test**: 临时目录起监听，拷贝合法目录 → 注册表出现；删目录 → 消失；坏目录 → 只 WARN，其余不受影响。

### Tests for User Story 2

- [ ] T015 [P] [US2] 新建 `sableos-core/src/test/java/com/sableos/core/agent/WorkspaceWatcherTest.java`：直接调包级 `handleChange` / `dispatch`
  - 往 `agents/` 手工丢一个 Agent 目录 → 监听事件触发 `refresh`、Agent 出现在 `ProfileRegistry`（免重启）
  - 删目录 → `unregisterByDir` 被调用
  - 单个坏目录不拖垮监听（`refresh` 抛异常 → 只 WARN，后续事件仍正常）
  - **端到端一次**：真实 `WatchService` + `@TempDir`，拷贝目录后轮询等待注册表出现（带超时上限）
- [ ] T016 [P] [US2] 扩展 `sableos-core/src/test/java/com/sableos/core/scheduler/AgentSchedulerRegisterTest.java`：`unregisterProfile` 后 `hasScheduledTask(scheduleId)` 为 false，且 `scheduledTaskStore.retire` 被调用；未注册 Agent 调用幂等

### Implementation for User Story 2

- [ ] T017 [US2] 新建 `sableos-core/src/main/java/com/sableos/core/agent/WorkspaceWatcher.java`（`start`/`stop`/`dispatch`/`handleChange`，根目录 + 子目录双层 watch）
- [ ] T018 [US2] 在 `AgentConfiguration` 装配 `workspaceWatcherExecutor`（单守护线程 `ThreadPoolTaskExecutor`）+ `WorkspaceWatcher`（`@Bean(initMethod="start")`）+ `ContextClosedEvent` 监听器调 `stop()`
- [ ] T019 [US2] 跑 `mvn -B -pl sableos-core -am test -Dtest='WorkspaceWatcherTest,AgentSchedulerRegisterTest'`，红转绿并确认无悬挂线程

**Checkpoint**: US1 + US2 均独立可验（两条录入路径殊途同归）。

---

## Phase 5: User Story 3 - 管理台看得到、改得动、删得掉 (Priority: P2)

**Goal**: `generate-files` / `files` / 固定会话两端点；前端 Agent 列表 + 五页签。

**Independent Test**: 生成草稿不落盘不注册、非法 400、model 未配置 503；保存后即生效；固定会话发消息落进 `admin:console:<name>`。

### Tests for User Story 3

- [ ] T020 [P] [US3] 新建 `sableos-core/src/test/java/com/sableos/core/agent/GenerateTest.java`：`generateFiles` 产出能被 `AgentLoader.parse` 解析的 `AGENT.md` 草稿；**只生成、不落盘、不注册**（`verify(agentStore, never()).writeAll(any(), any())` + `verify(profileRegistry, never()).register(any())`）；模型产出非法 → 400 可读原因；`authorModel` 空 → `ProviderUnavailableException`
- [ ] T021 [P] [US3] 扩展 `sableos-core/src/test/java/com/sableos/core/agent/AgentLifecycleServiceTest.java`：`saveFiles` 预校验非法不写坏目录；`update` 覆写后重注册

### Implementation for User Story 3

- [ ] T022 [US3] 在 `AgentLifecycleService` 实现 `generate` / `generateFiles`（`AGENT_AUTHOR_PROMPT` 常量、`===FILE:===` 解析、代码围栏剥离、路径校验、`parse` 校验、已有 Agent 的 provider 沿用）
- [ ] T023 [US3] 扩展 `AgentApiController`：`POST /agents/{name}/generate-files`、`POST /agents/{name}/files`、`GET /agents/{name}/session`、`POST /agents/{name}/session/messages`；新增 DTO `GenerateFilesRequest` / `SaveFilesRequest` / `AgentSessionMessageRequest`；`ApiConstraints` 加 `ADMIN_CHANNEL="admin"` / `ADMIN_USER="console"`
- [ ] T024 [P] [US3] 重写 `sableos-web/src/main/frontend/src/pages/AgentsPage.vue`（列表 + 新建 dialog）
- [ ] T025 [US3] 新建 `sableos-web/src/main/frontend/src/pages/AgentDetailPage.vue`（五页签：基本信息 / 生成 / 文件 / 会话 / 记忆）；更新 `router/index.ts`、`api/endpoints.ts`、`types/api.ts`
- [ ] T026 [US3] 跑 `mvn -B -pl sableos-core -am test -Dtest='GenerateTest,AgentLifecycleServiceTest'`，红转绿

**Checkpoint**: 后端 US3 完成。

---

## Phase 6: User Story 4 - 记忆跟着 Agent 走 (Priority: P2)

**Goal**: `agents/<name>/MEMORY.md`；无上下文回退全局；移除全局 `GET /memory`，新增 `GET /agents/{name}/memory`。

**Independent Test**: 两 Agent 记忆互不可见；无上下文回退既有路径；旧调用不抛。

### Tests for User Story 4

- [ ] T027 [P] [US4] 扩展 `sableos-memory/src/test/java/com/sableos/memory/store/MarkdownMemoryStoreTest.java`：有 `ToolExecutionContext` → 落 `agents/<name>/MEMORY.md`；无上下文 → 落 `memory/<profile>/MEMORY.md`（既有行为）；非法 Agent 名（含 `..`）→ 回退全局
- [ ] T028 [P] [US4] 扩展 `sableos-core/src/test/java/com/sableos/core/tool/ToolExecutorTest.java`：执行工具期间 `ToolExecutionContext.agentName()` 为当前 Profile 名，执行后为 null

### Implementation for User Story 4

- [ ] T029 [US4] 修改 `sableos-memory/src/main/java/com/sableos/memory/store/MarkdownMemoryStore.java`：`memoryFile` 按 `ToolExecutionContext.agentName()` 解析（含 `SAFE_AGENT` 校验与回退）
- [ ] T030 [US4] 修改 `sableos-memory/src/main/java/com/sableos/memory/MemoryServiceImpl.java`：`buildContext` / `memorySection` 委托 store 前后置入/复原 Agent 上下文
- [ ] T031 [US4] 删除 `sableos-web/src/main/java/com/sableos/web/api/MemoryApiController.java`；在 `AgentApiController` 新增 `GET /agents/{name}/memory`
- [ ] T032 [US4] 修正因移除全局端点而受影响的既有测试：`MockAgentE2ETest`、`ScheduledTaskE2ETest`、`WebSmokeIT`、`RestartRecoveryIT`、`HumanTriggerFlowIT`、`MockProviderFlowTest`（改为 `/agents/{name}/memory` 或直接读文件）
- [ ] T033 [US4] 前端 `MemoryPage.vue` + `endpoints.ts` 改为按 Agent 查询（或并入详情页「记忆」页签后删除独立页）
- [ ] T034 [US4] 跑 `mvn -B -pl sableos-memory,sableos-core -am test -Dtest='MarkdownMemoryStoreTest,ToolExecutorTest,MemoryServiceImplTest,MemoryStoreContractTest'`，红转绿

**Checkpoint**: US4 完成，且既有记忆契约测试零改动通过。

---

## Phase 7: User Story 5 - 文件浏览器能安全地读、按需地写 (Priority: P3)

**Goal**: `WorkspaceApiController` 的 tree / file(GET) / file(POST)，防目录穿越。

**Independent Test**: tree 返回 agents/ + archive/；读正常文件；`../../etc/passwd` 等越界串全 400。

### Tests for User Story 5

- [ ] T035 [P] [US5] 新建 `sableos-web/src/test/java/com/sableos/web/api/WorkspaceApiControllerTest.java`（`MockMvcBuilders.standaloneSetup`）
  - tree 返回 agents/archive 结构、可钻进 Agent 目录列文件
  - **`file?path=../../etc/passwd` 目录穿越 → 400**（关键回归）
  - 绝对路径、编码穿越（`%2e%2e%2f`）→ 400
  - 正常文件返回内容
  - 写 `agents/<name>/AGENT.md` 走 `AgentLifecycleService.saveFiles`（`verify`）；其它文件直写
- [ ] T036 [P] [US5] 新建 `sableos-web/src/test/java/com/sableos/web/api/AgentApiControllerTest.java`（`standaloneSetup`）：端点薄转发；冲突 400、不存在 404，统一 `ApiResponse`；`generate-files` 的 503 透传

### Implementation for User Story 5

- [ ] T037 [US5] 新建 `sableos-web/src/main/java/com/sableos/web/api/WorkspaceApiController.java`（tree / file GET / file POST）；新增 DTO `WorkspaceWriteRequest`
- [ ] T038 [US5] 跑 `mvn -B -pl sableos-web -am test -Dtest='WorkspaceApiControllerTest,AgentApiControllerTest'`，红转绿

**Checkpoint**: 五个 US 全部独立可验。

---

## Phase 8: Polish & Cross-Cutting

- [ ] T039 前端构建：`cd sableos-web/src/main/frontend && npm ci && npm run build`，产物入库
- [ ] T040 文档同步：《技术方案》§11.3 补记本次实现偏差（D-16）与 §7.2 端点表；`README.md` 端点表
- [ ] T041 `AGENTS.md` 第 10 章补记本节落地事实（13 行端点、per-agent 记忆、监听器、配置键）
- [ ] T042 全量 `mvn -B clean verify`，贴关键输出
- [ ] T043 H4 全局不变量自查（6 项）+ AGENTS §5.1/5.2 专项自查
- [ ] T044 节级验收报告 `specs/014-lesson30-dynamic-agent/acceptance-report.md`

---

## Dependencies

```text
T002 ─┐
T003 ─┼─→ T011 ─→ T012 ─→ T013 ─→ T014
T004 ─┤
T005 ─┼─→ T010, T016
T006 ─┘
T011 ─→ T017 ─→ T018 ─→ T019
T011 ─→ T022 ─→ T023 ─→ T026
T004, T007 ─→ T029 ─→ T030 ─→ T031 ─→ T032
T011 ─→ T037 ─→ T038
T024, T025 ─→ T039
```

## 课件交付物对照

| 课件「本节交付物」 | 对应任务 |
| --- | --- |
| `AgentApiController` 全部端点 | T013, T023, T031 |
| `WorkspaceApiController`（tree/file 读/写，防穿越） | T037 |
| `AgentLifecycleService`（create 含回滚 / register / get / update / delete / generate） | T011, T022 |
| `WorkspaceWatcher`（启动全量扫 + 实时监听） | T017, T018 |
| `AgentScheduler.unregisterProfile` | T005 |
| `agentStore` 的 write/archive/delete | T003 |
| `AgentView` / `FileNode` / 请求体 DTO | T013, T023, T037 |
| `AgentLifecycleServiceTest` | T009, T021 |
| `WorkspaceWatcherTest` | T015 |
| `WorkspaceApiControllerTest` | T035 |
| `AgentApiControllerTest` | T036 |
| `GenerateTest` | T020 |
| `.sableos/archive/` 目录 | T003（`archive` 行为）、T009（顺序断言） |
| 前端「Agent 管理」「工作区」两页（§5.4 收敛为 Agent 列表 + 五页签） | T024, T025, T039 |

## 本节额外交付（课件 §5 增补，已确认纳入）

| 增补 | 任务 |
| --- | --- |
| per-agent 记忆（§5.2.1） | T004, T007, T029, T030, T031 |
| 固定会话（§5.2.2） | T023 |
| 文件可编辑（§5.2.3） | T037 |
| 生成/编辑 Agent 一对端点（§5.2.4） | T020, T022, T023 |
