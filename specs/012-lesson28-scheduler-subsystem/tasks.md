---
description: "Task list for 第28节 定时任务子系统与通知渠道注册表"
---

# Tasks: 定时任务子系统与通知渠道注册表

**Input**: Design documents from `specs/012-lesson28-scheduler-subsystem/`

**Prerequisites**: plan.md、spec.md、research.md（D-01~D-14）、data-model.md、contracts/、quickstart.md

**Tests**: 课件「验收 harness」明确要求测试，故测试任务为必需项；每个用户故事的测试先于对应实现落地，实现后当场跑红转绿。

**Organization**: 按用户故事分阶段；Phase 2 是两套 store 的共同数据层，先于所有故事完成。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1~US5）
- 每个任务给出精确文件路径

## Path Conventions

Maven 多模块：`sableos-core/`、`sableos-storage/`、`sableos-tool/`、`sableos-web/`、`sableos-boot/`、`sableos-cli/`；前端在 `sableos-web/src/main/frontend/`。

---

## Phase 1: Setup

**Purpose**: 基线确认与两套表结构；不新增第三方依赖。

- [x] T001 跑 `mvn -B clean verify` 确认基线全绿，并确认本节不需要新增依赖（`spring-context` / JPA / Flyway / SQLite 已在 BOM 内）
- [x] T002 [P] 新增 `sableos-storage/src/main/resources/db/migration/sqlite/V6__scheduled_tasks.sql` 与 `.../postgresql/V6__scheduled_tasks.sql`：`scheduled_tasks`（`schedule_id` 自增主键、`profile_name`、`schedule_key`、`display_name`、`cron`、`zone`、`message`、`enabled`、`retired`、`next_run_at`、`last_run_at`、`last_status`、`run_count`、`updated_at`；唯一约束 `(profile_name, schedule_key)`）与 `task_executions`（`id` 自增主键、`schedule_id` 可空、`session_id`、`started_at`、`success`、`error_message`、`duration_ms`；索引 `(schedule_id, started_at)`）
- [x] T003 [P] 新增 `sableos-storage/src/main/resources/db/migration/sqlite/V7__notify_channels.sql` 与 `.../postgresql/V7__notify_channels.sql`：`notify_channels`（`name` 主键、`type`、`url`、`description`、`config_json` 默认 `{}`、`created_at`、`updated_at`；索引 `created_at`）

---

## Phase 2: Foundational（阻塞所有用户故事）

**Purpose**: core 契约、storage 实现与装配；没有它，US1/US5 都无法落地。

- [x] T004 [P] 新增 `sableos-core/src/main/java/com/sableos/core/scheduler/ScheduledTaskView.java`：`record(long scheduleId, String profileName, String scheduleKey, String displayName, String cron, String zone, String message, boolean enabled, boolean retired, Instant nextRunAt, Instant lastRunAt, String lastStatus, long runCount, Instant updatedAt)`，非空字段与 `lastStatus` 取值在 compact 构造校验
- [x] T005 [P] 新增 `sableos-core/src/main/java/com/sableos/core/scheduler/TaskExecutionView.java`：`record(long executionId, Long scheduleId, String sessionId, Instant startedAt, boolean success, String errorMessage, long durationMs)`
- [x] T006 新增 `sableos-core/src/main/java/com/sableos/core/scheduler/ScheduledTaskStore.java`：`reconcile` / `retire` / `recordExecution` / `isEnabled` / `setEnabled` / `find` / `list` / `executions`（契约见 `contracts/scheduled-task-store.md`）
- [x] T007 [P] 新增 `sableos-core/src/main/java/com/sableos/core/notify/NotifyChannelView.java`：`record(String name, String type, String url, String description, Map<String, String> config, Instant createdAt, Instant updatedAt)`，`config` 恒非 null 且 `Map.copyOf`
- [x] T008 新增 `sableos-core/src/main/java/com/sableos/core/notify/NotifyChannelStore.java`：`list` / `find` / `save` / `delete`（契约见 `contracts/notify-channel-store.md`）
- [x] T009 [P] 写 `sableos-storage/src/test/java/com/sableos/storage/scheduler/JpaScheduledTaskStoreTest.java`：稳定标识复用、退役与复活、`recordExecution` 更新 `run_count/last_status/next_run_at`、`list` 排除退役、`executions` 倒序、`isEnabled`/`setEnabled`、V6 迁移列齐——先跑红
- [x] T010 新增 `sableos-storage/src/main/java/com/sableos/storage/scheduler/ScheduledTaskEntity.java`、`TaskExecutionEntity.java`、`ScheduledTaskRepository.java`、`TaskExecutionRepository.java`：JPA 映射与 `findByProfileNameAndScheduleKey`、`findByRetiredFalseOrderByProfileNameAscScheduleKeyAsc`、`findByScheduleIdOrderByStartedAtDescIdDesc`
- [x] T011 新增 `sableos-storage/src/main/java/com/sableos/storage/scheduler/JpaScheduledTaskStore.java`：实现 T006；`reconcile` 复用退役行并复活、`recordExecution` 单事务插入历史 + `runCount+1` + 状态更新
- [x] T012 [P] 写 `sableos-storage/src/test/java/com/sableos/storage/notify/JpaNotifyChannelStoreTest.java`：CRUD、`list` 按 `createdAt ASC, name ASC`、`save` 保留 `createdAt`、删除不存在返回 false、V7 迁移列齐——先跑红
- [x] T013 新增 `sableos-storage/src/main/java/com/sableos/storage/notify/NotifyChannelEntity.java`、`NotifyChannelRepository.java`、`JpaNotifyChannelStore.java`：JSON `config_json` 序列化/反序列化、upsert、排序、删除
- [x] T014 在 `sableos-cli/src/main/java/com/sableos/cli/SableOsRuntime.java` 装配 `ScheduledTaskStore` 与 `NotifyChannelStore` Bean；在 `sableos-storage/src/test/java/com/sableos/storage/StorageTestApplication.java` 同步测试 Bean
- [x] T015 跑 `mvn -B -pl sableos-core,sableos-storage -am test`，确认 T009/T012 转绿且既有 storage/core 用例不回归

