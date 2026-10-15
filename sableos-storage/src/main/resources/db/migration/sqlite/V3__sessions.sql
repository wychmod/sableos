-- sessions：Session 元数据 + JSON 序列化的对话历史（技术方案 §9.2 第 1 张表；FR-009/010/011）。
-- 手工维护脚本是表结构唯一权威——禁止用 ddl-auto 改结构。session_id 由 SessionManager 按 channel:user:profile 拼接。
CREATE TABLE IF NOT EXISTS sessions (
    session_id     TEXT    PRIMARY KEY,
    profile_name   TEXT    NOT NULL,
    channel        TEXT    NOT NULL,
    user_id        TEXT    NOT NULL,
    messages_json  TEXT    NOT NULL,
    status         TEXT    NOT NULL,
    created_at     INTEGER NOT NULL,
    last_active_at INTEGER NOT NULL,
    archived_at    INTEGER
);
