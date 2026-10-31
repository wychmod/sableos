# Data Model: 定时任务子系统与通知渠道注册表

**Feature**: `specs/012-lesson28-scheduler-subsystem` | **Date**: 2026-10-31

> 表结构唯一权威是 `sableos-storage/src/main/resources/db/migration/{sqlite,postgresql}/` 下的 Flyway 脚本；本文件说明语义、校验与状态机，不替代迁移脚本。字段口径以《技术方案》§8.5 / §9.2 / §6.8 为准。

## 1. 定时任务（`scheduled_tasks`）

### 1.1 字段

| 字段 | 类型（SQLite / PG） | 可空 | 语义 |
| --- | --- | --- | --- |
| `schedule_id` | INTEGER PK AUTOINCREMENT / BIGSERIAL PK | 否 | 全局运行态主键；由库生成，跨重启稳定 |
| `profile_name` | TEXT / VARCHAR(255) | 否 | 归属 Agent |
| `schedule_key` | TEXT / VARCHAR(255) | 否 | Agent 内配置键；同一 Profile 内唯一 |
| `display_name` | TEXT / VARCHAR(255) | 否 | 展示名；核心阶段取 `schedule_key` |
| `cron` | TEXT / VARCHAR(128) | 否 | cron 表达式 |
| `zone` | TEXT / VARCHAR(64) | 否 | 时区标识（如 `Asia/Shanghai`） |
| `message` | TEXT | 否 | 到点发给 Agent 的消息 |
| `enabled` | INTEGER / BOOLEAN | 否 | 运营开关，默认 1/true |
| `retired` | INTEGER / BOOLEAN | 否 | 定义已删除或 key 已修改，默认 0/false |
| `next_run_at` | INTEGER / TIMESTAMPTZ | 是 | 下次触发时刻；cron 解析成功时必有值 |
| `last_run_at` | INTEGER / TIMESTAMPTZ | 是 | 上次真正执行开始时刻 |
| `last_status` | TEXT / VARCHAR(16) | 是 | `success` / `failed`；从未执行为空 |
| `run_count` | INTEGER / BIGINT | 否 | 累计真正执行次数，默认 0 |
| `updated_at` | INTEGER / TIMESTAMPTZ | 否 | 状态更新时间 |

约束：

- `UNIQUE (profile_name, schedule_key)`：同一 Agent 内 key 唯一；不同 Agent 可同名。
- 索引：`(retired, profile_name, schedule_key)` 支撑活动列表与对账。

### 1.2 不变量

1. `schedule_id` 一旦生成，对同一 `(profile_name, schedule_key)` 永久稳定；配置再次出现时复用，不新建。
2. `retired=true` 的行不参与调度、不出现在活动列表；`schedule_id` 与执行历史保留。
3. `enabled=false` 与 `retired=true` 是不同语义：前者是运营开关，重启后保持；后者是定义消失。
4. `run_count` 只在"真正执行"时 +1；因停用、锁占用而跳过不计。
5. `last_status` 只取 `success` / `failed`；从未执行为 null。
6. `updated_at` 在每次 reconcile、retire、setEnabled、recordExecution 时刷新。

### 1.3 状态流转

```text
reconcile(新 key)        → 新建：enabled=true, retired=false, run_count=0
reconcile(既有 key)      → 更新 cron/zone/message/display_name/next_run_at；保留 enabled/run_count/历史
reconcile(退役 key 再现) → 复用行，retired=false；保留 enabled/run_count/历史
retire(schedule_id)      → retired=true；不删行
setEnabled(id, false)    → enabled=false；retired 不变
setEnabled(id, true)     → enabled=true；retired 不变
recordExecution(成功)    → last_status=success, run_count+1, last_run_at=started_at, next_run_at=新值
recordExecution(失败)    → last_status=failed, run_count+1, last_run_at=started_at, next_run_at=新值
```

## 2. 执行历史（`task_executions`）

| 字段 | 类型（SQLite / PG） | 可空 | 语义 |
| --- | --- | --- | --- |
| `id` | INTEGER PK AUTOINCREMENT / BIGSERIAL PK | 否 | 自增主键 |
| `schedule_id` | INTEGER / BIGINT | 是 | 关联 `scheduled_tasks`；迁移前无法可靠关联的历史可为空 |
| `session_id` | TEXT / VARCHAR(255) | 否 | 本次触发所用的定时会话 |
| `started_at` | INTEGER / TIMESTAMPTZ | 否 | 开始时刻 |
| `success` | INTEGER / BOOLEAN | 否 | 是否成功 |
| `error_message` | TEXT | 是 | 失败原因，可读；成功为空 |
| `duration_ms` | INTEGER / BIGINT | 否 | 执行耗时（毫秒，>=0） |

