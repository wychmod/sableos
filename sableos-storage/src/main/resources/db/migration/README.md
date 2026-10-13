# 数据库迁移（Flyway 双轨）

> 本目录是表结构的**唯一权威真相源**（工程约定，见 AGENTS.md §5.1；宪章只管"审计 day one 落库"，见原则 VI）：建表 / 改表只改这里，
> 禁止用 JPA `ddl-auto` 或运行时手写 SQL 改结构。

- `sqlite/`：默认档，零配置本地运行（`jdbc:sqlite:.sableos/sableos.db`）。
- `postgresql/`：部署选项，与 SQLite 轨语义一致、按各方言语法改写。

命名规约：`V{序号}__{描述}.sql`（只追加、不改历史脚本），两轨版本号一一对应。

预留位：`V1__init.sql` 由 T015（US1）落地，含五张表 `sessions`、`tool_invocations`、
`llm_calls`、`notify_channels`、`memory_entries`——字段与约束照
`specs/001-runtime-core/data-model.md` 第 2 节。

与 `application.yaml` 的联动（`{vendor}` 按 datasource url 自动展开）：

```yaml
spring:
  flyway:
    locations: classpath:db/migration/{vendor}
```

新增迁移时的自检：两轨脚本一一对应、版本号一致、都能在空库上从零执行。
