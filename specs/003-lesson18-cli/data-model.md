# Phase 1 Data Model: 会话持久化（第 18 节）

> 表结构唯一权威是 Flyway 双轨脚本 `db/migration/{sqlite,postgresql}/V3__sessions.sql`（只追加，不改 V1/V2）。
> 本文件是列口径与映射说明；建表以脚本为准，禁 `ddl-auto` 改结构（AGENTS.md §5.1）。

## 1. `sessions` 表

Session 元数据 + JSON 序列化的对话历史（技术方案 §9.2 第 1 张表）。字段口径复用第 17 节为 `sessions` 预留的定义（见 `specs/002-lesson17-react/data-model.md`）。

| 列 | SQLite | PostgreSQL | 空 | 说明 |
| --- | --- | --- | --- | --- |
| `session_id` | `TEXT PRIMARY KEY` | `VARCHAR(255) PRIMARY KEY` | 否 | 主键，**非自增**；由 `SessionManager.sessionId(channel,user,profile)` = `channel:user:profile` 唯一拼接（research D-02）。与 `llm_calls.session_id`/`tool_invocations.session_id` 同源 |
| `profile_name` | `TEXT NOT NULL` | `VARCHAR(255) NOT NULL` | 否 | 关联 Profile（`ProfileRegistry` 索引键） |
| `channel` | `TEXT NOT NULL` | `VARCHAR(32) NOT NULL` | 否 | 接入渠道，取值 `cli`/`web`/`scheduler`（与 `TraceContext.CHANNEL_*` 同口径） |
| `user_id` | `TEXT NOT NULL` | `VARCHAR(255) NOT NULL` | 否 | 用户标识 |
| `messages_json` | `TEXT NOT NULL` | `TEXT NOT NULL` | 否 | 整体序列化的对话历史（`List<SessionMessage>` 的 JSON，空历史为 `[]`）；核心阶段不按条拆表（research D-05） |
| `status` | `TEXT NOT NULL` | `VARCHAR(16) NOT NULL` | 否 | 核心阶段固定 `active`（归档状态机流转不在本节，spec FR-015） |
| `created_at` | `INTEGER NOT NULL` | `TIMESTAMPTZ NOT NULL` | 否 | 创建时间（`Instant`；sqlite 以 INTEGER 毫秒往返，见 §3 绑定口径） |
| `last_active_at` | `INTEGER NOT NULL` | `TIMESTAMPTZ NOT NULL` | 否 | 最后活跃时间 |
| `archived_at` | `INTEGER` | `TIMESTAMPTZ` | 是 | 归档时间；核心阶段恒为 NULL（status 固定 active） |

**索引**：本节不额外建二级索引——`session_id` 已是主键（按标识取回走主键）；`session list` 轻命令全表扫描列会话（核心阶段会话量小，扩展阶段再按 `profile_name`/`last_active_at` 评估索引）。两轨脚本 1:1（列名/序一致，仅类型按方言改写）。

**与前后序节的接缝**：`sessions` 是审计三表的第三张（前两张 `llm_calls`/16 节、`tool_invocations`/17 节已落）；三表 `session_id` 同源。`llm_calls`/`tool_invocations` 的 `session_id` 目前无 FK 约束（17 节 data-model 记"待 sessions 落地后再评估"）——本节**不追加 FK**（避免改动前序已执行脚本，且 SQLite ALTER 能力有限），FK 评估留扩展阶段。

## 2. `SessionEntity`（JPA 映射，sableos-storage）

映射 `sessions` 表；`@Id` 为 `sessionId`（`String`，**无 `@GeneratedValue`**——标识由 `SessionManager` 生成后写入）。字段与列一一对应（驼峰 ↔ 下划线，`@Column(name=...)` 显式）。时间戳用 `Instant`（`created_at`/`last_active_at`/`archived_at`）。`@PrePersist`/`@PreUpdate` 不覆盖 `session_id`（由 manager 传入）；`created_at` 由 manager 在映射时置（新建=now，恢复=原值）。

> 命名取 `SessionEntity` 而非 `Session`，避免与 core 领域类 `com.sableos.core.session.Session` 撞名（research D-03）。

## 3. 领域 `Session` ↔ `SessionEntity` 映射（`JpaSessionManager`）

| 方向 | 规则 |
| --- | --- |
| `Session` → `SessionEntity`（save） | `sessionId←session.id()`；`profileName/channel/userId/status` 直映；`messagesJson←objectMapper.writeValueAsString(session.messages())`；`createdAt←session.createdAt()`；`lastActiveAt←session.lastActiveAt()`；`archivedAt←null`（本节）。`repository.save(entity)`（主键存在则 update、不存在则 insert，天然 upsert） |
| `SessionEntity` → `Session`（get/getOrCreate 命中） | `Session.rehydrate(sessionId, profileName, channel, userId, status, createdAt, lastActiveAt, objectMapper.readValue(messagesJson, List<SessionMessage>))`（research D-04） |
| `getOrCreate(channel,user,profile)` | `id = SessionManager.sessionId(channel,user,profile)`；`repository.findById(id)` 命中→rehydrate 返回；未命中→`new Session(id, profile, channel, user)`（空历史、createdAt=now、status=active）并**不立即落库**（首次 `save` 时 insert）——或按需立即 insert 一行空历史，二选一在实现时定，须保证幂等（同三元组收敛同 id） |

**`Instant` 绑定口径**（沿用 16/17 节，勿改）：`spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP`，`Instant` 以 `java.sql.Timestamp` 绑定，sqlite-jdbc 只对 INTEGER 毫秒可读写往返（`application.yaml` 已配，测试用 `@DynamicPropertySource` 同口径覆盖）。

**`messages_json` 结构**（`List<SessionMessage>` 的 JSON，research D-05）：每条目形如
`{"role":"USER|ASSISTANT|TOOL","content":"...","toolCalls":[{"id":"...","name":"...","argumentsJson":"..."}],"toolName":"...|null","success":true|false|null}`。
约束：`toolCalls` 仅 ASSISTANT 可非空（其余为 `[]`）；`toolName`/`success` 仅 TOOL 携带（其余 null）。反序列化须逐字段还原（round-trip 单测钉死，见 quickstart）。

## 4. `session_id` 单点公式（不变量 H4④ / SC-002）

```
SessionManager.sessionId(channel, userId, profileName) = channel + ":" + userId + ":" + profileName
```

- 唯一实现处：core `SessionManager` 接口的 `static` 方法（research D-02）。
- 所有入口只传三元组：CLI 传 `cli`（+ 当前 OS 用户或 `--user`）、Web 传 `web`（26 节）、定时传 `scheduler`（25 节）——**任何入口不得自行拼接标识字符串**。
- 收尾核验：`grep -rn '":"' --include=*.java` 确认除该静态方法外无第二处按 `channel:user:profile` 形态拼 session id。

## 5. 状态机（核心阶段范围）

`status` 核心阶段恒 `active`；`archived_at` 恒 NULL。归档流转（`active → archived`、置 `archived_at`）**不在本节**（spec 边界 / FR-015），字段按 §9.2 预留以免回改。
