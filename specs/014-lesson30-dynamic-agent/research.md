# Research: 动态管理 Agent（第 30 节）

> 每条决策给出「问题 → 选择 → 理由 → 被否方案」。凡与课件/技术方案不一致处，标注偏离点。

## D-01 编排放哪：`AgentLifecycleService` 归 core

- **问题**：`AgetLifecycleService` 依赖 `AgentLoader`(core)、`ProfileRegistry`(core)、`AgentScheduler`(core)、`AgentStore`(新，文件)、`ProviderService`(core 契约)，但 web 侧的 404 语义（`ResourceNotFoundException`）在 `sableos-web`。
- **选择**：整个编排放 `com.sableos.core.agent.AgentLifecycleService`；`get` 返回 `Optional<Profile>`，由 web 层决定抛 404。定义非法统一抛 `IllegalArgumentException`（web 全局处理器已映射 400）。
- **理由**：`ArchitectureTest` 明确禁止 core 依赖 web；`AgentService` / `AgentLoader` 均已在 core，编排与它们同层便于复用同一套派生与校验。
- **被否**：放 web → 破坏模块方向；放 boot → `boot` 只做装配，且 boot 无法被 `sableos-web` 反向使用。

## D-02 文件存储语义：新增 `AgentStore`（core）

- **问题**：谁负责 `agents/<name>/` 的写、回滚、归档？课件示意里是 `agentStore.write / delete / archive`，但仓库里没有该类。
- **选择**：新增 `com.sableos.core.agent.AgentStore`：
  - `Path writeAll(String name, Map<String,String> files)`：按相对路径写入 Agent 目录，逐路径 `normalize()` 后必须落在该 Agent 目录内（防穿越）；返回目录。
  - `void delete(Path agentDir)`：回滚用，仅删除本次创建的目录。
  - `void archive(String name)`：整目录移到 `archive/<name>`；目标已存在时追加时间戳后缀，绝不覆盖历史归档。
  - `String read(String name)` / `Map<String,String> readAll(String name)`：供详情与文件页读取。
  - `Path agentsDir()` / `Path archiveDir()` / `Path root()`。
  - Agent 名合法性：`[A-Za-z0-9][A-Za-z0-9_.-]{0,63}`，且不得为 `.`/`..`、不得含分隔符。
- **理由**：文件语义与 Spring 无关，放 core 可脱离 web 直接单测；`AgentLifecycleService` 与 `WorkspaceApiController` 共用同一份边界校验，避免两套。
- **被否**：放进 `sableos-cli`（18 节装配根）→ web 模块无法用；放进 web → 监听器（core）也要用。

## D-03 两条录入路径收敛到 `register(Path)`

- **问题**：API 创建与监听事件若各写一套注册代码，行为必然漂移。
- **选择**：`AgentLifecycleService` 暴露 `public Profile register(Path agentDir)`：`agentLoader.deriveProfile(dir)` → `profileRegistry.register` → 有 `schedules` 则 `agentScheduler.registerProfile`。创建、监听 `refresh`、监听 `unregisterByDir` 全部走它或它的注销孪生。
- **理由**：课件 §2.1 / 技术方案 §11.3 都已点名「同一段代码」；29 节的 `deriveProfile(Path)` 正是为此准备的唯一派生入口。

## D-04 监听器形态：单守护线程 + 根目录与子目录双层 watch

- **问题**：JDK `WatchService` **不递归**。只盯 `agents/` 根目录，收不到 `agents/<name>/AGENT.md` 的改动；而「改 AGENT.md 即生效」是本节验收项。
- **选择**：`WorkspaceWatcher` 维护 `WatchKey → 被监听目录` 映射：
  - 根目录 watch：`ENTRY_CREATE`（新目录 → 补挂监听 + 试登记）、`ENTRY_DELETE`（子目录被删 → 注销）。
  - 每个 Agent 子目录各挂一个 watch：只认 `AGENT.md`（大小写不敏感）的 `CREATE/MODIFY`（登记或刷新）与 `DELETE`（注销）。
  - 监听循环跑在**单个守护线程**上（装配层 `ThreadPoolTaskExecutor`，`corePoolSize=1`、`daemon=true`），与 25 节调度线程同类。
  - 关闭：装配层监听 `ContextClosedEvent` 调 `stop()` 关 `WatchService`，令 `take()` 抛 `ClosedWatchServiceException` 退出循环。
