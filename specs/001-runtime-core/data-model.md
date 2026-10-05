# Phase 1 数据模型：SableOS 运行时内核

**范围**：本功能（spec = `spec.md`）落地的实体与存储形态。凡标注「后续」的，只在本文件登记契约位置，不在本次实现。

## 1. 存储划分

| 数据 | 载体 | 理由 |
| --- | --- | --- |
| 会话、工具调用记录、模型调用记录、通知渠道、长期记忆（SQLite 档） | SQLite（JPA + Flyway 双轨迁移） | 需要查询、需要跨重启保留、需要结构化字段 |
| Agent 定义、Bootstrap、长期记忆（默认档）、MCP 配置、日志 | 文件系统 `.sableos/` | 用户可直接编辑、可 git 跟踪、可备份 |

## 2. 关系型实体（SQLite）

迁移脚本目录：`sableos-storage/src/main/resources/db/migration/{vendor}/`（SQLite 为默认档）。

### 2.1 `sessions`（本次实现）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `session_id` | VARCHAR | 主键，`channel + user + profile` 联合生成 |
| `profile_name` | VARCHAR | 关联的 Agent 运行配置名 |
| `channel` | VARCHAR | 触发渠道（`cli` / `web` / `scheduler`） |
| `user_id` | VARCHAR | 用户标识（CLI 取本机用户名、定时固定 `scheduler`、HTTP 由请求参数提供且缺省 `anonymous`） |
| `messages_json` | TEXT | 对话历史（JSON 序列化；含模型输出与工具结果） |
| `status` | VARCHAR | `active` / `archived` |
| `created_at` / `last_active_at` / `archived_at` | TIMESTAMP | 归档时间可空 |

**状态流转**：`active` --（归档，`DELETE /api/v1/sessions/{id}`）--> `archived`；归档后不再追加消息，历史仍可查询。

**校验规则**：同一 `(channel, user_id, profile_name)` 组合复用同一会话；消息单条最大 32KB；查询历史默认返回最近 100 条。

### 2.2 `tool_invocations`（本次实现，只写不查）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT | 主键，自增 |
| `session_id` | VARCHAR | 关联会话 |
| `tool_name` | VARCHAR | 工具名 |
| `input_json` | TEXT | 入参（JSON） |
| `result_json` | TEXT | 结果（JSON；失败时可为空） |
| `success` | BOOLEAN | 是否成功（被沙箱拒绝 = false） |
| `error_message` | TEXT | 失败原因（可空） |
| `duration_ms` | BIGINT | 执行耗时 |
| `created_at` | TIMESTAMP | 调用时间 |

### 2.3 `llm_calls`（本次实现，只写不查）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT | 主键，自增 |
| `session_id` | VARCHAR | 关联会话 |
| `provider` | VARCHAR | provider name（与显式映射表同名） |
| `model` | VARCHAR | 模型名 |
| `prompt_tokens` / `completion_tokens` / `total_tokens` | INT | 用量（取不到时记 0 或空） |
| `duration_ms` | BIGINT | 调用耗时 |
| `created_at` | TIMESTAMP | 调用时间 |

### 2.4 `notify_channels`（本次实现：建表 + 启动同步 + 读取）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | VARCHAR | 主键，Agent 正文按名引用 |
| `type` | VARCHAR | `webhook` / `feishu` / `dingtalk` 等 HTTP 类（本次唯一实现类型）；`email` 为后续 |
| `url` | VARCHAR | HTTP 类渠道的地址 |
| `description` | VARCHAR | 可选说明 |
| `config_json` | TEXT | 类型相关的多字段配置（email 的 host/port/from/to 等；HTTP 类可为空） |
| `created_at` / `updated_at` | TIMESTAMP | 时间戳 |

**定义源与同步（本次的关键约定）**：渠道的**定义源是工作区文件 `.sableos/notify_channels.yaml`**（用户可直接编辑），启动时读取并同步进本表——文件里新增/改动的 upsert，文件里移除的标记退役；表是运行时视图，不是定义源。这与定时任务"定义在文件、状态在库"的处理方式一致。

**约束**：本次只提供读取（供 `NotifyTools` 解析适配器与地址）与启动同步；管理端点（CRUD）属后续功能。凭证类字段（如 email 密码）支持 `${ENV_VAR}` 占位符，加载时从环境变量解析，不明文落库。

### 2.5 `memory_entries`（本次实现，SQLite 记忆档）

仅在 `memory.backend = sqlite` 时使用；默认档是文件形态（见 3.4 节），两者共用同一套核心区/归档区语义。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT | 主键，自增 |
| `profile_name` | VARCHAR | 归属 Agent 的运行配置名（记忆按 Agent 隔离） |
| `scope` | VARCHAR | `CORE` / `ARCHIVAL`（核心区用 `WHERE scope='CORE'` 全量取） |
| `content` | TEXT | 记忆正文 |
| `created_at` / `updated_at` | TIMESTAMP | 时间戳 |

**行为契约（与文件档一致）**：不缓存（每次查库）；核心区永不截断，截断 = 归档查询 `LIMIT N`；关键词检索 = 归档区 `LIKE` 匹配、不区分大小写；写入分区由调用方显式指定，缺省归档区。
**留白**：向量列与索引属 015 语义检索升级，本次不建。

### 2.6 后续实体的契约位置（本次不实现）

| 实体 | 归属 | 说明 |
| --- | --- | --- |
| `scheduled_tasks` / `task_executions` | 后续功能 | 定时任务的状态持久化与管理端点（项目方已选定最小形态，见 spec 澄清记录第 1 条） |
| `memory_entries` 的向量列与索引 | 后续功能 | 015 语义检索升级（见 `research.md` D-04） |

