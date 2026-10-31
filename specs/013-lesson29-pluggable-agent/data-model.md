# Data Model: 插件化 Agent

**Feature**: `specs/013-lesson29-pluggable-agent` | **Date**: 2026-10-31

> **本节不新增数据表、不新增迁移、不新增实体。** 全部"数据"都是文件系统里的 Agent 目录与内存中的索引。

## 1. 文件系统模型：一个 Agent 目录

```text
<workspace>/agents/<name>/
├── AGENT.md              # 必需。frontmatter = 运行配置；正文 = 任务指令
├── REFERENCE.md          # 可选。参考资料，不预载，经 read_file 按需读
├── skills/<skill-name>   # 可选。指向 <workspace>/skills/<skill-name>/ 的相对软连接
│                         #   注意：必须是软连接，普通 .md 会被判越界（research D-04）
└── scripts/*             # 可选。脚本，不预载，经 shell 按需跑；代码不进上下文
```

**约束**（均来自既有实现，本节不新增）：

| 约束 | 来源 | 违反后果 |
| --- | --- | --- |
| `AGENT.md` 必须存在 | `AgentLoader.loadAgent` | 该 Agent 跳过 + ERROR 日志 |
| frontmatter 必须闭合 | `AgentLoader.parseFrontmatter` | 同上 |
| frontmatter 的 `name` 必须等于目录名 | `AgentLoader.deriveProfile` | 同上 |
| `provider.name` 必须在 `sableos.providers` 清单内 | `AgentLoader.deriveProfile` | 同上 |
| `provider.model` 非空 | `AgentLoader.deriveProfile` | 同上 |
| `skills/` 下每个条目必须是指向公共 Skill 根的软连接且含 `SKILL.md` | `ContextLoader.resolveSkill` | **整个 system prompt 构造抛异常** |

## 2. frontmatter 字段（既有 `Profile` 记录，本节冻结不改）

| 字段 | 类型 | 对应 `Profile` 成员 | 本节动作 |
| --- | --- | --- | --- |
| `name` | String | `name` | 沿用 |
| `description` | String | `description` | 沿用 |
| `identity.agent_name` / `identity.prompt` | String | `identity` | 沿用 |
| `provider.name` / `.model` / `.temperature` | String/String/Double | `provider` | 沿用 |
| `tools` | List\<String\> | `tools` | **新增**：与已注册工具名单比对，未命中告警 |
| `mcp_servers` | List\<String\> | `mcpServers` | 沿用 |
| `channels` | List\<String\> | `channels` | 沿用 |
| `schedules[].id / cron / zone / message` | List\<Map\> | `schedules` | 沿用（FR-006 要求原样透传） |
| `bootstrap` | List\<String\> | `bootstrap` | 沿用 |
| `settings.max_iterations` / `max_history_turns` | Integer | `settings` | 沿用 |

**明确不在 frontmatter 中**：`notify_channels`（技术方案 §6.8 / §11.1；渠道走全局注册表，正文按名引用）、`skills`（软连接集合是唯一绑定真相源，AGENTS §6）。

## 3. 内存模型

### 3.1 `ProfileRegistry`（既有类，本节补一个方法）

| 成员 | 签名 | 状态 |
| --- | --- | --- |
| `register` | `void register(Profile)` | 既有，同名覆盖 |
| `get` | `Profile get(String)` | 既有，未注册返回 `null`（语义冻结） |
| `exists` | `boolean exists(String)` | 既有 |
| `all` | `List<Profile> all()` | 既有，返回快照 |
| `remove` | `boolean remove(String)` | **新增**：存在并移除返回 `true` |

底层已为 `ConcurrentHashMap`（16 节即如此），本节不改存储结构。

### 3.2 `AgentScheduler`（既有类，本节补三个成员）

| 成员 | 签名 | 状态 |
| --- | --- | --- |
| `registerAll` | `void registerAll()` | 既有，改为逐条委派 `registerProfile` |
| `registerProfile` | `void registerProfile(Profile)` | **新增**：登记该 Agent 的全部 schedule |
| `scheduledTasks` | `ConcurrentMap<Long, ScheduledFuture<?>>` | **新增**：`scheduleId` → 句柄 |
| `hasScheduledTask` | `boolean hasScheduledTask(long scheduleId)` | **新增**：句柄存在性查询 |
| `registeredTasks` | `ConcurrentMap<Long, RegisteredTask>` | 既有，保留（承载 profile/schedule/future） |
| `taskLocks` / `sessionLocks` | `ConcurrentMap<...>` | 既有，不动 |

### 3.3 `AgentLoader`（既有类，本节补两处）

| 成员 | 签名 | 状态 |
| --- | --- | --- |
| 构造器 | `AgentLoader(Path, Set<String>)` | 既有，保留（工具名单为空集，即不告警） |
| 构造器 | `AgentLoader(Path, Set<String>, Set<String>)` | **新增**：第三参为已注册工具名集合 |
| `loadAll` | `ProfileRegistry loadAll()` | 既有，内部改调 `deriveProfile(Path)` |
| `deriveProfile` | `Profile deriveProfile(Path agentDir)` | **新增**：按目录派生（第 30 节与测试直接用） |
| `deriveProfile` | `Profile deriveProfile(String dirName, String markdown)` | 既有，保留 |

## 4. 状态流转：登记与句柄

```text
启动扫描：
  loadAll()
    └─ for each dir: deriveProfile(Path)   ──(非法)──> ERROR 日志, 跳过该 Agent
                     └─ warnUnknownTools   ──(未注册工具)──> WARN 日志, 继续
                     └─ registry.register
  [装配层] AgentScheduler.registerAll()
    └─ for each profile: registerProfile(profile)
         └─ for each schedule:
              reconcile(...) -> scheduleId
              schedule(...)  -> future
              scheduledTasks.put(scheduleId, future)
                   └─ 旧句柄非空且不同 -> previous.cancel(false)

运行期新增（第 30 节）：
  deriveProfile(Path) -> registry.register -> scheduler.registerProfile
  （与启动扫描同一段代码，因此异常类型与消息完全一致）

运行期移除（第 30 节）：
  scheduler 注销该 profile 的句柄 -> registry.remove(name)
```

## 5. 无数据库变更声明

- 无新表、无新列、无新迁移脚本（`db/migration/{sqlite,postgresql}` 本轮零改动）。
- 定时任务的持久化仍走第 28 节的 `scheduled_tasks` / `task_executions`，本节不改其 schema。
