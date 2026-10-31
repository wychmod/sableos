-- scheduled_tasks：定时任务登记信息与运行状态（技术方案 §8.5 / §9.2；第 28 节）。
-- 定义来源仍是 AGENT.md 的 schedules；本表只存状态与历史，不作为定义源。
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    schedule_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_name   TEXT    NOT NULL,
    schedule_key   TEXT    NOT NULL,
    display_name   TEXT    NOT NULL,
    cron           TEXT    NOT NULL,
    zone           TEXT    NOT NULL,
    message        TEXT    NOT NULL,
    enabled        INTEGER NOT NULL DEFAULT 1,
    retired        INTEGER NOT NULL DEFAULT 0,
    next_run_at    INTEGER,
    last_run_at    INTEGER,
    last_status    TEXT,
    run_count      INTEGER NOT NULL DEFAULT 0,
    updated_at     INTEGER NOT NULL,
    CONSTRAINT uq_scheduled_tasks_profile_key UNIQUE (profile_name, schedule_key)
);

CREATE INDEX IF NOT EXISTS idx_scheduled_tasks_active
    ON scheduled_tasks (retired, profile_name, schedule_key);

-- task_executions：每次真正执行的审计历史，成功失败都记（技术方案 §8.5 / §9.2；第 28 节）。
CREATE TABLE IF NOT EXISTS task_executions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    schedule_id   INTEGER,
    session_id    TEXT    NOT NULL,
    started_at    INTEGER NOT NULL,
    success       INTEGER NOT NULL,
    error_message TEXT,
    duration_ms   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_task_executions_schedule
    ON task_executions (schedule_id, started_at);