## 3. 文件系统实体（`.sableos/`）

### 3.1 工作区布局（`sableos init` 产出）

```text
.sableos/
├── agents/            # 每个子目录 = 一个 Agent（AGENT.md + skills/ 软连接 + scripts/ + REFERENCE.md）
├── skills/            # 公共 Skill 实体库（SKILL.md + 可选附属资源）
├── memory/
│   └── MEMORY.md      # 长期记忆（核心记忆区 / 归档记忆区）
├── mcp_servers.yaml   # MCP server 声明
├── notify_channels.yaml  # 通知出站渠道定义（本次新增；启动时同步进 notify_channels 表）
├── sessions/          # 会话导出目录（工作区约定留白）
├── logs/              # 日志
├── AGENTS.md          # Bootstrap：底座级指令
├── SOUL.md            # Bootstrap：人格设定
├── USER.md            # Bootstrap：用户初始设定（底座只读不写）
└── sableos.db         # SQLite 库文件
```

> 初始化时同时生成一份**带注释的最小白名单**（示例域名、工作区目录、少量命令），使新工作区无需手工配置即可跑通演示场景。

### 3.2 `AGENT.md`（一个目录 = 一个 Agent）

**frontmatter → 运行配置（Profile）字段映射**：

| frontmatter 字段 | 含义 | 约束 |
| --- | --- | --- |
| `name` / `description` | 标识与说明 | `name` 与目录名一致 |
| `identity`（`agent_name`、`prompt`） | 身份与人格提示 | — |
| `provider`（`name`、`model`、`temperature`） | 模型提供方 | `name` 必须在显式映射表中注册，否则该 Agent 加载失败并记录日志 |
| `tools` | 可用工具子集 | 必须是 `ToolRegistry` 中已注册的工具名 |
| `mcp_servers` | 该 Agent 可用的 MCP server | 需在 `mcp_servers.yaml` 中声明 |
| `channels` | 接入渠道 | 核心阶段支持 `cli` / `web`（IM 渠道为后续） |
| `schedules` | 定时触发（cron、时区、消息） | 只在 frontmatter 声明；改动需重启或重新加载 |
| `bootstrap` | 引用的 Bootstrap 文件 | 文件必须存在 |
| `settings`（`max_iterations`、`max_history_turns`） | 循环与历史上限 | 缺省 `max_iterations=10`、`max_history_turns=20` |

**正文**：该 Agent 的任务指令，全量注入 system prompt。
**不含** `notify_channels` 字段（通知渠道按名在正文中引用全局注册表）。

**目录内的可选资源**：

- `skills/<name>` → 指向 `.sableos/skills/<name>/` 的**相对软连接**（唯一绑定真相源；真实目标必须位于公共 Skill 根内）
- `scripts/` → 脚本资源，经底座既有的 `read_file` / `shell` 按需取用
- `REFERENCE.md` → 参考材料，同上按需读取

### 3.3 `SKILL.md`（公共 Skill）

frontmatter 至少含 `name` 与 `description`；正文按需读取。Prompt 每轮只注入**已绑定** Skill 的 `name` / `description` / Agent 本地绝对路径，正文与附属资源不预载。

### 3.4 `MEMORY.md`（默认长期记忆后端）

```markdown
## 核心记忆
（永不被截断；不参与检索；每次全量注入）

## 归档记忆
（可被截断；关键词检索只在这一区匹配，不区分大小写）
```

**行为契约**：① 不缓存，每次重新读文件；② 核心区永不截断，截断只作用于归档区；③ 写入哪个区由调用方通过 `scope` 显式指定（缺省归档区），系统不猜；④ 未配置向量化时行为与关键词检索一致（语义检索为后续功能）。

**异常降级**：文件不存在 = 空记忆（正常路径，不报错）；文件存在但无法解析或结构非法 = 按纯文本整体视作归档区内容，核心区视为空，并在日志中给出明确告警；任何情况下不得因记忆文件异常而中断对话。

### 3.5 `mcp_servers.yaml`

每项声明 `name`、`transport`、`command`、`env`；启动时连接并调 `tools/list`，把每个 MCP 工具包装为 `SableTool` 注册进 `ToolRegistry`，并处理失联、超时与错误恢复。

### 3.6 `notify_channels.yaml`（本次新增）

通知出站渠道的定义源。每项声明 `name`、`type`、`url`（HTTP 类）或 `config`（多字段类型，如后续的 email），可选 `description`；凭证支持 `${ENV_VAR}`。启动时读取并同步进 `notify_channels` 表（新增/改动 upsert、移除的标记退役），`sableos init` 会生成一份带注释的模板。

## 4. 内存态实体

| 实体 | 说明 | 生命周期 |
| --- | --- | --- |
| `Profile` | 由 `AGENT.md` frontmatter 派生，注册进 `ProfileRegistry` 按名索引 | 启动扫描 + 每次组装上下文现读（无缓存） |
| `ProfileContext` | 当前处理所属的 Profile（线程级；虚拟线程下天然隔离） | 一次 `AgentService.process` 调用内 |
| `ToolRegistry` | 所有 `SableTool` 实例的注册表（内置 + `@Tool` + MCP） | 进程级 |
| `SableTool` | 统一工具抽象：`getName` / `getDescription` / `getInputSchema` / `execute` | 进程级 |
| `ToolResult` | 单次工具执行结果：成功标识、内容、错误信息、是否可重试 | 单次调用 |
| 调度句柄表 | `AgentScheduler` 内按任务句柄注册的 cron 任务 | 进程级（重启后按 frontmatter 重新注册） |