约束与不变量：

- 索引 `(schedule_id, started_at)`；按 `started_at DESC, id DESC` 返回。
- 成功与失败都写；跳过不写。
- `error_message` 不含堆栈；CRLF 已净化；最长 1000 字符。
- `session_id` 必须是 `scheduler:scheduler:<profile>`（由 `SessionManager.sessionId` 生成，不在别处拼装）。

## 3. 通知渠道（`notify_channels`）

| 字段 | 类型（SQLite / PG） | 可空 | 语义 |
| --- | --- | --- | --- |
| `name` | TEXT PK / VARCHAR(255) PK | 否 | 全局唯一注册名；Agent 正文按名引用 |
| `type` | TEXT / VARCHAR(32) | 否 | 核心阶段只接受 `webhook` |
| `url` | TEXT | 否 | 目标地址；webhook 类型必填 |
| `description` | TEXT | 是 | 可选描述 |
| `config_json` | TEXT | 否 | 类型相关多字段 JSON，默认 `{}` |
| `created_at` | INTEGER / TIMESTAMPTZ | 否 | 创建时刻；决定"第一个渠道" |
| `updated_at` | INTEGER / TIMESTAMPTZ | 否 | 更新时间 |

校验：

- `name` 非空、长度 ≤ 128、只允许 `[A-Za-z0-9._-]`（保证可作为路径变量与运行期稳定标识）。
- `type` 非空、核心阶段只接受 `webhook`（大小写不敏感，入库统一小写）。
- `url` 非空、可被 URI 解析、scheme 为 `http` / `https`。
- `config_json` 必须是字符串到字符串的 JSON 对象；缺省 `{}`。
- 新建重名 → 冲突；更新/删除不存在 → 未找到。

不变量：

- `name` 是全局唯一真相源；`NotifyTools` 每次执行按名查询，不缓存启动快照。
- `list()` 按 `created_at ASC, name ASC`；缺省/空白/`default` 取第一条。
- `url` 是 webhook 的权威地址；映射到 `NotifyTarget` 时覆盖写入 `config["url"]`。

## 4. 视图对象（core 契约）

### 4.1 `ScheduledTaskView`

```text
(long scheduleId, String profileName, String scheduleKey, String displayName,
 String cron, String zone, String message, boolean enabled, boolean retired,
 Instant nextRunAt, Instant lastRunAt, String lastStatus, long runCount, Instant updatedAt)
```

- 非空：`profileName` / `scheduleKey` / `displayName` / `cron` / `zone` / `message` / `updatedAt`。
- 可空：`nextRunAt` / `lastRunAt` / `lastStatus`。
- `lastStatus` 只允许 `success` / `failed` / null。

### 4.2 `TaskExecutionView`

```text
(long executionId, Long scheduleId, String sessionId, Instant startedAt,
 boolean success, String errorMessage, long durationMs)
```

- `scheduleId` 可空（迁移前历史）；新写入恒非空。
- `errorMessage` 成功时为 null。

### 4.3 `NotifyChannelView`

```text
(String name, String type, String url, String description,
 Map<String, String> config, Instant createdAt, Instant updatedAt)
```

- `config` 恒非 null 且不可变（`Map.copyOf`）。
- `description` 可空；`name` / `type` / `url` / `createdAt` / `updatedAt` 非空。

## 5. 与既有表的关系

- `task_executions.session_id` → `sessions.session_id`（逻辑关联，不建外键；SQLite 外键约束默认不启用，与既有审计表一致）。
- `task_executions.schedule_id` → `scheduled_tasks.schedule_id`（逻辑关联；迁移前历史可为空）。
- 模型调用与工具调用仍分别落 `llm_calls` / `tool_invocations`，不在本节新增列、不改既有表。
- 通知渠道与 Agent 运行声明是"按名引用"关系，不建 Profile 字段、不建关联表。

## 6. 迁移自检

- `V6__scheduled_tasks.sql` 同时创建 `scheduled_tasks` 与 `task_executions`；`V7__notify_channels.sql` 创建 `notify_channels`。
- 两轨（sqlite / postgresql）版本号一一对应，均可在空库上从零执行。
- 已执行的 V1~V5 不得修改；新增脚本只追加。
