# Data Model: Agent Provider——对接大模型的统一入口

**Feature**: 001-lesson16-provider | **Date**: 2026-10-14 | 上游：[spec.md](./spec.md) · [research.md](./research.md)

三类实体：**Profile**（Agent 运行配置，启动期从 `AGENT.md` 派生、内存索引）、**ProviderConfig**（全局层 provider 声明，启动期从 `application.yaml` 绑定）、**llm_calls**（调用审计表，随每次调用落库）。前两者是配置模型（不落库），第三者唯一进 SQLite/PG。

## 1. Profile——一个 Agent 的完整运行声明

**来源**：`.sableos/agents/<name>/AGENT.md` 的 frontmatter（`---` 围栏内 YAML），由 `AgentLoader.deriveProfile` 解析；进程内以 `ProfileRegistry`（`Map<String, Profile>`）按 name 索引。**字段集以《技术方案》§8.2 为唯一权威**（不含 `skills`、`notify_channels`，见 research 附录 B(B)）。

### 1.1 顶层字段

| 字段 | 类型 | 必填 | frontmatter 键 | 说明 |
| --- | --- | --- | --- | --- |
| name | String（record） | ✅ | `name` | 必须与目录名一致，不一致 = 坏目录 |
| description | String | — | `description` | Agent 简述 |
| identity | Identity | — | `identity` | 见 1.2 |
| provider | ProviderRef | ✅ | `provider` | 见 1.3；缺省或 provider 名未登记 = 坏目录 |
| tools | List\<String\> | — | `tools` | 工具名列表；本节只承载，20 节起消费（不校验注册与否） |
| mcp_servers | List\<String\> | — | `mcp_servers` | MCP server 名列表；20 节起消费 |
| channels | List\<String\> | — | `channels` | 渠道名列表；18/26 节起消费 |
| schedules | List\<ScheduleConfig\> | — | `schedules` | 见 1.4；25 节起消费 |
| bootstrap | List\<String\> | — | `bootstrap` | Bootstrap 文件名列表；17 节 ContextLoader 消费 |
| settings | Settings | — | `settings` | 见 1.5，有默认值 |

### 1.2 Identity（嵌套）

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| agent_name | String | — | 展示用 Agent 名（可与 name 不同） |
| prompt | String | — | 主提示词/角色描述 |

### 1.3 ProviderRef（嵌套）——Profile 与 ProviderConfig 的连接点

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| name | String | ✅ | 实例全局清单中的 provider 名；**加载期校验存在性，不存在即整个 Agent 失败并点名** |
| model | String | ✅ | 该 Agent 使用的模型名（如 `deepseek-chat`、`qwen-max`）；换模型 = 只改此行 |
| temperature | Double | — | 采样温度；null = 用供应商默认 |

### 1.4 ScheduleConfig（嵌套）

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | ✅ | 任务键（Agent 内唯一） |
| cron | String | ✅ | cron 表达式 |
| zone | String | — | 时区，缺省用运行环境 |
| message | String | ✅ | 到点发给 Agent 的消息内容 |

本节只做结构解析（深层校验随 25 节 `AgentScheduler` 到位后补）。

### 1.5 Settings（嵌套，含默认值）

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| max_iterations | int | 10 | ReAct 单轮最大迭代数（17 节消费） |
| max_history_turns | int | 20 | 历史轮数上限（17 节消费） |

### 1.6 Profile 校验规则（本节范围）

| 校验 | 失败后果 | 对应需求 |
| --- | --- | --- |
| 目录下存在 `AGENT.md` | 该目录记错误日志、跳过 | FR-001 |
| frontmatter 可被 YAML 解析且 `name` == 目录名 | 同上（坏文件不阻断其余） | FR-001 / SC-004 |
| `provider` 存在且 `provider.name` 在全局清单内 | 同上；错误信息点名 provider 与 Agent | FR-002 |
| `provider.model` 非空 | 同上 | FR-003 |

其余字段（tools/channels/bootstrap/schedules）本节**只解析不校验**——各自模块到位时补校验（§8.2「后面各节的字段各自补自己的校验规则」）。