- **理由**：单线程 + 逐事件 try/catch，天然满足「单个坏目录不拖垮监听」；不开线程池也避免事件乱序导致「先删后建」被倒置。
- **被否**：定时全量轮询 → 有延迟且空转；每目录一线程 → 线程数随 Agent 数增长。

## D-05 刷新语义：`refresh(dir)` 先注销旧定时再注册

- **问题**：编辑 `AGENT.md` 改 cron 后，若只 `register`，`AgentScheduler.registerProfile` 内部虽会取消旧句柄（29 节 `reconcile` 复用同一 `scheduleId` 并 `cancel(false)`），但**被删掉的 schedule 项**不会退役（`retireMissing` 只在 `registerAll` 跑）。
- **选择**：`AgentLifecycleService.refresh(Path agentDir)`：先按名字取旧 Profile 并 `agentScheduler.unregisterProfile(old)`，再 `register(dir)`。`update` / `saveFiles` 同样走这条。
- **理由**：`unregisterProfile` 取消该 Agent 名下**全部**句柄，无论新旧配置中是否还存在；随后按新配置重注册，语义等价于「该 Agent 的定时整体重建」。
- **被否**：依赖 `registerProfile` 的幂等取消 → 删掉的任务会残留句柄直到进程重启。

## D-06 `AgentScheduler.unregisterProfile(Profile)` 的边界

- **问题**：句柄表键是 `long scheduleId`，而 `Profile.schedules()` 里只有 `id`（任务键）。
- **选择**：`unregisterProfile(Profile profile)` 遍历 `registeredTasks.values()`，筛出 `task.profile().name().equals(profile.name())` 的条目，对每条：
  1. `scheduledTasks.remove(scheduleId)` 并 `future.cancel(false)`（不中断正在执行的那次）；
  2. `registeredTasks.remove(scheduleId)`；
  3. `scheduledTaskStore.retire(scheduleId)`。
  **不动** `taskLocks` / `sessionLocks`（重注册会复用同一把锁，动了反而制造竞态）。
- **理由**：以 Profile 名归属筛选，天然覆盖「配置里已删除的那条旧 schedule」；`retire` 让管理台看到退役而非凭空消失。
- **被否**：遍历 `profile.schedules()` 反查句柄 → 拿不到已被配置删除的旧任务。

## D-07 生成走既有 `ProviderService.chat`，新增两个配置键

