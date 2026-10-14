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
