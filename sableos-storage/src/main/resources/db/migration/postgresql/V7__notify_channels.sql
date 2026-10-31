-- notify_channels：全局通知渠道注册表（技术方案 §6.8；第 28 节补齐）。
-- Agent 在 AGENT.md 正文按名引用；url 是 webhook 的权威地址，config_json 存类型相关多字段。
CREATE TABLE IF NOT EXISTS notify_channels (
    name        VARCHAR(255) PRIMARY KEY,
    type        VARCHAR(32)  NOT NULL,
    url         TEXT         NOT NULL,
    description TEXT,
    config_json TEXT         NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notify_channels_created_at ON notify_channels (created_at);
