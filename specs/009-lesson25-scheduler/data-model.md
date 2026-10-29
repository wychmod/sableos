# Phase 1 Data Model: 定时任务调度（第 25 节）

**Feature**: `specs/009-lesson25-scheduler` | **Date**: 2026-10-29

本节**不新增数据表、不新增迁移脚本、不新增持久化字段**。定时任务的「定义」仍是 `AGENT.md` frontmatter 派生出的 `Profile`；「运行态」只有进程内锁与 Spring 调度器里的已注册任务，重启即重建。

## 1. 定时任务声明（既有值对象，本节首次消费）

`Profile.ScheduleConfig`（`com.sableos.core.profile.Profile.ScheduleConfig`，16 节随 Profile 一并落地，`AgentLoader.parseSchedules` 已解析并有单测覆盖）：

| 字段 | 类型 | 来源（frontmatter） | 约束 |
|---|---|---|---|
| `id` | String | `schedules[].id` | 必填、非空白；同一进程内作为锁键与日志标识 |
| `cron` | String | `schedules[].cron` | 必填；Spring 六段 cron（含秒） |
| `zone` | String | `schedules[].zone` | 必填；`ZoneId` 可解析的时区 ID（如 `Asia/Shanghai`） |
| `message` | String | `schedules[].message` | 到点交给 Agent 的消息正文；内容语义不校验（空串也照常触发） |

不属于本值对象、本节也不引入的：`enabled` / `retired` / `next_run_at` / `last_run_at` / `run_count` —— 这些属第 28 节的持久化运行态。

## 2. 运行态（内存，不持久化）

### 2.1 任务执行权表

| 概念 | 形态 | 生命周期 |
|---|---|---|
| 任务锁 | `ConcurrentMap<String, Lock>`，键 = `ScheduleConfig.id`，值 = `ReentrantLock` | 进程内；首次触发时惰性创建，进程退出即失去 |

- 语义：同一 id 的上一次 `runOnce` 未结束时，`tryLock()` 失败 → 本次触发跳过（不排队、不并发）。
- 非语义：不是分布式锁，不跨进程；两个 Agent 若声明了相同 `id`，在这张表里被视为同一个任务（与课件骨架一致，课件的锁键就是 `sc.getId()`）。

### 2.1.1 会话锁表

| 概念 | 形态 | 生命周期 |
|---|---|---|
| 会话锁 | `ConcurrentMap<String, Lock>`，键 = `SessionManager.sessionId("scheduler","scheduler",profileName)`，值 = `ReentrantLock` | 进程内；首次触发时惰性创建 |

- 语义：同一 Agent 的多条 schedules 共用一个会话，对会话的「加载 → 追加 → 保存」必须串行；后到者**阻塞等待**（不是跳过）。
- 为什么需要：领域 `Session` 是一次快照对象，`JpaSessionManager.save` 整列覆盖 `messages_json`；两个并行触发会让后写者抹掉前者的历史（lost update）。

### 2.2 已注册任务

| 概念 | 形态 | 说明 |
|---|---|---|
| 调度注册项 | `TaskScheduler.schedule(Runnable, CronTrigger)` 返回的 `ScheduledFuture` | 本节不持有、不取消（运行期增删归第 30 节）；`registerAll` 只负责注册 |

## 3. 会话身份（复用 18 节 `sessions` 表，不改表）

| 字段 | 取值 |
|---|---|
| `session_id` | `scheduler:scheduler:<profileName>`（由 `SessionManager.sessionId` 唯一拼接） |
| `channel` | `scheduler` |
| `user_id` | `scheduler` |
| `profile_name` | 该定时任务所属 Agent 名 |

同一 Agent 的历次定时触发复用同一会话，历史累积靠 `settings.max_history_turns` 截断（17 节既有逻辑）。

## 4. 审计（复用既有两表，不改表）

| 表 | 定时触发时的写入来源 |
|---|---|
| `llm_calls` | `ProviderService` → `LlmCallAuditor`（与 CLI/Web 同一路径） |
| `tool_invocations` | `ToolExecutor` → `ToolInvocationAuditor`（同上） |

两表的 `trace_id` / `session_id` 均由既有链路写入：`trace_id` 来自 `wrapRoot` 开出的根链路，`session_id` 来自上一节的定时会话。**不新增列、不新增表。**

## 5. 明确留给后续节的数据结构

- `scheduled_tasks`、`task_executions` 两表及其 `ScheduledTaskStore` 契约：第 28 节（《技术方案》§8.5 / §9.2 已给出字段表）。
- 运行期按 Agent 增删定时任务所需的注册表变更：第 30 节。
