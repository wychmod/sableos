# Contract: sessions 表结构（Flyway 双轨 V3）

> 表结构唯一权威 = `sableos-storage/src/main/resources/db/migration/{sqlite,postgresql}/V3__sessions.sql`（只追加，不改 V1/V2）。
> 列口径详见 [data-model.md](../data-model.md#1-sessions-表)。禁 `ddl-auto` 改结构。

## SQLite（默认档）

```sql
-- sessions：Session 元数据 + JSON 序列化的对话历史（技术方案 §9.2 第 1 张表；FR-009/010/011）。
CREATE TABLE IF NOT EXISTS sessions (
    session_id     TEXT    PRIMARY KEY,          -- channel:user:profile，由 SessionManager 单点拼接
    profile_name   TEXT    NOT NULL,
    channel        TEXT    NOT NULL,
    user_id        TEXT    NOT NULL,
    messages_json  TEXT    NOT NULL,             -- List<SessionMessage> 的 JSON，空历史为 []
    status         TEXT    NOT NULL,             -- 核心阶段固定 'active'
    created_at     INTEGER NOT NULL,             -- Instant 以 INTEGER 毫秒往返
    last_active_at INTEGER NOT NULL,
    archived_at    INTEGER                       -- 核心阶段恒 NULL
);
```

## PostgreSQL（部署档，1:1）

```sql
CREATE TABLE IF NOT EXISTS sessions (
    session_id     VARCHAR(255) PRIMARY KEY,
    profile_name   VARCHAR(255) NOT NULL,
    channel        VARCHAR(32)  NOT NULL,
    user_id        VARCHAR(255) NOT NULL,
    messages_json  TEXT         NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    last_active_at TIMESTAMPTZ  NOT NULL,
    archived_at    TIMESTAMPTZ
);
```

## 验收锚点（SessionRepositoryTest）

- `PRAGMA table_info(sessions)`（sqlite）含全部 9 列：`session_id`/`profile_name`/`channel`/`user_id`/`messages_json`/`status`/`created_at`/`last_active_at`/`archived_at`。
- `flyway_schema_history` 含 version `1`/`2`/`3` 各一条（V3 由手工迁移执行，双轨追加）。
- 存一行带历史的会话→读回→`messages_json` 反序列化后消息逐条完整（角色/内容/toolCalls/toolName/success）。
- 模拟重启：新建 `@SpringBootTest` 上下文（同一临时 DB 文件）重查，历史仍在。

## 约束

- 不追加 `llm_calls`/`tool_invocations` → `sessions` 的 FK（避免改前序已执行脚本；SQLite ALTER 受限）；FK 评估留扩展阶段。
- 不建二级索引（主键即取回路径；`session list` 全表扫描，核心阶段量小）。
