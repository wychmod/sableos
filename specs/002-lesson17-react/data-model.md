# Data Model: ReAct 循环——Agent 的核心循环引擎

**Feature**: 002-lesson17-react | **Date**: 2026-10-14 | 上游：[spec.md](./spec.md) · [research.md](./research.md)

四类实体：**Session**（会话聚合，进程内承载，跨重启持久化归 18 节）、**SessionMessage**（会话内一条往来记录）、**tool_invocations**（工具调用审计表，随每次执行落库）、**Profile 消费视图**（本节消费第 16 节 Profile 的哪些字段）。前三者是本节建模重点，第四者只列「本节读哪些字段」。

## 1. Session——会话聚合

**来源/承载**：本节由调用方构造、`AgentService` 处理后就地累积；进程内 `InMemorySessionStore` 保存（`Map<String, Session>`）。跨重启持久化（`sessions` 表 + `SessionManager`）归 18 节，**本节不建 `sessions` 表**。字段口径按 18 节 data-model 预留，以免回改。

| 成员 | 类型 | 可变 | 说明 |
| --- | --- | --- | --- |
| id | String | 否 | 会话标识（`llm_calls.session_id` 与 `tool_invocations.session_id` 的取值来源） |
| profileName | String | 否 | 所属 Agent 运行配置名（`ProfileRegistry` 索引键） |
| channel | String | 否 | 触发渠道（`cli` / `web` / `scheduler`；可空。与 `TraceContext.CHANNEL_*` 同口径） |
| userId | String | 否 | 用户标识（可空） |
| messages | `List<SessionMessage>` | 增 | 对话历史；外部通过 `messages()` 取不可变视图 |
| status | String | 否 | 会话状态（本节固定 `active`；状态机归 18 节） |
| createdAt | Instant | 否 | 创建时间（构造时 `Instant.now()`） |
| lastActiveAt | Instant | 增 | 最后活跃时间（每次 append 刷新） |

**行为**：

- `appendUserMessage(String content)` → 追加一条 `USER` 消息；
- `appendAssistantMessage(ProviderResponse response)` → 追加一条 `ASSISTANT` 消息（`content` = `response.content()`，`toolCalls` = `response.toolCalls()`）；
- `appendToolResult(String toolName, ToolResult result)` → 追加一条 `TOOL` 消息（`content` = 成功时 `result.content()`，失败时 `result.errorMessage()`；`success` = `result.success()`）；
- `messages()` → 不可变视图（`List.copyOf` 或 `Collections.unmodifiableList`）。

**不变量**：`id` / `profileName` 非空；`messages` 只增不减；时间戳单调不减。

## 2. SessionMessage——会话内一条往来记录

**来源**：由 `Session.appendXxx` 构造，只读。

| 成员 | 类型 | 说明 |
| --- | --- | --- |
| role | `MessageRole`（`USER` / `ASSISTANT` / `TOOL`） | 角色 |
| content | String | 文本（工具结果条目：成功为结果内容/失败为原因） |
| toolCalls | `List<ToolCallRequest>` | 仅 `ASSISTANT` 消息可携带模型提出的工具调用（其余为空表） |
| toolName | String | 仅 `TOOL` 消息携带（哪个工具的结果） |
| success | Boolean | 仅 `TOOL` 消息携带（工具成败）；其余为 null |

**静态工厂**：`user(String content)` / `assistant(ProviderResponse response)` / `tool(String toolName, ToolResult result)`。

**渲染（供 PromptBuilder，D-12）**：

| role | 渲染前缀 |
| --- | --- |
| USER | `User: ` |
| ASSISTANT | `Assistant: `（若带 toolCalls，附一行 `(调用工具: <names>)`） |
| TOOL | `Tool[<toolName>]: ` |

## 3. tool_invocations——工具调用审计表

**口径**：每次 `ToolExecutor.execute` 调用**成败各一条**；表结构以 `db/migration/{vendor}/V2__tool_invocations.sql` 为唯一权威（禁 ddl-auto）。JPA 实体 `ToolInvocation` + `ToolInvocationRepository` 映射本表。列集与类型对齐 16 节 `V1__llm_calls.sql`（口径取自 `specs/001-runtime-core` data-model §2.2，经 `git show 17e2cfc^` 取回）。

