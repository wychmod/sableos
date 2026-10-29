-- sandbox_whitelist_entries：Sandbox 白名单条目（008-sandbox-whitelist-admin / data-model.md）。
-- 表结构唯一权威；禁止 ddl-auto 改结构。value 存归一后的形态，唯一索引才真正生效。
-- 时间列用 TIMESTAMP，与最近的 V4 对齐（V1~V3 用的是 TIMESTAMPTZ）。
CREATE TABLE IF NOT EXISTS sandbox_whitelist_entries (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(32)   NOT NULL,
    value       VARCHAR(1024) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_sandbox_whitelist_type_value
    ON sandbox_whitelist_entries (type, value);

-- sandbox_whitelist_seed：首次导入标记，单行表。
-- 行存在即"已从配置文件导入过"，与条目表是否为空无关——否则删光条目后重启会把配置再插一遍。
CREATE TABLE IF NOT EXISTS sandbox_whitelist_seed (
    id          SMALLINT  PRIMARY KEY CHECK (id = 1),
    seeded_at   TIMESTAMP NOT NULL,
    entry_count INTEGER   NOT NULL
);