**Checkpoint**: 两套 store 的契约、迁移与实现就位；US1/US5 可以并行推进。

---

## Phase 3: User Story 1 - 定时任务可定义、能自己跑、跑完有账 (Priority: P1) 🎯 MVP

**Goal**: 启动登记、到点执行、成功失败都写 `task_executions`、更新任务状态；停用跳过；失败隔离。

**Independent Test**: core 单测用测试替身 store 驱动 `registerAll` / `runOnce` / `runNow`，断言登记、启用检查、执行记录、失败隔离与退役；整机 REST 验收归 US2。

### Tests for User Story 1

- [x] T016 [US1] 扩展 `sableos-core/src/test/java/com/sableos/core/scheduler/AgentSchedulerTest.java`：加入测试替身 `InMemoryScheduledTaskStore`；补「停用跳过且不记历史」「recordExecution 成功/失败」「runNow 无视停用」「runNow 锁被占返回 empty」「registerAll 退役配置已删除的任务」——先跑红

### Implementation for User Story 1

- [x] T017 [US1] 修改 `sableos-core/src/main/java/com/sableos/core/scheduler/AgentScheduler.java`：构造器加 `ScheduledTaskStore`；`registerAll` 调 `reconcile` 并把 `scheduleId` 传给触发 Runnable；注册完成后退役不在当前配置集合里的活动任务
- [x] T018 [US1] 同文件实现 `runOnce(Profile, ScheduleConfig, long scheduleId)`：先 `isEnabled`，再 `tryLock`，执行后 `recordExecution`；跳过分支不记历史；异常不外抛、`finally` 释锁
- [x] T019 [US1] 同文件实现 `runNow(long scheduleId)`：按注册表或 store 重建任务、无视 `enabled`、同一把任务锁与会话锁、返回 `Optional<TaskExecutionView>`；锁被占返回 empty
- [x] T020 [US1] 同文件实现 `nextRunAt` 计算与可读错误信息（CRLF 净化、1000 字符截断、不存堆栈）；`recordExecution` 失败只记日志，不让调度器崩溃
- [x] T021 [US1] 修改 `sableos-boot/src/main/java/com/sableos/boot/scheduler/SchedulerConfiguration.java`：注入 `ScheduledTaskStore` 到 `AgentScheduler`
- [x] T022 [US1] 跑 `mvn -B -pl sableos-core -am test -Dtest=AgentSchedulerTest`，红转绿；确认既有断言未被削弱

**Checkpoint**: US1 在 core 层独立可验。

---

## Phase 4: User Story 2 - 定时任务可管理：立即执行与启用停用 (Priority: P1)

**Goal**: v2 五端点、v1 四端点兼容、管理台"定时任务"页、整机 E2E。