| 列 | SQLite 类型 | PG 类型 | 可空 | 语义 |
| --- | --- | --- | --- | --- |
| id | INTEGER | BIGSERIAL | 否 | 主键（IDENTITY） |
| session_id | TEXT | VARCHAR(255) | 否 | 会话标识（与 `llm_calls.session_id` 同源；`sessions` 表 18 节才落，暂无 FK） |
| tool_name | TEXT | VARCHAR(128) | 否 | 工具名（模型给出、`ToolCatalog` 未解析到也原样记录） |
| input_json | TEXT | TEXT | 是 | 工具入参原始 JSON 文本（模型给出的 `argumentsJson` 原文；无入参/未解析到工具时为 null） |
| result_json | TEXT | TEXT | 是 | 工具结果 JSON（成功时结果内容；失败时为 null，原因走 `error_message`） |
| success | INTEGER(0/1) | BOOLEAN | 否 | 成功标识 |
| error_message | TEXT | TEXT | 是 | 失败原因（成功恒 null）；写库前 CRLF 净化 |
| duration_ms | INTEGER | BIGINT | 否 | 执行耗时（毫秒，含解析与执行，不含审计写入） |
| trace_id | TEXT | VARCHAR(64) | 是 | 链路标识：写入方取 `TraceContext.traceId()`，无上下文为 null；**不用 `UUID.randomUUID()`** |
| created_at | INTEGER（epoch 毫秒） | TIMESTAMPTZ | 否 | 落库时间（`@PrePersist` 生成；Instant 以 setTimestamp 绑定，sqlite-jdbc 只对 INTEGER 毫秒可读写往返） |

**索引**：`idx_tool_invocations_session_id (session_id)`、`idx_tool_invocations_trace_id (trace_id)`——两轨同名。

**与后续节的接缝**：`sessions`（18 节）、`memory_entries`（22 节）按追加迁移进双轨目录，不回改本节脚本；`session_id` 的 FK 约束待 `sessions` 表落地后再评估。

## 4. Profile 消费视图（本节读哪些字段）

本节不新建 Profile——只**消费**第 16 节交付的 `Profile`（record）。字段使用面：

| Profile 字段 | 本节用途 |
| --- | --- |
| `name()` | `AgentService` 以 `session.profileName()` 从 `ProfileRegistry` 取回；`ContextLoader` 定位 `.sableos/agents/<name>/` |
| `identity().prompt()` | `ContextLoader` 拼进 system prompt（角色设定） |
| `bootstrap()` | `ContextLoader` 读启动信息文件（缺失 WARN） |
| `settings().maxIterations()` | `ReActLoop` 迭代上限（默认 10） |
| `settings().maxHistoryTurns()` | `PromptBuilder` 历史截断条数（默认 20） |
| `provider()` | 由第 16 节 `ProviderService` 消费（本节透传 Profile） |

**注意**：`Profile` **不含** `skills` 字段——Skill 绑定是 Agent 目录内的相对软连接（项目约定，见《技术方案》§11.1 与 AGENTS.md §6），由 `ContextLoader` 扫描目录得到，不由 Profile 承载（research D-11 / 附录 B(B)）。

## 5. 状态迁移

| 实体 | 状态机 |
| --- | --- |
| Session | 本节无状态机（`status` 恒 `active`，只增历史）——18 节引入 `sessions` 表与生命周期时再补 |
| SessionMessage | 追加即终态（不可变记录） |
| tool_invocations | 只追加（每条记录一次执行，不改不删） |

## 6. 与前序 data-model 的关系

- 本节 `tool_invocations` 与 16 节 `llm_calls` **并列**，同属审计三表（第三表 `sessions` 归 18 节）；两表 `session_id` 同源、`trace_id` 同口径。
- 本节的 `Session` 是 18 节 `sessions` 表的**内存前身**；字段口径一致，18 节落地时以迁移追加而非重建。
