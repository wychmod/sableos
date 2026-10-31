-- scheduled_tasks：定时任务登记信息与运行状态（技术方案 §8.5 / §9.2；第 28 节）。
-- 定义来源仍是 AGENT.md 的 schedules；本表只存状态与历史，不作为定义源。
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    schedule_id    BIGSERIAL PRIMARY KEY,
    profile_name   VARCHAR(255) NOT NULL,
    schedule_key   VARCHAR(255) NOT NULL,
    display_name   VARCHAR(255) NOT NULL,
    cron           VARCHAR(128) NOT NULL,
    zone           VARCHAR(64)  NOT NULL,
    message        TEXT         NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    retired        BOOLEAN      NOT NULL DEFAULT FALSE,
    next_run_at    TIMESTAMPTZ,
    last_run_at    TIMESTAMPTZ,
    last_status    VARCHAR(16),
    run_count      BIGINT       NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_scheduled_tasks_profile_key UNIQUE (profile_name, schedule_key)
);

CREATE INDEX IF NOT EXISTS idx_scheduled_tasks_active
    ON scheduled_tasks (retired, profile_name, schedule_key);

-- task_executions：每次真正执行的审计历史，成功失败都记（技术方案 §8.5 / §9.2；第 28 节）。
CREATE TABLE IF NOT EXISTS task_executions (
    id            BIGSERIAL PRIMARY KEY,
    schedule_id   BIGINT,
    session_id    VARCHAR(255) NOT NULL,
    started_at    TIMESTAMPTZ  NOT NULL,
    success       BOOLEAN      NOT NULL,
    error_message TEXT,
    duration_ms   BIGINT       NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_task_executions_schedule
    ON task_executions (schedule_id, started_at);
