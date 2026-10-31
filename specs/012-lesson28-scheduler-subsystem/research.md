# Phase 0 Research: 定时任务子系统与通知渠道注册表

**Date**: 2026-10-31 | **Feature**: `specs/012-lesson28-scheduler-subsystem`

## D-01 定时任务的身份与稳定标识

**Decision**: `scheduled_tasks` 用自增 `schedule_id` 作全局主键；`(profile_name, schedule_key)` 唯一。`reconcile(profileName, key, ...)` 先按二元组查行，命中则复用 `schedule_id`、更新 cron/zone/message/display_name/next_run_at；未命中则新建。退役行保留，配置再次出现时复用同一行并把 `retired` 置回 false。

**Rationale**: 任务键只在 Agent 内唯一，两个 Agent 各自声明 `daily` 是两条任务；用全局自增 ID 做管理面与执行历史的外键，才能避免"裸 key 选错 Agent"。复用行而不是删了重建，保证 `schedule_id` 稳定、历史不丢（技术方案 §8.5 / §9.2）。

**Alternatives considered**:
- 用 `(profile_name, schedule_key)` 复合主键：管理面路径要带两个变量，执行历史外键也变成复合键；与 §9.2 的 `schedule_id` 口径不符。
- 删除旧行再插入：违反"退役不删历史"。

## D-02 两张表的列与 Flyway 双轨

**Decision**: 新增 `V6__scheduled_tasks.sql`（含 `scheduled_tasks` 与 `task_executions`）和 `V7__notify_channels.sql`，sqlite / postgresql 各一份，版本号一一对应。

- `scheduled_tasks`：`schedule_id`（自增主键）、`profile_name`、`schedule_key`、`display_name`、`cron`、`zone`、`message`、`enabled`、`retired`、`next_run_at`、`last_run_at`、`last_status`、`run_count`、`updated_at`；唯一约束 `(profile_name, schedule_key)`。
- `task_executions`：`id`（自增主键）、`schedule_id`（可空，兼容迁移前历史）、`session_id`、`started_at`、`success`、`error_message`、`duration_ms`；索引 `(schedule_id, started_at)`。
- `notify_channels`：`name`（主键）、`type`、`url`、`description`、`config_json`、`created_at`、`updated_at`；索引 `created_at`。

**Rationale**: 表结构唯一权威是 Flyway 双轨目录（AGENTS §5.1 #6），课件里的 `schema.sql` 不适用。SQLite 用 `INTEGER` 存时刻、`INTEGER` 存布尔；PostgreSQL 用 `TIMESTAMPTZ` / `BOOLEAN`，与既有 V1~V5 同构。

**Alternatives considered**:
- 继续用 25 节的纯内存注册：重启丢状态，不满足 FR-004 / FR-010。
- 用 `ddl-auto=update` 建表：SQLite 上不可控，违反硬约束。

## D-03 契约在 core、实现在 storage（依赖倒置）

**Decision**: `ScheduledTaskStore`、`ScheduledTaskView`、`TaskExecutionView` 放 `com.sableos.core.scheduler`；`JpaScheduledTaskStore` 与实体/仓储放 `com.sableos.storage.scheduler`。`NotifyChannelStore`、`NotifyChannelView` 放 `com.sableos.core.notify`；JPA 实现放 `com.sableos.storage.notify`。存储 Bean 在 `SableOsRuntime` 装配。

**Rationale**: `AgentScheduler` 在 core，不能反向依赖 storage（ArchitectureTest 红线）；`NotifyTools` 在 tool，也不能依赖 storage。契约与中立值对象放 core 是 §8.5 明定的先例，也让 core 保持框架无关。

**Alternatives considered**:
- 把 store 接口放 storage、由 core 依赖 storage：形成 `core → storage` 反向边，ArchitectureTest 直接失败。
- 在 core 里直接用 JPA：把持久化依赖塞进内核，违反 §10 分层。

## D-04 启用、退役与启动对账

**Decision**: `isEnabled(scheduleId)` 对缺失行、`retired=true`、`enabled=false` 一律返回 false。`registerAll()` 完成所有活动配置的 `reconcile` 后，取 `store.list()`（仅活动行），把不在本次 `(profile, key)` 集合里的行 `retire`。退役行不出现在列表；配置再次出现时 `reconcile` 复用并复活该行。

**Rationale**: "停用即不跑"与"配置删了就退役"是两条不同语义：`enabled` 是运营开关，`retired` 是定义消失。分开存才能让管理台停用的任务在重启后仍然停用，而删掉定义的任务不再出现但历史可留。

**Alternatives considered**:
- 用 `enabled=false` 代替退役：管理台分不清"运营主动停用"与"定义已删除"。
- 启动时删旧行：丢历史，违反 §8.5。

