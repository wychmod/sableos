-- sessions：Session 元数据 + JSON 序列化的对话历史（技术方案 §9.2 第 1 张表；FR-009/010/011）。
-- session_id 由 SessionManager 按 channel:user:profile 拼接（非自增主键）。
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
