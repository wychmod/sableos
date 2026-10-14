# Contract: tool_invocations 表结构（Flyway 双轨 DDL）

**Feature**: 002-lesson17-react | 契约类型：数据库表结构（`db/migration/{vendor}/V2__tool_invocations.sql`，sqlite 与 postgresql 双轨 1:1）

> 本表结构以迁移脚本为**唯一权威**；JPA 实体 `ToolInvocation` 只是映射，禁 `ddl-auto` 改结构（AGENTS.md §5.1 第 6 条）。列语义与上游需求对照见 [data-model.md](../data-model.md) §3。口径取自 `specs/001-runtime-core` data-model §2.2（经 `git show 17e2cfc^` 取回）。

## 1. sqlite 轨——`db/migration/sqlite/V2__tool_invocations.sql`

```sql
-- tool_invocations：每次工具调用一条审计（成败两态、day one 落库；FR-010/原则 VI）。
-- 手工维护脚本是表结构唯一权威——禁止用 ddl-auto 改结构。
CREATE TABLE IF NOT EXISTS tool_invocations (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    TEXT    NOT NULL,
    tool_name     TEXT    NOT NULL,
    input_json    TEXT,
    result_json   TEXT,
    success       INTEGER NOT NULL,
    error_message TEXT,
    duration_ms   INTEGER NOT NULL,
    trace_id      TEXT,
    created_at    INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tool_invocations_session_id ON tool_invocations (session_id);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_trace_id   ON tool_invocations (trace_id);
```

## 2. postgresql 轨——`db/migration/postgresql/V2__tool_invocations.sql`

```sql
-- tool_invocations：每次工具调用一条审计（成败两态、day one 落库；FR-010/原则 VI）。
CREATE TABLE IF NOT EXISTS tool_invocations (
    id            BIGSERIAL PRIMARY KEY,
    session_id    VARCHAR(255) NOT NULL,
    tool_name     VARCHAR(128) NOT NULL,
    input_json    TEXT,
    result_json   TEXT,
    success       BOOLEAN NOT NULL,
    error_message TEXT,
    duration_ms   BIGINT NOT NULL,
    trace_id      VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tool_invocations_session_id ON tool_invocations (session_id);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_trace_id   ON tool_invocations (trace_id);
```

## 3. 写入语义（与 contracts/tool-executor.md §3 对齐）

| 项 | 规约 |
| --- | --- |
| 条数 | 每次 `ToolExecutor.execute` 恰一条：成功 `success=1/true`，失败 `success=0/false` |
| 失败行字段 | `error_message` = 失败原因（未注册/入参解析失败/执行异常）；`result_json` = null；`input_json` 按可用值 |
| 成功行字段 | `error_message` = null；`result_json` = 工具结果内容 |
| `trace_id` | 写入时取 `TraceContext.traceId()`（无链路上下文为 null）；**禁止 `UUID.randomUUID()`**（AGENTS.md §5.2） |
| `duration_ms` | 执行前起计、返回/异常为止；**不含**审计自身写入耗时 |
| `created_at` | `@PrePersist` 落 `Instant.now()`；Hibernate 以 `setTimestamp` 绑定（`hibernate.type.preferred_instant_jdbc_type: TIMESTAMP`，boot 与 storage 测试同口径）——sqlite-jdbc 只对「INTEGER 毫秒」可读写往返；SQLite 存 epoch 毫秒，PG 存 `TIMESTAMPTZ` |

## 4. 迁移纪律

1. `sqlite/` 与 `postgresql/` 脚本**一一对应、版本号一致**；只追加新文件，不改历史脚本（`V1` 已被 16 节 `llm_calls` 占用，本节取 `V2`）；
2. 与 `db/migration/README.md` 的「按节追加策略」一致（该文件已预告 `tool_invocations`（17 节））；
3. 自检：两轨脚本都能在空库上从零执行——由 `ToolInvocationRepositoryTest`（临时 SQLite + 真 Flyway）承载。

## 5. 实体映射（JPA 侧，非权威、随迁移演进）

`ToolInvocation`：`@Entity @Table(name="tool_invocations")`；`@Id @GeneratedValue(strategy=IDENTITY)`；列名逐列 `@Column(name=...)`（`toolName→tool_name` 等下划线映射）；`createdAt` 由 `@PrePersist` 补写。`ToolInvocationRepository extends JpaRepository<ToolInvocation, Long>`。`JpaToolInvocationAuditor` 面向 `ToolInvocationAuditor` 接口，写失败抛 `IllegalStateException`（fail-closed）。
