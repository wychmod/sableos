# Implementation Plan: 定时任务子系统与通知渠道注册表

**Branch**: `028-lesson28-scheduler-subsystem` | **Date**: 2026-10-31 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/012-lesson28-scheduler-subsystem/spec.md`

## Summary

把 25 节的「内存 cron」升级成完整的定时任务子系统：`AgentScheduler` 注册时经 core 的 `ScheduledTaskStore` 把任务登记进 `scheduled_tasks`（同一 `(profileName, scheduleKey)` 复用稳定 `scheduleId`），执行入口先查启用状态、再真正执行，成功失败都写 `task_executions` 并更新 `last_run_at / last_status / run_count / next_run_at`；新增 `runNow(scheduleId)` 供管理面手动补跑；启动结束后把已不在配置里的活动任务标记退役。`sableos-storage` 提供 `JpaScheduledTaskStore`，Flyway 双轨迁移新增 `scheduled_tasks` / `task_executions` 两表。

同时补齐 19 节后置的全局通知渠道注册表：core 定义 `NotifyChannelStore`，storage 落 `notify_channels` 表与 JPA 实现，`NotifyTools` 改为每次执行时从 store 解析渠道（缺省/空白/`default` 取第一个），web 提供 `/api/v1/notify-channels` CRUD，`ToolConfiguration` 注入 store。这样定时链路「查天气 → notify → webhook」能真跑通，31 节 Demo 也有真实出口。

管理面按《技术方案》§8.5 的 v2 口径交付：`/api/v2/schedules` 四端点 + `/api/v2/agents/{profileName}/schedules/{key}/run`；同时保留 `/api/v1/schedules/**` 按任务键定位的兼容层，遇到多个 Agent 撞 key 返回 409。管理台新增「定时任务」页（列表 + 立即执行 + 启用停用），只调用 v2。

## Technical Context

**Language/Version**: Java 21（父 POM enforcer 钉死 JDK 21 / Maven）

**Primary Dependencies**: Spring Boot 3.5.16 / Spring Framework 6.2.19 / Spring Data JPA / Flyway / SQLite JDBC。**不新增第三方依赖**：core 已有 `spring-context`（25 节为 `TaskScheduler` 引入），调度与 cron 计算沿用 Spring；notify 渠道 CRUD 复用既有 Spring MVC / Jackson；`sableos-web` 已依赖 `sableos-tool`，不新增模块边。

**Storage**: SQLite（默认档）+ Spring Data JPA + Flyway 双轨 `db/migration/{sqlite,postgresql}/`。新增 `V6__scheduled_tasks.sql`（`scheduled_tasks` + `task_executions`）与 `V7__notify_channels.sql`。禁用 `ddl-auto` 改结构。

**Testing**: JUnit 5 / Mockito 5 / AssertJ；整机用 `@SpringBootTest(classes = SableOsRuntime.class)` + `TestRestTemplate` + 临时工作区（沿用 27 节 `workspace.root` 覆盖）。门禁内：`ScheduledTaskE2ETest`、`MultiAgentIsolationTest`、各模块单测；`@Tag("integration")`：`SchedulerFlowIT`（真 key）、`RestartRecoveryIT`。

**Target Platform**: JVM 单二进制（`sableos-boot` fat JAR / CLI），内网单实例部署。

**Project Type**: Maven 多模块后端单体 + 同进程托管的 Vue 管理台。

**Performance Goals**: 无新增用户可感延迟目标；调度线程池沿用 25 节的 4 线程；任务状态与执行历史写库为一次短事务。

**Constraints**: 不新增模块；core 保持框架无关（不出现 JPA / Web 类型）；不使用 Reactor / `CompletableFuture` / 自建线程池；不新增第二个 `TaskDecorator`；`synchronized` 零使用；`session_id` 仍只由 `SessionManager.sessionId` 拼接；日志中的用户可控串先做 CRLF 净化；管理台构建不在 Maven 内（手工 `npm ci && npm run build`）。

**Scale/Scope**: 新增 core 契约 2 个 + 值对象 3 个；storage 实体 3 个 + 仓储 3 个 + 实现 2 个；tool 改造 1 个类；web 控制器 3 个 + DTO 6 个；迁移 4 份；前端 1 页 + 路由/导航/API 客户端；测试类约 9 个。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据 |
| --- | --- | --- |
| I. JDK 21 + Boot 3.x 单体 | ✅ | 不新增模块、不新增第三方依赖；沿用 Boot BOM 管理的 Spring |
| II. 自实现 ReAct 循环 | ✅ | 定时触发仍只调 `AgentService.process`；不改 `ReActLoop` / `ToolExecutor` / `PromptBuilder` |
| III. Spring AI 只用一半 | ✅ | 本节不出现 Spring AI 自动工具执行路径；notify 仍是 `SableTool` 由 `ToolExecutor` 调度 |
| IV. Provider 显式映射 | ✅ | 本节无 Provider 改动 |
| V. Tool 单模块 | ✅ | `NotifyTools` 改造仍在 `sableos-tool`，不拆模块 |
| VI. 审计 day one 落库 | ✅ | 新增 `task_executions`；工具调用成败仍走既有 `tool_invocations`；模型调用仍走 `llm_calls` |
| VII. Sandbox 接口先行 | ✅ | notify 发送前仍 `sandbox.enforce(HTTP_REQUEST, url)`，先于 `adapter.send`；不新增动作类型 |
| VIII. 范围收敛 | ✅ | 定时状态持久化与运行控制是《技术方案》§8.5 第 28 节明确范围；通知渠道是 §6.8 核心阶段能力；分布式协调、重试、邮件渠道、通知渠道管理台均明确不做 |
| IX. 每个 user story 可演示 | ✅ | `ScheduledTaskE2ETest` 覆盖 US1/US2；`RestartRecoveryIT` 覆盖 US3；`MultiAgentIsolationTest` 覆盖 US4；`SchedulerFlowIT` 覆盖 US1+US5 |
| AGENTS §5.1 #6 表结构唯一权威 | ✅ | 只用 Flyway 双轨迁移，禁 `ddl-auto`；不用课件里的 `schema.sql` |
| AGENTS §5.2 线程 / traceId | ✅ | 定时触发继续 `TracedExecutors.wrapRoot` 开新根链路；不新增 `TaskDecorator`；无 `synchronized`；无 `UUID.randomUUID()` |
| AGENTS §5.2 日志 CRLF 净化 | ✅ | 任务键 / 渠道名 / 错误信息入日志前 `replaceAll("[\r\n]", "_")` |

**Post-Phase 1 re-check**: 设计未引入新违规。唯一范围扩展（通知渠道注册表）已由用户在 2026-10-31 明确批准，并在 spec 的 US5 / FR-012~015 中登记；其契约来自《技术方案》§6.8，不属自造概念。

## Project Structure

### Documentation (this feature)

```text
specs/012-lesson28-scheduler-subsystem/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── contracts/
│   ├── scheduled-task-store.md
│   ├── notify-channel-store.md
│   └── management-api.md
├── quickstart.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
sableos-core/src/main/java/com/sableos/core/
├── scheduler/
│   ├── AgentScheduler.java              # 修改：reconcile / 启用检查 / recordExecution / runNow / retire
│   ├── ScheduledTaskStore.java          # 新增：reconcile / retire / recordExecution / isEnabled / setEnabled / find / list / executions
│   ├── ScheduledTaskView.java           # 新增：任务视图值对象
│   └── TaskExecutionView.java           # 新增：执行历史视图值对象
└── notify/
    ├── NotifyChannelStore.java          # 新增：list / find / save / delete
    └── NotifyChannelView.java           # 新增：通知渠道视图值对象

sableos-storage/src/main/java/com/sableos/storage/
├── scheduler/
│   ├── ScheduledTaskEntity.java
│   ├── TaskExecutionEntity.java
│   ├── ScheduledTaskRepository.java
│   ├── TaskExecutionRepository.java
│   └── JpaScheduledTaskStore.java
└── notify/
    ├── NotifyChannelEntity.java
    ├── NotifyChannelRepository.java
    └── JpaNotifyChannelStore.java

sableos-storage/src/main/resources/db/migration/
├── sqlite/V6__scheduled_tasks.sql
├── sqlite/V7__notify_channels.sql
├── postgresql/V6__scheduled_tasks.sql
└── postgresql/V7__notify_channels.sql

sableos-tool/src/main/java/com/sableos/tool/builtin/
└── NotifyTools.java                      # 修改：运行期从 NotifyChannelStore 解析

sableos-boot/src/main/java/com/sableos/boot/tool/
└── ToolConfiguration.java                # 修改：注入 NotifyChannelStore

sableos-cli/src/main/java/com/sableos/cli/
└── SableOsRuntime.java                   # 修改：装配 ScheduledTaskStore / NotifyChannelStore

sableos-web/src/main/java/com/sableos/web/api/
├── ScheduleApiController.java            # 新增：v2 四端点
├── ScheduleAgentRunController.java       # 新增：v2 按 Agent+key 立即执行
├── LegacyScheduleApiController.java      # 新增：v1 按 key 兼容（撞 key 409）
├── NotifyChannelApiController.java       # 新增：/api/v1/notify-channels CRUD
└── dto/
    ├── ScheduleView.java
    ├── ScheduleExecutionView.java
    ├── UpdateScheduleRequest.java
    ├── NotifyChannelView.java
    ├── NotifyChannelRequest.java
    └── RunScheduleResponse.java

sableos-web/src/main/frontend/src/
├── api/client.ts                         # 修改：支持 v2 base
├── api/endpoints.ts                      # 修改：定时任务 v2 端点
├── types/api.ts                          # 修改：定时任务类型
├── pages/SchedulesPage.vue               # 新增
├── router/index.ts                       # 修改：/schedules
└── components/AdminLayout.vue            # 修改：导航项

sableos-web/src/main/resources/static/admin/   # 重建产物（入库）

Test:
sableos-core/src/test/java/com/sableos/core/scheduler/AgentSchedulerTest.java        # 扩展
sableos-storage/src/test/java/com/sableos/storage/scheduler/JpaScheduledTaskStoreTest.java
sableos-storage/src/test/java/com/sableos/storage/notify/JpaNotifyChannelStoreTest.java
sableos-tool/src/test/java/com/sableos/tool/builtin/NotifyToolsTest.java             # 扩展运行期解析
sableos-web/src/test/java/com/sableos/web/api/ScheduleApiControllerTest.java
sableos-web/src/test/java/com/sableos/web/api/NotifyChannelApiControllerTest.java
sableos-boot/src/test/java/com/sableos/boot/ScheduledTaskE2ETest.java
sableos-boot/src/test/java/com/sableos/boot/MultiAgentIsolationTest.java
sableos-boot/src/test/java/com/sableos/boot/SchedulerFlowIT.java                     # @Tag("integration")
sableos-boot/src/test/java/com/sableos/boot/RestartRecoveryIT.java                   # @Tag("integration")
```

**Structure Decision**: 沿用既有 14 模块骨架（当前交付 9 个）。契约与中立值对象放 `sableos-core`（依赖倒置，沿用 `ScheduledTaskStore` / `SessionManager` 先例），JPA 实体、仓储与实现放 `sableos-storage`；调度改造仍在 `sableos-core/scheduler`，notify 工具仍在 `sableos-tool`；Web 控制器与 DTO 放 `sableos-web`；存储 Bean 在 `sableos-cli` 的 `SableOsRuntime` 装配（008 节先例）；前端与静态产物在 `sableos-web`。不新增模块、不改 Provider / ReAct / Memory。

## Complexity Tracking

| 取舍 | 为什么需要 | 更简单的替代为何不行 |
| --- | --- | --- |
| 通知渠道注册表并入本节（表 + core 契约 + storage 实现 + CRUD + NotifyTools 改造） | 第 28 节定时链路要靠 `notify` 把结果推出去；第 19 节已把注册表后置，仓库当前 `ToolConfiguration` 传空渠道表，`SchedulerFlowIT` 与 31 节 Demo 都无法真跑。用户 2026-10-31 明确选择并入。 | 只在测试内注入渠道会让生产侧仍不可用，31 节 Demo 还得再补一次；把注册表单独提前做一个 feature 会让本节无法完成端到端验收。 |
| `AgentScheduler` 依赖 core 的 `ScheduledTaskStore` 而不是直接依赖 storage | core 不能反向依赖 storage（ArchitectureTest 红线）；沿用 §8.5 明定的依赖倒置契约。 | 把持久化写进 core 会引入 JPA 依赖并破坏 core 框架无关性；把调度器挪进 storage 违反 §8.5 归口。 |
| `AgentScheduler` 保留 `runOnce(Profile, ScheduleConfig, long)` 的包级入口 | 既有 `AgentSchedulerTest` 不启 Spring 上下文，直接驱动执行路径；新增 `scheduleId` 参数后测试可继续覆盖锁、会话复用与失败隔离。 | 只暴露 `runNow(scheduleId)` 会让单测必须构造完整注册表与 store，测试焦点从行为变成装配。 |
| v1 兼容层单独一个控制器 | 课件要求 `/api/v1/schedules/**` 四端点，技术方案要求 v2 为主、v1 按 key 兼容且撞 key 409；两套路径的定位语义不同（key vs id），混在一个类里容易把 409 规则带到 v2。 | 只保留 v2 会丢课件要求的兼容面；只保留 v1 会违反技术方案 §8.5 的 v2 口径。 |