**Independent Test**: mock Agent 启动整机，通过 v2 端点查列表、立即执行、查历史、停用/启用；v1 撞 key 409；管理台 `/admin/schedules` 可操作。

### Tests for User Story 2

- [x] T023 [US2] 写 `sableos-web/src/test/java/com/sableos/web/api/ScheduleApiControllerTest.java`：v2 四端点 + agent+key 立即执行；404/400/409；v1 撞 key 409、0 条 404、列表不歧义——先跑红
- [x] T024 [P] [US2] 写 `sableos-boot/src/test/java/com/sableos/boot/ScheduledTaskE2ETest.java`：mock provider、临时工作区、远期 cron；断言启动登记、v2 列表、立即执行、`run_count/last_status`、执行历史、记忆写入、停用——先跑红

### Implementation for User Story 2

- [x] T025 [US2] 新增 `sableos-web/src/main/java/com/sableos/web/api/dto/ScheduleView.java`、`ScheduleExecutionView.java`、`UpdateScheduleRequest.java`：含 `from(ScheduledTaskView)` / `from(TaskExecutionView)` 映射；`enabled` 用 `Boolean` 以区分缺失
- [x] T026 [US2] 新增 `sableos-web/src/main/java/com/sableos/web/api/ScheduleApiController.java`：`GET /api/v2/schedules`、`GET /{id}/executions`、`POST /{id}/run`、`PUT /{id}`；不存在 404、锁占用 409、`enabled` 缺失 400
- [x] T027 [US2] 新增 `sableos-web/src/main/java/com/sableos/web/api/ScheduleAgentRunController.java`：`POST /api/v2/agents/{profileName}/schedules/{key}/run`
- [x] T028 [US2] 新增 `sableos-web/src/main/java/com/sableos/web/api/LegacyScheduleApiController.java`：`GET /api/v1/schedules`、`GET /{key}/executions`、`POST /{key}/run`、`PUT /{key}`；按 key 定位 0 条 404、多条 409
- [x] T029 [US2] 跑 `mvn -B -pl sableos-web -am test -Dtest=ScheduleApiControllerTest`，红转绿
- [x] T030 [US2] 跑 `mvn -B -pl sableos-boot -am test -Dtest=ScheduledTaskE2ETest`，红转绿
- [x] T031 [P] [US2] 修改 `sableos-web/src/main/frontend/src/api/client.ts`：`RequestOptions` 增加 `base`，默认 `API_BASE`；导出 `API_BASE_V2 = '/api/v2'`
- [x] T032 [P] [US2] 修改 `sableos-web/src/main/frontend/src/types/api.ts` 与 `api/endpoints.ts`：`ScheduleView` / `ScheduleExecution` 类型；`listSchedules` / `listScheduleExecutions` / `runSchedule` / `setScheduleEnabled`，均走 v2 base
- [x] T033 [US2] 新增 `sableos-web/src/main/frontend/src/pages/SchedulesPage.vue`：列表（任务 / Profile / cron / 下次触发 / 上次结果 / 次数 / 状态）+ 立即执行 + 启停 + 行内执行历史
- [x] T034 [US2] 修改 `sableos-web/src/main/frontend/src/router/index.ts` 与 `components/AdminLayout.vue`：新增 `/schedules` 路由与"定时任务"导航项（`clock` 图标）
- [x] T035 [US2] 在 `sableos-web/src/main/frontend` 执行 `npm ci && npm run build`，把 `sableos-web/src/main/resources/static/admin/**` 产物一起更新
- [x] T036 [US2] 更新 `README.md` 端点表与 `sableos-web/src/main/frontend/README.md`：v2 五端点、v1 兼容层、通知渠道 CRUD；说明管理台构建链

**Checkpoint**: 后端 v2/v1 与管理台"定时任务"页均可独立演示。

---

## Phase 5: User Story 3 - 重启不失忆 (Priority: P2)

**Goal**: 会话、记忆、定时状态与历史、审计跨上下文恢复。

**Independent Test**: 同一工作区先后启动两个上下文，第二个上下文读回第一个写入的四类数据。

- [x] T037 [US3] 写 `sableos-boot/src/test/java/com/sableos/boot/RestartRecoveryIT.java`（`@Tag("integration")`）：mock provider + 同一临时工作区；上下文一写入会话/记忆/定时执行/审计，关闭后用同一工作区启动上下文二，断言四类数据完整、`run_count/last_status/next_run_at` 与重启前一致、`llm_calls` 连续
- [x] T038 [US3] 在 `quickstart.md` 补齐进程级 `kill` + `serve` 的人工验收步骤与预期；确认 `RestartRecoveryIT` 在无 key 时也可运行（mock）
- [x] T039 [US3] 跑 `mvn -B -pl sableos-boot -am test -DexcludedGroups= -Dgroups=integration -Dtest=RestartRecoveryIT`，红转绿

