# Contract: llm_calls 表结构（Flyway 双轨 DDL）

**Feature**: 001-lesson16-provider | 契约类型：数据库表结构（`db/migration/{vendor}/V1__llm_calls.sql`，sqlite 与 postgresql 双轨 1:1）

> 本表结构以迁移脚本为**唯一权威**；JPA 实体 `LlmCall` 只是映射，禁 `ddl-auto` 改结构（AGENTS.md §5.1 第 6 条）。列语义与上游需求对照见 [data-model.md](../data-model.md) §3。

## 1. sqlite 轨——`db/migration/sqlite/V1__llm_calls.sql`

```sql
-- llm_calls：每次大模型调用一条审计（成败两态、day one 落库；FR-005/原则 VI）。
-- 手工维护脚本是表结构唯一权威——禁止用 ddl-auto 改结构。
CREATE TABLE IF NOT EXISTS llm_calls (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id        TEXT    NOT NULL,
    provider          TEXT    NOT NULL,
    model             TEXT    NOT NULL,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    trace_id          TEXT,
    success           INTEGER NOT NULL,
    error_message     TEXT,
    duration_ms       INTEGER NOT NULL,
    created_at        INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_llm_calls_session_id ON llm_calls (session_id);
CREATE INDEX IF NOT EXISTS idx_llm_calls_trace_id   ON llm_calls (trace_id);
```

## 2. postgresql 轨——`db/migration/postgresql/V1__llm_calls.sql`

```sql
-- llm_calls：每次大模型调用一条审计（成败两态、day one 落库；FR-005/原则 VI）。
CREATE TABLE IF NOT EXISTS llm_calls (
    id                BIGSERIAL PRIMARY KEY,
    session_id        VARCHAR(255) NOT NULL,
    provider          VARCHAR(64)  NOT NULL,
    model             VARCHAR(128) NOT NULL,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    trace_id          VARCHAR(64),
    success           BOOLEAN NOT NULL,
    error_message     TEXT,
    duration_ms       BIGINT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_llm_calls_session_id ON llm_calls (session_id);
CREATE INDEX IF NOT EXISTS idx_llm_calls_trace_id   ON llm_calls (trace_id);
```

## 3. 写入语义（与 §1.1/§1.5 编排对齐）

| 项 | 规约 |
| --- | --- |
| 条数 | 每次 `ProviderService.chat` 恰一条：成功 `success=1/true`，失败 `success=0/false` |
| 失败行字段 | `error_message` = 异常消息（写库前不改写；**入日志**前按仓库 CRLF 净化规约处理）；token 三列 null |
| 成功行字段 | `error_message` = null；token 三列来自供应商返回（供应商不返回则 null） |
| `trace_id` | 写入时取 `TraceContext.traceId()`（无链路上下文为 null）；**禁止 `UUID.randomUUID()`**（AGENTS.md §5.2） |
| `duration_ms` | 调用前起计、模型返回/异常为止；**不含**审计自身写入耗时 |
| `created_at` | `@PrePersist` 落 `Instant.now()`；Hibernate 以 `setTimestamp` 绑定（`hibernate.type.preferred_instant_jdbc_type: TIMESTAMP`，boot 与 storage 测试同口径）——sqlite-jdbc 只对「INTEGER 毫秒」可读写往返，默认 `TIMESTAMP_UTC` 绑定的 ISO 文本读回会 `Error parsing time stamp`（2026-10-14 实测）；SQLite 存 epoch 毫秒，PG 存 `TIMESTAMPTZ` |

## 4. 迁移纪律（本表落地时同步执行）

1. `sqlite/` 与 `postgresql/` 脚本**一一对应、版本号一致**；只追加新文件，不改历史脚本；
2. `db/migration/README.md` 中已悬空的 T015 五表预留段改写为「按节追加」策略（旧的 `specs/001-runtime-core` 引用已随 `17e2cfc` 删除）；
3. 自检：两轨脚本都能在空库上从零执行——由 `LlmCallRepositoryTest`（临时 SQLite + 真 Flyway）与手工 PG 验证（本节快速验证项）承载。

## 5. 实体映射（JPA 侧，非权威、随迁移演进）

`LlmCall`：`@Entity @Table(name="llm_calls")`；`@Id @GeneratedValue(strategy=IDENTITY)`；列名逐列 `@Column(name=...)` 对齐上表（`promptTokens→prompt_tokens` 等下划线映射）；`createdAt` 由 `@PrePersist` 补写。`LlmCallRepository extends JpaRepository<LlmCall, Long>`。`JpaLlmCallAuditor` 面向 `LlmCallAuditor` 接口，写失败抛 `IllegalStateException`（fail-closed）。