- **问题**：`ProviderService` 只有 `chat(sessionId, profile, request)`，没有 `complete(prompt, text)`；系统也没有「默认 model」。
- **选择**：
  - 生成时构造一个**临时 Profile**（`name = "agent-author"`，`provider = (authorProvider, authorModel)`），用 `sessionId = "agent-author:" + name` 调用 `chat`，取 `response().content()`。
  - 新增配置键：`sableos.author.provider`（缺省取 `sableos.providers` 首个的 name）、`sableos.author.model`（**必须显式配置**，留空则生成端点返回 503 可读原因）。
  - 生成的文本先剥 Markdown 代码围栏（模型常多吐 ```），再按 `===FILE: <相对路径>===` 分隔解析成多文件；无分隔符则整段当 `AGENT.md`。
  - 每个文件路径 `normalize()` 后拒绝绝对路径、`..` 开头、以及 `skills/` 前缀（skills 只许软链）。
  - `AGENT.md` 内容再过 `agentLoader` 的**纯内存校验**（见 D-08）；非法 → 400。
- **理由**：`chat` 是唯一出口，天然落 `llm_calls`（原则五）；临时 Profile 不注册、不落盘，符合「只生成不落盘」。
- **被否**：给 `ProviderService` 加 `complete` → 改动 16 节契约，且绕开会话审计口径。

## D-08 纯内存校验入口：`AgentLoader.parse(markdown, expectedName)`

- **问题**：生成草稿、保存文件都需要「这段 `AGENT.md` 能否解析成合法定义」，但不能写盘。
- **选择**：在 `AgentLoader` 增加 `public Profile parse(String agentMarkdown, String expectedName)`，内部复用既有包级 `deriveProfile(String directoryName, String agentMarkdown)`（改可见性为 `public` 或加一层 public 委派）。**不新增第二条校验路径**。
- **理由**：校验逻辑（frontmatter 必填、provider 已登记、model 非空、name 与目录名一致）只能有一处，否则草稿能过、落盘被拒。
- **被否**：在 `AgentLifecycleService` 里另写一套 YAML 检查 → 立即出现两套规则漂移。

## D-09 per-agent 记忆：`ToolExecutionContext` + 存储路径解析

- **问题**：`SableTool.execute(JsonNode)` 没有执行上下文，`save_memory` / `recall_memory` 不知道替哪个 Agent 跑。
- **选择**：
  - 新增 `com.sableos.core.agent.ToolExecutionContext`：`ThreadLocal<String> AGENT`，提供 `setAgentName / agentName / clear`。**非** `InheritableThreadLocal`（AGENTS §5.2）。
  - `ToolExecutor.execute(...)` 在 `tool.execute(input)` 前后 `set` / `clear`（`finally`），取值来自 `ProfileContext.current().name()`——`AgentService` 已在该线程置入。
  - `MemoryServiceImpl.buildContext(Session)` / `memorySection(Profile)`：委托 store 前后临时置入对应 Agent 名，`finally` 复原（读路径不经 `ToolExecutor`）。
  - `MarkdownMemoryStore`：`memoryFile()` = 有合法 Agent 上下文 → `<root>/agents/<name>/MEMORY.md`；否则回退既有 `<root>/memory/<profileName>/MEMORY.md`（**注意**：现实现已按 profile 分目录，回退路径保持原样）。
  - `LongTermMemoryStore` SPI 签名不变（三档后端契约测试零改动）。
- **理由**：不改工具接口、不牵动非记忆工具；同步阻塞模型下一次 ReAct 整体跑在一条虚拟线程上，线程绑定天然隔离。
- **被否**：给 `SableTool.execute` 加参数 → 改动 20 节全部工具与 MCP 适配器。

## D-10 移除全局 `GET /api/v1/memory`，改 `GET /api/v1/agents/{name}/memory`

- **问题**：全局记忆端点在有 per-agent 记忆后语义含混（`?profile=` 与「哪个 Agent」重复）。
- **选择**：删除 `MemoryApiController`；新增 `GET /api/v1/agents/{name}/memory`。**同时必须改**的既有测试与文档：
  - `MockAgentE2ETest`、`ScheduledTaskE2ETest`、`WebSmokeIT`、`RestartRecoveryIT`、`HumanTriggerFlowIT`、`MockProviderFlowTest` 六处引用；
  - `sableos-web/src/main/frontend` 的 `MemoryPage.vue`（改为按 Agent 选）与 `endpoints.ts`；
  - 《技术方案》§7.2 端点表、README 能力/端点表。
- **理由**：§5.2.1 明确「移除全局 `GET /api/v1/memory`」；留着两套必然长期漂移。
- **被否**：保留旧端点做兼容 → 与「唯一真相源」冲突，且旧端点无法表达 per-agent 语义。

## D-11 固定会话：`channel="admin"`、`user="console"`

- **问题**：管理台每个 Agent 一条固定会话，但不能另起 `session_id` 拼接。
- **选择**：`GET /agents/{name}/session` 与 `POST /agents/{name}/session/messages` 都调 `sessionManager.getOrCreate(ADMIN_CHANNEL, ADMIN_USER, name)`，常量 `ADMIN_CHANNEL = "admin"`、`ADMIN_USER = "console"` 定义在 web 侧 `ApiConstraints`；`session_id` 仍只由 `SessionManager.sessionId` 生成。
- **理由**：H4④ 要求拼装单点；固定 channel/user 满足「幂等 → 恒为同一条」。
- **被否**：新开一个 `sessionId` 重载 → 破坏唯一公式。

## D-12 工作区接口的边界与 `AGENT.md` 写入路径

- **问题**：目录树是否只给 `agents/` + `archive/`？写 `AGENT.md` 要不要重注册？
- **选择**：
  - `GET /workspace/tree` 只返回 `agents/` 与 `archive/` 两个根（不暴露 `.sableos/` 下的 `sableos.db`、`mcp_servers.yaml` 等）。
  - `GET /workspace/file?path=` / `POST /workspace/file {path,content}`：`path` 先按工作区根 `resolve` 后 `normalize()`，必须 `startsWith(root)` 且首段是 `agents` 或 `archive`，否则 400。读前再解析一次真实路径（防校验后被换成外链）。
  - 写 `agents/<name>/AGENT.md` 时**不直接落盘**，改走 `AgentLifecycleService.saveFiles(name, Map.of("AGENT.md", content))`（校验 + 写 + 重注册）；`archive/` 下的文件与其它文件直接写。
  - 读不到 → 404；越界 → 400；写非文本（目录）→ 400。
- **理由**：`WatchService` 在 macOS 对子目录内文件变更不可靠（课件 §5.2.3 已给依据），显式重注册是唯一稳的路径。
- **被否**：写 `AGENT.md` 也走裸写盘 + 等监听 → 平台相关，macOS 上 UI 会「保存了但没生效」。

## D-13 状态码与异常映射（沿用 26 节）

| 场景 | 状态 |
| --- | --- |
| name 非法 / 定义非法 / 生成结果非法 / 路径越界 / 请求体缺字段 | 400 |
| Agent 不存在（get/update/delete/memory/session/files） | 404 |
| 生成用 model 未配置 / 上游模型调用失败 | 503 |
| Agent 调用超 60 秒 | 504 |

不新增状态码、不新增信封字段。异常类型：`IllegalArgumentException`（→400）、`ResourceNotFoundException`（→404）、`ProviderUnavailableException`（→503），三者都已有 handler。

## D-14 装配位置：`sableos-boot` 的 `AgentConfiguration`

- **问题**：`AgentLifecycleService` 需要 `ProviderService`（本仓库 `sableos-provider` 的实现 bean 在 boot 的 `ProviderConfiguration`）、`AgentScheduler`（boot 的 `SchedulerConfiguration`）。
- **选择**：在 `sableos-boot` 的 `AgentConfiguration` 内新增 `AgentStore` / `AgentLifecycleService` / `WorkspaceWatcher` / `workspaceWatcherExecutor` / `watcherGracefulShutdown` 五个 bean。
- **理由**：boot 是唯一能同时看到 provider 与 scheduler 的装配模块；`AgentConfiguration` 已持有 `workspace.root` 与 `AgentLoader`。
- **与 18 节口径的关系**：18 节把「`@EnableJpaRepositories` / `@EntityScan` 声明在 cli」限定的是 **JPA 扫描**；本节的 bean 不涉及 JPA，落在 boot 的既有 `AgentConfiguration` 不违反该约束（`AgentConfiguration` 本就在 boot）。

## D-15 前端：源码 + 构建产物一起交付

- **问题**：`static/admin/**` 是入库的构建产物，`sableos-web/pom.xml` 未绑 frontend 插件。
- **选择**：改 `sableos-web/src/main/frontend/src/` 后手工 `npm ci && npm run build`（`outDir=../resources/static/admin`），产物一并入库；`vite build` 与 `vue-tsc --noEmit` 必须都过。
- **理由**：AGENTS §10 已记录该构建链不在 Maven 内的坑；不重建会让 jar 里是旧页面。

## D-16 课件的三处偏离（需在验收报告里点名）

| 课件写法 | 实际实现 | 原因 |
| --- | --- | --- |
| `providerService.complete(AGENT_AUTHOR_PROMPT, sentence)` | `providerService.chat(genSessionId, 临时 Profile, ProviderRequest.of(prompt)).content()` | `ProviderService` 只有 `chat`（D-07） |
| 生成提示词见 `docs/prompt/prompt.md` | 仓库无 `docs/prompt/`；提示词以常量 `AGENT_AUTHOR_PROMPT` 落在 `AgentLifecycleService` 内 | 依据 `docs/prompt/prompt.md` 不存在，按「第三方/外部文件核实不到即触发软门禁」上报；本 plan 采用「内置常量」，若要外置为文件需另行确认 |
| 课件示意 `agentScheduler.unregisterProfile(profile.schedules()...)` 未给实现 | 按 D-06 以 Profile 名筛句柄表并 `retire` | 课件只给语义，未给代码 |

## D-17 新目录登记的重试（实测补充）

- **问题**：`mkdir agents/<name>` 与写 `AGENT.md` 是两个系统调用。根目录的 `ENTRY_CREATE` 常在文件落盘**之前**到达，此时 `deriveProfile` 必然报「缺少 AGENT.md」；而子目录的 watch 若挂得晚，那次 CREATE 事件也已经错过。首轮全量 verify 里端到端用例在 Windows 上确实红了（`Agent 目录变更处理失败…目录下缺少 AGENT.md`）。
- **选择**：只在「根目录新增子目录」这条路径上做有限重试（5 × 100ms），期间轮询 `AGENT.md` 是否就位；仍失败则记一条点名 WARN 跳过。子目录内的 `MODIFY`（编辑器保存）**不重试**——那时文件已经在写，重试只会拖延事件处理。
- **理由**：这是真实文件系统时序，不是测试假象（`cp -r` 一个大目录同样会先建目录再逐个落文件）。重试只在低频的「新增 Agent」路径发生，最坏阻塞监听线程 500ms。
- **被否**：给子目录补挂监听后再等一次事件 → 已错过的那次不会重放；把整个 `refresh` 放进重试 → 会把派生失败（真的坏定义）也拖成 500ms 延迟。

## D-18 SpotBugs 三轮收敛记录

首轮全量 verify 暴露 5 处，均已按既有规范处理，**无一处改成"看起来更安全但实际更差"的写法**：

| 报点 | 处理 | 说明 |
| --- | --- | --- |
| `CRLF_INJECTION_LOGS`（`AgentLifecycleService.rollbackCreated`） | 补 `safe(cleanup.getMessage())` 作为第二个插值 | 保持 `replaceAll("[\r\n]", "_")` 字面形态 |
| `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`（`AgentStore.isTextFile`） | `getFileName()` 显式判空 | 根路径才可能为 null，但规则不看上下文 |
| `EI_EXPOSE_REP2`（`WorkspaceWatcher` 构造器） | 类级定点抑制 + justification | 与 `AgentScheduler` / `AgentLifecycleService` 同款理由：注入共享单例是意图 |
| `IMPROPER_UNICODE`（`WorkspaceWatcher` 的 `equalsIgnoreCase`） | 抽 `isAgentFile` + `Locale.ROOT` 小写比较 + 定点抑制 | **改成 `equalsIgnoreCase` 之外的写法而不是简单压掉**：语义就是「大小写不敏感文件系统上的同一文件名」 |
| `BX_UNBOXING_IMMEDIATELY_REBOXED`（`Profile.Settings`） | `Integer` 默认值常量改为 `int`，构造器用 `if` 赋值 | 原写法「三元 + 常量封箱」触发装箱-拆箱-再装箱 |