**Checkpoint**: US3 独立可验；若失败，优先检查是否有状态被放在 `AgentScheduler` 的内存 map 作为事实源。

---

## Phase 6: User Story 4 - 多 Agent 并存不打架 (Priority: P2)

**Goal**: 工具、会话、定时三条隔离边界。

**Independent Test**: 两个 mock Profile；工具清单严格等于声明；会话标识不同；A 定时失败后 B 正常执行。

- [x] T040 [US4] 写 `sableos-boot/src/test/java/com/sableos/boot/MultiAgentIsolationTest.java`（mock、gate 内）：A 只声明 `read_file` / `write_file`，B 只声明 `http_get`；断言 `PromptBuilder.build(...).availableTools()` 严格等于各自声明；`SessionManager.getOrCreate` 返回不同 ID；A 的定时任务用不可达 provider 失败后 B 的 `runNow` 仍成功
- [x] T041 [US4] 若 T040 暴露「默认 Profile」串台，修对应实现；否则不改生产代码（只登记证据）
- [x] T042 [US4] 跑 `mvn -B -pl sableos-boot -am test -Dtest=MultiAgentIsolationTest`，红转绿

**Checkpoint**: US4 独立可验。

---

## Phase 7: User Story 5 - 通知渠道可管理，notify 运行期解析 (Priority: P2)

**Goal**: `notify_channels` CRUD、`NotifyTools` 运行期解析、定时链路真推送。

**Independent Test**: 通过 REST 新建渠道 → Agent 调 `notify` → 本地 webhook 收到；白名单外失败但调度器存活。

### Tests for User Story 5

- [x] T043 [US5] 扩展 `sableos-tool/src/test/java/com/sableos/tool/builtin/NotifyToolsTest.java`：store 构造器、缺省/空白/`default` 取第一个、按名命中、无渠道/未命中明确失败且零请求、`enforce` 先于 `send`——先跑红
- [x] T044 [P] [US5] 写 `sableos-web/src/test/java/com/sableos/web/api/NotifyChannelApiControllerTest.java`：CRUD、重名 409、不存在 404、名称/类型/url 非法 400、列表按创建时间升序——先跑红
- [x] T045 [P] [US5] 写 `sableos-boot/src/test/java/com/sableos/boot/SchedulerFlowIT.java`（`@Tag("integration")`、真 key、`assumeTrue`）：本地天气服务 + 本地 webhook；`sessions` 复用、`llm_calls` 2 条、`tool_invocations` 2 条全成功、webhook 收到；渠道改白名单外域名后失败记账、调度器仍能执行下一次

### Implementation for User Story 5

- [x] T046 [US5] 修改 `sableos-tool/src/main/java/com/sableos/tool/builtin/NotifyTools.java`：新增 store 构造器与每次执行时的 `list()/find()` 解析；保留 Map 构造器供既有单测；`NotifyChannelView` → `NotifyTarget` 时把 `url` 写入 `config`
- [x] T047 [US5] 修改 `sableos-boot/src/main/java/com/sableos/boot/tool/ToolConfiguration.java`：注入 `NotifyChannelStore`，生产装配改用 store 构造器
- [x] T048 [US5] 新增 `sableos-web/src/main/java/com/sableos/web/api/dto/NotifyChannelRequest.java`、`NotifyChannelView.java` 与 `sableos-web/src/main/java/com/sableos/web/api/NotifyChannelApiController.java`：`GET/POST/PUT/DELETE /api/v1/notify-channels`；名称/类型/url 校验、重名 409、不存在 404
- [x] T049 [US5] 跑 `mvn -B -pl sableos-tool,sableos-web -am test -Dtest=NotifyToolsTest,NotifyChannelApiControllerTest`，红转绿
- [x] T050 [US5] 在 `quickstart.md` 与 `README.md` 补通知渠道 CRUD 与 notify 链路；在 `AGENTS.md` §10 登记「通知渠道注册表随第 28 节落地」与本节口径

**Checkpoint**: US5 独立可验；31 节 Demo 的真实通知出口就位。

---

## Phase 8: Polish & Cross-Cutting Concerns

