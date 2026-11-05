# Data Model: 动态管理 Agent（第 30 节）

> 本节**不新增数据库表**。Agent 定义与记忆是文件；运行态仍是内存注册表 + 既有 `scheduled_tasks`。以下按「文件布局 → 领域对象 → 对外 DTO」三层列出。

## 1. 文件布局（唯一真相源）

```text
<sableos.root>/                     # 默认 .sableos，可被 sableos.root / WORKSPACE_ROOT 覆盖
├── agents/
│   └── <name>/                     # 一个目录 = 一个 Agent
│       ├── AGENT.md                # 必需：frontmatter = Profile，正文 = 任务指令
│       ├── MEMORY.md               # 新增：该 Agent 的长期记忆（per-agent，D-09）
│       ├── REFERENCE.md            # 脚手架可选：参考资料
│       ├── scripts/                # 脚手架可选：脚本
│       │   └── example.py
│       └── skills/<skill>          # 只许软连接（29 节约定）
├── archive/
│   └── <name>/                     # DELETE 后的归宿（不物理删）
│   └── <name>-<yyyyMMddHHmmss>/    # 同名重复归档时的后缀形态
├── memory/<profileName>/MEMORY.md  # 回退路径（无 Agent 上下文时使用，保持既有行为）
└── sableos.db                      # 不在文件浏览器暴露
```

**约束**
- `name` 合法性：`^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$`，且不得为 `.` / `..`。
- `agents/<name>/` 内任何写入路径 `normalize()` 后必须仍在该目录内。
- `archive/` 只增不改；同名归档追加时间戳后缀，**绝不覆盖**已有归档。

## 2. 领域对象（core）

### 2.1 `AgentStore`（新增）

| 方法 | 语义 | 失败语义 |
| --- | --- | --- |
| `Path root()` | 工作区根（绝对、normalize） | — |
| `Path agentsDir()` / `Path archiveDir()` | 两棵子树根 | — |
| `Path writeAll(String name, Map<String,String> files)` | 写整组文件；相对路径防穿越；返回 Agent 目录 | name 非法 / 路径越界 → `IllegalArgumentException` |
| `void delete(Path agentDir)` | 回滚删除（仅用于本次创建的目录） | 不存在 → 静默 |
| `void archive(String name)` | 移入 `archive/`；同名追加时间戳后缀 | Agent 目录不存在 → `IllegalArgumentException` |
| `String read(String name)` | 读 `AGENT.md` | 缺失 → `IllegalStateException` |
| `Map<String,String> readAll(String name)` | 读目录内全部文本文件（相对路径 → 内容） | 缺失 → `IllegalArgumentException` |
| `Path resolveWithin(String relative)` | 工作区内的路径解析 + 边界校验（供 web 树/文件接口复用） | 越界 → `IllegalArgumentException` |

### 2.2 `AgentLifecycleService`（新增）

| 方法 | 语义 |
| --- | --- |
| `String generate(String name, String description)` | 一次模型调用 → 剥围栏 → 返回多文件草稿的**合并文本**（供预览）；不落盘、不注册；model 未配置 → `ProviderUnavailableException` |
| `Map<String,String> generateFiles(String name, String description)` | 同上但返回 `相对路径 → 内容`；每份 `AGENT.md` 过 `agentLoader.parse` 校验，非法 400 |
| `Profile create(String name, String description)` | name 冲突 → 400；`agentStore.writeAll` → `register` → 失败回滚 `delete` |
| `Profile register(Path agentDir)` | **唯一登记入口**：`deriveProfile` → `registry.register` → 有 schedules 则 `scheduler.registerProfile` |
| `Profile refresh(Path agentDir)` | 先 `unregisterProfile(旧)` 再 `register`（监听 MODIFY 用） |
| `void unregisterByDir(Path agentDir)` | 注销 + 移出注册表（监听 DELETE 用），不归档 |
| `Optional<Profile> get(String name)` / `List<Profile> list()` | 查询 |
| `Profile update(String name, String markdown)` | `parse` 预校验 → `saveFiles(name, Map.of("AGENT.md", markdown))` |
| `Profile saveFiles(String name, Map<String,String> files)` | `parse` 预校验 → `writeAll` → `refresh` |
| `void delete(String name)` | **定序**：`unregisterProfile` → `registry.remove` → `agentStore.archive` |