## D-05 执行历史与任务状态一次事务更新

**Decision**: `recordExecution(scheduleId, sessionId, startedAt, success, errorMessage, durationMs, nextRunAt)` 在同一事务里插入一条 `task_executions` 并更新任务行的 `last_run_at / last_status / run_count+1 / next_run_at / updated_at`。错误信息只存可读原因，不存堆栈；入参先做 CRLF 净化与长度截断（建议 1000 字符）。

**Rationale**: 若先写历史再单独更新任务状态，中间失败会出现"历史有、列表没变"或反之；一次事务保证对账口径一致（FR-004）。`run_count` 只在真正执行时增加，跳过（锁占用、停用）不计。

**Alternatives considered**:
- 历史与状态分两次提交：会出现短暂不一致，重启窗口内可见。
- 失败只记日志不记历史：违反"成功失败都记"。

## D-06 立即执行的语义

**Decision**: `AgentScheduler.runNow(scheduleId)` 返回 `Optional<TaskExecutionView>`：任务不存在返回 empty；任务锁被占返回 empty（web 映射 409）；否则无视 `enabled` 执行一次并记历史。执行仍走 `(profileName, key)` 任务锁与 `scheduler:scheduler:<profile>` 会话锁，与到点触发完全同一条路径。`next_run_at` 在手动执行后按 cron 重新计算，不改变 cron 序列。

**Rationale**: "立即执行"是补跑，不是并行第二份；沿用同一把锁避免与到点触发重叠。返回 empty 而不抛领域异常，避免 core 依赖 web 的 `ConflictException`，由 web 层翻译成 409。

**Alternatives considered**:
- 异步触发立刻返回 202：管理台无法立刻展示本次结果，E2E 断言需要轮询。
- 绕过锁直接执行：可能与到点触发并写同一会话，违反 25 节的会话串行不变量。

## D-07 v2 主路径与 v1 兼容

**Decision**:
- v2（管理台唯一调用）：`GET /api/v2/schedules`、`GET /api/v2/schedules/{scheduleId}/executions`、`POST /api/v2/schedules/{scheduleId}/run`、`PUT /api/v2/schedules/{scheduleId}`、`POST /api/v2/agents/{profileName}/schedules/{key}/run`。
- v1 兼容：`GET /api/v1/schedules`、`GET /api/v1/schedules/{key}/executions`、`POST /api/v1/schedules/{key}/run`、`PUT /api/v1/schedules/{key}`。列表不歧义；按 key 定位时 0 条 → 404，多条 → 409，提示改用 v2 ID。

**Rationale**: 技术方案 §8.5 明确 v2 为主、v1 按旧 key 兼容且"绝不静默选第一条"；课件要求 v1 四端点。两套路径共用同一个 store 与 `AgentScheduler`，不产生两套账。

**Alternatives considered**:
- 只做 v2：丢课件要求的兼容面，v1 调用方直接 404。
- 只做 v1：违反 §8.5 的 v2 口径，管理台无法精确定位。
- v1 撞 key 时默认取第一条：会把任务执行到错误 Agent 上。

## D-08 通知渠道注册表的数据形状

**Decision**: `notify_channels` 以 `name` 为主键；字段 `type`（核心阶段只接受 `webhook`）、`url`、`description`（可空）、`config_json`（类型相关多字段，默认 `{}`）、`created_at`、`updated_at`。core 的 `NotifyChannelView` 暴露 `Map<String,String> config`；存储层负责 JSON 序列化/反序列化。`NotifyTarget` 的配置映射 = `config` + `url` 覆盖写入，保证 `WebhookNotifyAdapter` 仍能从 `config.get("url")` 取地址。

**Rationale**: 技术方案 §6.8 规定渠道含 `name`、`type`、`url`、可选 `description` 与类型相关 `config`；把 `url` 也放进 `NotifyTarget.config` 可复用既有适配器，不改 `WebhookNotifyAdapter`。

**Alternatives considered**:
- 只存 `url` 不存 `config`：扩展阶段加 email 类型时要改表。
- 把 `config` 拆成多个列：类型相关字段会随渠道类型膨胀。

## D-09 NotifyTools 的运行期解析与测试兼容

**Decision**: `NotifyTools` 新增 `(Sandbox, NotifyChannelAdapter, NotifyChannelStore)` 构造器；每次 `execute` 时 `channel` 缺省/空白/`default` 取 `store.list()` 第一个（按 `created_at` 升序），否则 `store.find(name)`。保留既有 `(Sandbox, NotifyChannelAdapter, Map<String, NotifyTarget>)` 构造器供 19 节单测使用；生产装配只用 store 构造器。