- [x] T051 跑 `mvn spotless:apply`，再跑 `mvn -B clean verify` 全绿；贴关键输出
- [x] T052 人工跑 `SchedulerFlowIT`（有 `DEEPSEEK_API_KEY` 时）与 `RestartRecoveryIT`，记录结果；无 key 时记录 skipped
- [x] T053 逐条核对 H4 全局不变量：notify 先 `Sandbox.enforce` 再 `send`；审计不建旁路；无明文 key；`session_id` 只在 core 单点拼接；无 Reactor / `CompletableFuture` / 自建线程池；无 Spring AI 自动工具执行
- [x] T054 逐条核对 AGENTS §5.2：定时触发新根链路、唯一 `TaskDecorator`、`TraceIds`、无 `synchronized` 阻塞、日志 CRLF 净化
- [x] T055 更新 `AGENTS.md` §10：记录 v2/v1 口径、`display_name` 取 key、通知渠道注册表落地、管理台构建链不变
- [x] T056 写 `specs/012-lesson28-scheduler-subsystem/acceptance-report.md`：六项证据 + 课件交付物映射 + 剩余人工项
- [x] T057 输出变更总结（改动点 / 重点 review 清单 / 如何验证），以 `git status --short` / `git diff --stat` 实测为准

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，先做 T001，T002/T003 可并行。
- **Foundational (Phase 2)**: 依赖 T002/T003；T004~T008 可并行，T009~T013 依赖对应契约，T014 依赖 T011/T013，T015 依赖 T014。
- **US1 (Phase 3)**: 依赖 Phase 2；T016 先写，T017~T021 实现，T022 收口。
- **US2 (Phase 4)**: 依赖 US1（需要 `runNow` 与 `AgentScheduler` Bean）；T023/T024 先写，T025~T028 实现，T029/T030 收口，T031~T035 前端，T036 文档。
- **US3 (Phase 5)**: 依赖 US1 + US2（需要 v2 端点）；可与 US4/US5 并行。
- **US4 (Phase 6)**: 依赖 US1；可与 US3/US5 并行。
- **US5 (Phase 7)**: 依赖 Phase 2；T043/T044/T045 先写，T046~T048 实现，T049~T050 收口；`SchedulerFlowIT` 还依赖 US1 的 `runNow` 与 US2 的控制器可用（测试可直接调 scheduler，不依赖控制器）。
- **Polish (Phase 8)**: 依赖全部目标故事。

### Within Each Story

- 测试先写并确认红；实现后当场跑该模块测试，红转绿再进下一个任务。
- 模型/契约先于实现；实现先于 Endpoint/UI；单模块绿后再跨模块。

### Parallel Opportunities

- T002 / T003 可并行（两套迁移脚本）。
- T004~T008 可并行（core 契约与值对象）。
- T009 与 T012 可并行（两套 storage 测试）。
- T023 与 T024 可并行（web 单测与 boot E2E）。
- T031 / T032 可并行（前端 client/types 与 endpoints）。
- T043 / T044 / T045 可并行（tool/web/boot 三处测试）。
- US3、US4、US5 在 US1/US2 完成后可由不同人并行。

---

## Parallel Example: Foundational

```bash
# 两套 core 契约可并行写：
Task: "新增 ScheduledTaskView / TaskExecutionView / ScheduledTaskStore"
Task: "新增 NotifyChannelView / NotifyChannelStore"
# 两套 storage 测试可并行写：
Task: "写 JpaScheduledTaskStoreTest"
Task: "写 JpaNotifyChannelStoreTest"
```

---

## Implementation Strategy

### MVP First

1. Phase 1 + Phase 2：迁移与两套 store 就位。
2. Phase 3（US1）：启动登记、到点执行、执行历史、停用跳过。
3. Phase 4（US2）：v2/v1 管理面 + `ScheduledTaskE2ETest` + 管理台页面。
4. **STOP and VALIDATE**：跑 `ScheduledTaskE2ETest`，确认 mock 全链路。

### Incremental Delivery

1. US1 → 可自己跑、跑完有账（核心层 MVP）。
2. US2 → 可看、可补跑、可停用（整机 MVP）。
3. US3 → 重启不失忆。
4. US4 → 多 Agent 不打架。
5. US5 → 通知渠道可管理、notify 真推送。

### Notes

- 每个任务完成后跑对应模块测试；不攒到最后。
- 任何断言不得删除、`@Disabled` 或放宽阈值；实现错修实现。
- 前端改完必须 `npm ci && npm run build`，`static/admin/**` 是入库产物。
- 全量门禁前先 `mvn spotless:apply`。
