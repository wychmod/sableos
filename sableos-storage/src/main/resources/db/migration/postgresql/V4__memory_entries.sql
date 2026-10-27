-- memory_entries：长期记忆条目（技术方案 §5.1；data-model.md）。
-- 手工维护脚本是表结构唯一权威——禁止用 ddl-auto 改结构。
CREATE TABLE IF NOT EXISTS memory_entries (
    id           BIGSERIAL PRIMARY KEY,
    profile_name TEXT        NOT NULL,
    scope        VARCHAR(16) NOT NULL,
    content      TEXT        NOT NULL,
    created_at   TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_profile_scope ON memory_entries (profile_name, scope);