**不变量**
- 任何登记路径都只经 `register(Path)`；任何注销路径都先停定时再动索引。
- 注册表是内存事实源，磁盘是持久事实源；两者由启动扫描 + 监听 + API 维护同步。

### 2.3 `WorkspaceWatcher`（新增）

| 成员 | 语义 |
| --- | --- |
| `start()` | 建 `WatchService`，挂根目录 + 每个已存在子目录；起单守护线程循环 |
| `stop()` | 关 `WatchService`，令循环退出 |
| `dispatch(dir, changed, kind)`（包级） | 事件 → `handleChange`；单测直接调用 |
| `handleChange(agentDir, kind)`（包级） | DELETE → `unregisterByDir`；CREATE/MODIFY → `refresh`；异常只记 WARN |

### 2.4 `ToolExecutionContext`（新增）

```java
public final class ToolExecutionContext {
  void setAgentName(String name);
  String agentName();     // 可能为 null
  void clear();
}
```

- 线程级 `ThreadLocal`（非继承）；`ToolExecutor` 执行前后 `set`/`clear`（`finally`）。
- 读路径（`MemoryServiceImpl.buildContext` / `memorySection`）在委托 store 前后临时置入并复原。

## 3. 对外 DTO（web）

### 3.1 `AgentView`（列表/详情共用，替换既有 `ProfileView` 的角色）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | String | Agent 名（= 目录名） |
| `description` | String? | 描述 |
| `provider` | String? | provider 名 |
| `model` | String? | 模型名 |
| `tools` | List<String> | 工具名列表 |
| `channels` | List<String> | 渠道名列表 |
| `schedules` | List<ScheduleView0> | 定时声明（`id/cron/zone/message`） |
| `toolCount` / `scheduleCount` | int | 便于列表页展示，不引入额外查询 |

> **不包含** `identity.prompt`、`bootstrap`、`settings`：与 26 节 `ProfileView` 同口径（内部指令不对外）。

### 3.2 `AgentDetailView`

`AgentView` + `files`（`List<FileNode>`，该 Agent 目录树）+ `memoryPath`（相对路径 `agents/<name>/MEMORY.md`）。

### 3.3 `FileNode`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | String | 显示名 |
| `path` | String | 相对工作区根的路径，`/` 分隔 |
| `directory` | boolean | 是否目录 |
| `children` | List<FileNode> | 仅目录携带；文件为空表 |

### 3.4 请求体

| DTO | 字段 | 校验 |
| --- | --- | --- |
| `CreateAgentRequest` | `name`, `description` | name 非空 + 合法字符 + ≤64；description ≤ 2000 |
| `UpdateAgentRequest` | `content`（整份 `AGENT.md`） | 非空 |
| `GenerateFilesRequest` | `description` | 非空 + ≤2000 |
| `SaveFilesRequest` | `files: Map<String,String>` | 非空；必须含 `AGENT.md` |
| `WorkspaceWriteRequest` | `path`, `content` | path 非空且在 `agents/`/`archive/` 内 |
| `AgentSessionMessageRequest` | `content` | 非空 + ≤32KB（复用 `ApiConstraints`） |

### 3.5 `AgentSessionView`

复用既有 `SessionView`（26 节），不新增结构。

## 4. 配置键（新增）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `sableos.author.provider` | 空 → 取 `sableos.providers[0].name` | 生成 Agent 用的 provider |
| `sableos.author.model` | 空 | 生成 Agent 用的模型；留空则 generate 端点 503 |

## 5. 与既有数据的关系

| 既有对象 | 本节改动 |
| --- | --- |
| `scheduled_tasks` 表 | 不改结构；`unregisterProfile` 会调既有 `retire(scheduleId)` |
| `llm_calls` 表 | 不改结构；生成的每次调用由既有审计链写入（`session_id = agent-author:<name>`） |
| `tool_invocations` 表 | 不改结构 |
| `sessions` 表 | 不改结构；管理台固定会话就是一条普通 `admin:console:<name>` 行 |
| `LongTermMemoryStore` SPI | **签名不变**；仅 `MarkdownMemoryStore` 的路径解析按 Agent 上下文切换 |