## 2. ProviderConfig——实例级 provider 声明

**来源**：`application.yaml` 的 `sableos.providers[]`，绑定到 `ProvidersProperties`（record）；启动期 `validate()` 失败快。

| 字段 | 类型 | 必填 | YAML 键 | 说明 |
| --- | --- | --- | --- | --- |
| name | String | ✅ | `name` | 全局唯一（重复 = 启动失败）；Profile 引用的就是它 |
| type | String | — | `type` | `openai`（缺省）\| `dashscope`；其余取值 = 启动失败并点名 |
| apiKey | String | ✅ | `api-key` | `${ENV_VAR:}` 占位；空白或含未展开 `${` = 启动失败并点名 |
| baseUrl | String | openai 型 ✅ / dashscope 型 可选 | `base-url` | OpenAI 兼容端点地址（如 `https://api.deepseek.com`）；工厂层归一（去尾 `/` 与 `/v1`） |

**空清单合法**：默认无任何条目时系统正常启动（无 key 环境/CI），任何调用尝试走到未登记 provider 会得到点名异常。

### 2.x 与 Profile 的关系

```text
Profile.provider.name ──按名引用──▶ ProviderConfig.name（加载期校验存在性）
Profile.provider.model ──每次调用时──▶ ChatModel 请求的 model 字段
```

- `ProviderConfig` = 「连不连得上」（连接与凭证）；`Profile.provider` = 「怎么用」（模型与温度）。两层各自独立改动。

## 3. llm_calls——LLM 调用审计表

**口径**：每次 `ProviderService.chat` 调用**成败各一条**；表结构以 `db/migration/{vendor}/V1__llm_calls.sql` 为唯一权威（禁 ddl-auto）。JPA 实体 `LlmCall` + `LlmCallRepository` 映射本表。

| 列 | SQLite 类型 | PG 类型 | 可空 | 语义 |
| --- | --- | --- | --- | --- |
| id | INTEGER | BIGSERIAL | 否 | 主键（IDENTITY） |
| session_id | TEXT | VARCHAR(255) | 否 | 会话标识（调用方传入；`sessions` 表 18 节才落，暂无 FK） |
| provider | TEXT | VARCHAR(64) | 否 | provider 名（Profile 声明值） |
| model | TEXT | VARCHAR(128) | 否 | 模型名（Profile 声明值） |
| prompt_tokens | INTEGER | INTEGER | 是 | 输入 token（供应商未返回时 null） |
| completion_tokens | INTEGER | INTEGER | 是 | 输出 token（同上） |
| total_tokens | INTEGER | INTEGER | 是 | 合计 token（同上） |
| trace_id | TEXT | VARCHAR(64) | 是 | 链路标识：写入方取 `TraceContext.traceId()`，无上下文为 null；**不用 `UUID.randomUUID()`** |
| success | INTEGER(0/1) | BOOLEAN | 否 | 成功标识 |
| error_message | TEXT | TEXT | 是 | 失败原因（成功恒 null）；写库前 CRLF 净化 |
| duration_ms | INTEGER | BIGINT | 否 | 调用耗时（毫秒，含翻译与调用，不含审计写入） |
| created_at | INTEGER（epoch 毫秒） | TIMESTAMPTZ | 否 | 落库时间（`@PrePersist` 生成；Instant 以 setTimestamp 绑定，sqlite-jdbc 只对 INTEGER 毫秒可读写往返） |

**索引**：`(session_id)`（按会话查）、`(trace_id)`（按链路查）——两轨同名。

**与后续节的接缝**：`tool_invocations`（17 节）、`sessions`（18 节）、成本列（扩展阶段）都按**追加迁移**进双轨目录，不回改本节脚本；`session_id` 的外键约束待 `sessions` 表落地后再评估（SQLite 弱 ALTER，届时以新迁移处理）。

## 4. 状态迁移

本节实体均无生命周期状态机（Profile 启动期一次性装载、ProviderConfig 启动期一次性绑定、llm_calls 只追加）——后继节引入运行时注册/热加载时再补。
