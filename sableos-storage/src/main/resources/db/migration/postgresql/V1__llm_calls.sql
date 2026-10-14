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