**Rationale**: 运行期查询让"改渠道立即生效"成立，不需要重启 Agent；保留 Map 构造器避免为了持久化而重写 19 节已通过的渠道解析单测，测试焦点不漂移。

**Alternatives considered**:
- 启动时把注册表加载成 Map：CRUD 后需要重启或手动刷新，违反 FR-014。
- 改 `NotifyChannelAdapter` 接口：超出本节范围，且会破坏 19 节的接口先行契约。

## D-10 展示名与任务键

**Decision**: 核心阶段 `display_name` 取 `schedule.id()`（任务键）。不新增 `AGENT.md` frontmatter 字段。

**Rationale**: `Profile.ScheduleConfig` 只有 id/cron/zone/message 四要素；技术方案 §9.2 的 `display_name` 是"展示名称，不参与运行态定位"，但未指定来源。取任务键是最小、可逆的选择，避免为展示名扩 Profile 契约。

**Alternatives considered**:
- 新增 `name` 字段：改 `Profile` / `AgentLoader` / 既有测试，超出本节交付清单。
- 取消息前 N 字：消息可能为空或很长，展示不稳定。

## D-11 管理台 v2 客户端与页面

**Decision**: `client.ts` 的 `request` 增加可选 `base`，默认 `/api/v1`；新增 `API_BASE_V2 = '/api/v2'`。`SchedulesPage.vue` 用表格展示任务，行内提供"立即执行""停用/启用""历史"；历史用行内展开表格，不引入新弹窗组件。路由 `/schedules`，导航项用既有 `clock` 图标。构建后把 `static/admin/**` 产物一起入库。

**Rationale**: 管理台与接口同进程托管，v2 前缀不能靠改全局 base；按请求传 base 最小改动。复用既有组件与图标，不新增 UI 依赖。

**Alternatives considered**:
- 把全局 base 改成 `/api` 再拼 `/v1`/`/v2`：会改动所有既有端点路径，风险大。
- 新建通用 Modal 组件：本节只需一个历史视图，行内展开足够。

## D-12 重启验收的测试形态

**Decision**: `RestartRecoveryIT` 在同一个 JVM 内先后构建两个 `SableOsRuntime` 应用上下文（第一个用 `WebApplicationType.SERVLET` + 随机端口，关闭后再用同一工作区启动第二个），通过第二个上下文的 HTTP 接口读回数据。该测试打 `@Tag("integration")`，不进默认门禁。

**Rationale**: 真正 `kill -9` + 子进程重启在 CI 中不可移植、也不稳定；"关闭上下文再新建上下文"已经能证明状态不在第一个上下文的内存里。进程级 kill 的重启步骤仍写入 quickstart 的人工验收项。

**Alternatives considered**:
- 用 `ProcessBuilder` 起 fat JAR：依赖打包产物、端口与超时不稳定，CI 成本高。
- 只用 `@DirtiesContext`：同一个 `@SpringBootTest` 实例无法保证完全新的上下文与 SQLite 连接生命周期，断言不够硬。

## D-13 JPA / SQLite 的布尔与时刻映射

**Decision**: 实体字段用 `boolean` / `Instant`；SQLite 迁移用 `INTEGER`，PostgreSQL 用 `BOOLEAN` / `TIMESTAMPTZ`；沿用 `spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP`。实体 `@PrePersist` 只补 `updated_at`/`created_at`，其余字段由 store 显式赋值。

**Rationale**: 与既有 `llm_calls` / `sessions` 的映射完全同构，避免 SQLite 上 Instant 往返解析问题（AGENTS §5.3 已记录）。

**Alternatives considered**:
- 用 `String` 存时刻：排序与比较要额外解析，SQL 侧过滤困难。
- 用 `LocalDateTime`：丢失时区语义，跨时区部署出错。

## D-14 错误信息可读性与审计边界

**Decision**: 执行失败时 `error_message` 取 `Throwable.getMessage()`（非空时），做 CRLF 净化并截断 1000 字符；为空时取异常简单类名。不让堆栈进库。工具调用的成败仍由既有 `ToolExecutor` 写 `tool_invocations`，调度器不重复记工具账；模型调用仍由既有 Provider 审计写 `llm_calls`。

**Rationale**: 审计表是给人查的（FR-016）；沙箱拒绝的异常消息本身已点名域名/路径，直接存即可。调度器只记"这次任务执行"这一层，工具与模型账各归各的既有路径，避免双记。

**Alternatives considered**:
- 存完整堆栈：表膨胀、不可读、可能泄露内部路径。
- 调度器再写一份工具审计：与 `ToolExecutor` 重复，违反"不建旁路"。
