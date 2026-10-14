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
