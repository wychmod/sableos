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
