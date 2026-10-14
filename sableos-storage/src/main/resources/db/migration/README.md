# 数据库迁移（Flyway 双轨）

> 本目录是表结构的**唯一权威真相源**（工程约定，见 AGENTS.md §5.1；宪章只管"审计 day one 落库"，见原则 VI）：建表 / 改表只改这里，
> 禁止用 JPA `ddl-auto` 或运行时手写 SQL 改结构。

- `sqlite/`：默认档，零配置本地运行（`jdbc:sqlite:.sableos/sableos.db`）。
- `postgresql/`：部署选项，与 SQLite 轨语义一致、按各方言语法改写。

命名规约：`V{序号}__{描述}.sql`（只追加、不改历史脚本），两轨版本号一一对应。

按节追加策略：每节把本节的表以**追加迁移**落进两轨目录（版本号从 `V1` 起按落地顺序递增），
已执行过的脚本永不修改。已知追加计划：`V1__llm_calls.sql`（16 节 Provider）、`tool_invocations`（17 节）、
`sessions`（18 节）、`memory_entries`（22 节，落哪个模块以对应节 plan 为准）。

与 `application.yaml` 的联动（`{vendor}` 按 datasource url 自动展开）：

```yaml
spring:
  flyway:
    locations: classpath:db/migration/{vendor}
```

新增迁移时的自检：两轨脚本一一对应、版本号一致、都能在空库上从零执行。
