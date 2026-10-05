# 任务清单：SableOS 运行时内核（五大核心能力）

**输入**：设计产物来自 `specs/001-runtime-core/`

**前置产物**：plan.md（必需）、spec.md（必需）、research.md、data-model.md、contracts/、quickstart.md

**测试说明**：本仓库**未强制 TDD**（宪章定稿时明确排除该条款），因此测试与实现同批交付，不设"先写失败测试"的门槛；但核心链路必须有测试覆盖（research.md D-13、《需求文档》第 13 章功能验收要求）。

**组织方式**：任务按用户故事分阶段，每个阶段是一个可独立验收的增量。

## 格式：`[ID] [P?] [Story] 描述`

- **[P]**：可并行（不同文件、无未完成依赖）
- **[Story]**：所属用户故事（US1–US5；Setup / Foundational / Polish 阶段不带标记）
- 每条任务都给出确切文件路径

## 路径约定

- Java 源码：`sableos-<module>/src/main/java/com/sableos/<module>/...`
- 测试：`sableos-<module>/src/test/java/com/sableos/<module>/...`
- 资源：`sableos-<module>/src/main/resources/...`
- 模块包名：`core` / `provider` / `memory` / `tool` / `channel.cli` / `web` / `storage` / `cli` / `boot`

---

## Phase 1: Setup（工程地基）

**目的**：把骨架补成能承载业务代码的工程基座

- [ ] T001 在聚合 POM 补齐依赖管理与版本：Spring AI Alibaba、Spring Data JPA、SQLite JDBC、Flyway、SnakeYAML、MCP Java SDK in `pom.xml`
- [ ] T002 [P] 各模块按需声明依赖，确认 `mvn clean package` 通过且 JDK 21 目标锁定 in `sableos-*/pom.xml`
- [ ] T003 [P] 建立 Flyway 双轨迁移目录（SQLite 为默认档） in `sableos-storage/src/main/resources/db/migration/sqlite/`、`sableos-storage/src/main/resources/db/migration/postgresql/`
- [ ] T004 [P] 配置结构化日志（Logback，含日志脱敏占位） in `sableos-boot/src/main/resources/logback-spring.xml`
- [ ] T005 [P] 建立应用配置骨架：端口、工作区根、四类白名单键、`memory.backend`、工具结果截断阈值 in `sableos-boot/src/main/resources/application.yaml`
- [ ] T006 验证 fat JAR 产出与 `java -jar` 启动冒烟 in `sableos-boot/pom.xml`

**检查点**：`mvn clean package` 通过，骨架可启动

---

## Phase 2: Foundational（阻塞所有用户故事的公共件）

**目的**：所有用户故事共用的核心抽象、持久化基座、沙箱与工作区初始化

**⚠️ 关键**：本阶段完成前，任何用户故事都不要开工

- [ ] T007 [P] 定义 `SableTool` 接口（`getName` / `getDescription` / `getInputSchema` / `execute`）与 `ToolResult`（成功标识、内容、错误信息、是否可重试） in `sableos-core/src/main/java/com/sableos/core/tool/SableTool.java`、`sableos-core/src/main/java/com/sableos/core/tool/ToolResult.java`
- [ ] T008 [P] 定义 `Sandbox` 接口与 `SandboxAction` / `ActionType`（FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST，签名中不出现"白名单""容器"等实现特有词） in `sableos-core/src/main/java/com/sableos/core/sandbox/`
- [ ] T009 [P] 定义 `Session` 领域模型与消息结构（角色、顺序、时间） in `sableos-core/src/main/java/com/sableos/core/session/`
- [ ] T010 [P] 定义 `Profile` 模型与 `ProfileRegistry`（按 name 索引） in `sableos-core/src/main/java/com/sableos/core/profile/`
- [ ] T011 [P] 实现 `ProfileContext`（线程级当前 Agent；虚拟线程下天然隔离） in `sableos-core/src/main/java/com/sableos/core/profile/ProfileContext.java`
- [ ] T012 实现 `ContextLoader`：现读 `AGENT.md` 正文与 Bootstrap（无缓存，改动下一轮即生效） in `sableos-core/src/main/java/com/sableos/core/context/ContextLoader.java`
- [ ] T013 实现 `AgentLoader.deriveProfile`：扫 `.sableos/agents/`，校验 provider / tool / channel / bootstrap，校验失败只记日志不阻断启动 in `sableos-core/src/main/java/com/sableos/core/agent/AgentLoader.java`
- [ ] T014 [P] 审计实体与仓储：`ToolInvocationEntity` 与 `ToolInvocationRepository`、`LlmCallEntity` 与 `LlmCallRepository`（只写不查） in `sableos-storage/src/main/java/com/sableos/storage/audit/`
- [ ] T015 Flyway 首个迁移脚本：`sessions`、`tool_invocations`、`llm_calls`、`notify_channels`、`memory_entries` 五张表（字段与约束照 `data-model.md` 第 2 节） in `sableos-storage/src/main/resources/db/migration/sqlite/V1__init.sql`
- [ ] T016 实现 `WhitelistSandbox`：四类 ActionType 分别路由到路径（标准化后比对，处理 `../` 穿越）、命令（精确匹配）、域名（通配符）、SMTP 端点（`host:port`，端口缺省任意）四个校验分支，失败抛异常；**不得使用 `SecurityManager`**（JDK 21 已不可用） in `sableos-tool/src/main/java/com/sableos/tool/sandbox/WhitelistSandbox.java`
- [ ] T017 实现 `ToolRegistry`：注册全部 `SableTool`（内置 / MCP / 注解）并按 Profile 的 `tools` 字段过滤可用子集 in `sableos-tool/src/main/java/com/sableos/tool/registry/ToolRegistry.java`
- [ ] T018 实现 `ConfigLoader`：`${ENV_VAR}` 占位符解析、必填与格式校验、明文密钥不入日志 in `sableos-cli/src/main/java/com/sableos/cli/config/ConfigLoader.java`
- [ ] T019 实现 `sableos init`：创建 `.sableos/` 完整目录、默认 Bootstrap 模板、示例 Agent、**带注释的最小白名单**（示例域名 + 工作区目录 + 少量命令）、`notify_channels.yaml` 模板，且可重复执行不覆盖用户文件 in `sableos-cli/src/main/java/com/sableos/cli/command/InitCommand.java`
- [ ] T020 Picocli 主入口与 12 个子命令骨架（未实现者给出明确提示） in `sableos-cli/src/main/java/com/sableos/cli/SableOsCli.java`
- [ ] T021 实现 `AgentService.process` 编排壳：放入 `ProfileContext` → 调循环 → 持久化会话 → `finally` 清理 in `sableos-core/src/main/java/com/sableos/core/agent/AgentService.java`
- [ ] T022 [P] 建立测试基建：临时工作区夹具、内存 SQLite 数据源、provider 桩 in `sableos-core/src/test/java/com/sableos/core/support/`

**检查点**：地基就绪，用户故事可以开工

---

## Phase 3: 用户故事 1 - 对接模型提供方（优先级：P1）🎯 MVP

**目标**：同一实例配置多个 provider，按 Agent 配置选用，运行时切换无需改代码

**独立验证方式**：配置两个 provider 用同一段提示分别调用都成功；把某 Agent 的 provider 改名后行为随之改变；未注册的 provider 明确失败

### 用户故事 1 的测试

- [ ] T023 [P] [US1] 契约测试：显式映射命中、未注册 provider 明确失败（禁止类型扫描回退） in `sableos-provider/src/test/java/com/sableos/provider/ProviderMappingTest.java`
- [ ] T024 [P] [US1] 集成测试：两个 provider 跑同一提示均成功且互不干扰 in `sableos-provider/src/test/java/com/sableos/provider/ProviderSwitchIT.java`

### 用户故事 1 的实现

- [ ] T025 [US1] 实现 `ProviderService`：维护 provider name 到 `ChatModel` 的显式映射表，按 Profile 选择底层模型 in `sableos-provider/src/main/java/com/sableos/provider/ProviderService.java`
- [ ] T026 [P] [US1] provider 配置绑定：API key / base URL / 默认模型，支持 `${ENV_VAR}` in `sableos-provider/src/main/java/com/sableos/provider/config/`、`sableos-boot/src/main/resources/application.yaml`
- [ ] T027 [US1] Function Calling 适配：把 `SableTool` 转成框架的工具调用格式（**只做协议转换与 schema 生成，必须禁用框架的自动 tool 执行**） in `sableos-provider/src/main/java/com/sableos/provider/FunctionCallingAdapter.java`
- [ ] T028 [US1] 模型调用失败处理：认证失败 / 超时 / 格式异常映射为可定位错误，不静默重试、不切换 provider in `sableos-provider/src/main/java/com/sableos/provider/ProviderErrorHandler.java`
- [ ] T029 [US1] 跑 `/speckit-analyze` 核对本阶段产出与 spec / plan / 宪章的一致性并修正漂移
- [ ] T030 [US1] 打 commit 固化用户故事 1 的稳定状态（提交范围：`sableos-provider/`、`sableos-boot/`）

**检查点**：US1 可独立验收（FR-001 ~ FR-005）

---

## Phase 4: 用户故事 2 - ReAct 循环（优先级：P2）

**目标**：Agent 自主多步完成任务，具备迭代上限与可解释终止

**独立验证方式**：需要两轮工具调用的任务能跑通并累积历史；不收敛任务在上限处终止并给出原因；`sableos chat` 可多轮对话

### 用户故事 2 的测试

- [ ] T031 [P] [US2] 循环单元测试：多轮工具调用、消息按序累积、达上限强制终止 in `sableos-core/src/test/java/com/sableos/core/react/ReActLoopTest.java`
- [ ] T032 [P] [US2] 契约测试：终止原因取值（正常结束 / 达到上限），终止时保留全部中间步骤 in `sableos-core/src/test/java/com/sableos/core/react/TerminationTest.java`

### 用户故事 2 的实现

- [ ] T033 [US2] 实现 `PromptBuilder` 四段组装：系统提示（`AGENT.md` 正文 + Bootstrap + 当前日期时间）→ Memory 注入 → 对话历史（按 `max_history_turns` 截断，缺省 20）→ 可用工具列表 in `sableos-core/src/main/java/com/sableos/core/prompt/PromptBuilder.java`
- [ ] T034 [US2] 实现 `ReActLoop`：调 Provider → 无工具调用即结束 → 有则交 `ToolExecutor` 并回填结果 → 迭代计数与上限终止（缺省 10，可由 Profile 覆盖）；终止时返回最后一条模型输出 + 终止原因；模型输出无法解析出工具调用时，按带「是否可重试」的失败结果回填并继续，由上限兜底（FR-044） in `sableos-core/src/main/java/com/sableos/core/react/ReActLoop.java`
- [ ] T035 [US2] 实现 `ToolExecutor`：查 `ToolRegistry` → `Sandbox.enforce` → 执行 → 包装 `ToolResult` → 写 `tool_invocations`（失败与被拒绝的调用同样落库） in `sableos-core/src/main/java/com/sableos/core/tool/ToolExecutor.java`
- [ ] T036 [US2] 实现工具结果截断：超过可配置阈值按字符数截断并标注「已截断 + 原始长度」，循环继续且不落盘 in `sableos-core/src/main/java/com/sableos/core/tool/ToolResultTruncator.java`
- [ ] T037 [P] [US2] 内置 `HttpTools`（`http_get` / `http_post`，执行前过域名白名单） in `sableos-tool/src/main/java/com/sableos/tool/builtin/HttpTools.java`
- [ ] T038 [US2] 实现内存版会话存储与消息追加（本阶段不落库） in `sableos-storage/src/main/java/com/sableos/storage/session/InMemorySessionStore.java`
- [ ] T039 [US2] 实现 `CliChannel` 与 `sableos chat`（交互对话、维持当前会话、`/quit` 退出） in `sableos-channel-cli/src/main/java/com/sableos/channel/cli/CliChannel.java`
- [ ] T040 [US2] 跑 `/speckit-analyze` 核对一致性并修正漂移
- [ ] T041 [US2] 打 commit 固化用户故事 2 的稳定状态（提交范围：`sableos-core/`、`sableos-tool/`、`sableos-channel-cli/`、`sableos-storage/`）

**检查点**：`sableos chat` 多轮对话并调用 HTTP 工具（FR-006 ~ FR-011）

---

## Phase 5: 用户故事 3 - Memory：跨对话记住用户与项目（优先级：P3）

**目标**：长期记忆可写、可检索、可切换后端，跨会话生效

**独立验证方式**：让 Agent 记住偏好后新会话体现该偏好；手工编辑记忆后下一轮立即生效；切换 `memory.backend` 行为一致

### 用户故事 3 的测试

- [ ] T042 [P] [US3] 记忆后端契约测试：两档后端在追加 / 加载 / 检索上行为一致（核心区不截断、检索不区分大小写、不缓存） in `sableos-memory/src/test/java/com/sableos/memory/LongTermMemoryContractTest.java`
- [ ] T043 [P] [US3] 集成测试：写入偏好后新会话命中该偏好 in `sableos-memory/src/test/java/com/sableos/memory/MemoryCrossSessionIT.java`

### 用户故事 3 的实现

- [ ] T044 [US3] 定义 `LongTermMemoryStore` 接口：`append(content, scope)` / `load` / `recallByKeyword`；分区语义为必选能力，无法映射 core/archival 的后端在装配期可读拒绝 in `sableos-core/src/main/java/com/sableos/core/memory/LongTermMemoryStore.java`
- [ ] T045 [P] [US3] 实现 `MarkdownMemoryStore`（默认档）：操作 `.sableos/memory/MEMORY.md` 两个分区，不缓存、核心区永不截断、检索只在归档区；文件缺失按空记忆、结构非法按纯文本整体视作归档区并在日志告警（FR-043） in `sableos-memory/src/main/java/com/sableos/memory/MarkdownMemoryStore.java`
- [ ] T046 [US3] 实现 `SqliteMemoryStore`：`memory_entries` 表，核心区 `WHERE scope='CORE'` 全量取、归档区 `LIMIT N`、检索走 `LIKE` in `sableos-memory/src/main/java/com/sableos/memory/SqliteMemoryStore.java`
- [ ] T047 [US3] 实现 `MemoryService` 统一门面：会话记忆委托会话存储、长期记忆委托后端；按 `memory.backend` 装配（`markdown` 默认 / `sqlite`） in `sableos-memory/src/main/java/com/sableos/memory/MemoryService.java`
- [ ] T048 [US3] 实现 `MemoryTools`：`save_memory(content, scope)`（缺省归档区）与 `recall_memory`，注册进 `ToolRegistry` in `sableos-memory/src/main/java/com/sableos/memory/MemoryTools.java`
- [ ] T049 [US3] `PromptBuilder` 接入 Memory 注入（会话历史 + 核心区 + 归档区，每轮重新读取） in `sableos-core/src/main/java/com/sableos/core/prompt/PromptBuilder.java`
- [ ] T050 [US3] 跑 `/speckit-analyze` 核对一致性并修正漂移
- [ ] T051 [US3] 打 commit 固化用户故事 3 的稳定状态（提交范围：`sableos-memory/`、`sableos-core/`）

**检查点**：跨会话记忆生效 + 后端切换等价（FR-012 ~ FR-017、FR-042）

---

## Phase 6: 用户故事 4 - Tool 体系：内置工具 + 零代码扩展 + 沙箱约束（优先级：P4）

**目标**：内置工具齐备、三档扩展可用、越权操作被拒且留痕

**独立验证方式**：只写配置接入 MCP 并调用成功；白名单外的路径 / 域名 / 命令被拒且原因可读；技能元数据进提示词、正文按需读取

### 用户故事 4 的测试

- [ ] T052 [P] [US4] 沙箱四类拒绝路径测试（路径穿越、非白名单命令、非白名单域名、白名单为空时全拒） in `sableos-tool/src/test/java/com/sableos/tool/sandbox/WhitelistSandboxTest.java`
- [ ] T053 [P] [US4] 技能绑定边界测试：未绑定 Skill 不出现在提示词、软连接越界不可读、删除被引用 Skill 默认拒绝 in `sableos-core/src/test/java/com/sableos/core/context/SkillBindingTest.java`

### 用户故事 4 的实现

- [ ] T054 [P] [US4] 实现 `FileTools`：`read_file` / `write_file` / `list_dir`，执行前调 `Sandbox.enforce` in `sableos-tool/src/main/java/com/sableos/tool/builtin/FileTools.java`
- [ ] T055 [P] [US4] 实现 `ShellTools`：精确匹配可执行文件白名单、argv 直传不经 Shell 解释、带超时 in `sableos-tool/src/main/java/com/sableos/tool/builtin/ShellTools.java`
- [ ] T056 [US4] 定义 `NotifyChannelAdapter` 接口（`send(NotifyTarget, content)`）并实现 `WebhookNotifyAdapter`（发送前过域名白名单） in `sableos-tool/src/main/java/com/sableos/tool/notify/`
- [ ] T057 [US4] 实现 `NotifyTools.notify(content, channel)`：从全局注册表按名解析适配器与地址，地址不进入对话 in `sableos-tool/src/main/java/com/sableos/tool/builtin/NotifyTools.java`
- [ ] T058 [US4] 实现 `notify_channels.yaml` 加载与同步：读文件 → reconcile 进 `notify_channels` 表（新增/改动 upsert、文件里移除的标记退役），供 `NotifyTools` 解析适配器与地址；不提供 CRUD 端点（FR-035/036） in `sableos-storage/src/main/java/com/sableos/storage/notify/`
- [ ] T059 [US4] 实现 `McpClientService` 与 `McpToolAdapter`：启动连接 `mcp_servers.yaml` 声明的 server、调 `tools/list`、包装为 `SableTool`，处理失联 / 超时 / 错误恢复 in `sableos-tool/src/main/java/com/sableos/tool/mcp/`
- [ ] T060 [US4] `ContextLoader` 补技能扫描：验证软连接真实目标位于公共 Skill 根内，只注入 name / description / 本地绝对路径（正文不预载） in `sableos-core/src/main/java/com/sableos/core/context/ContextLoader.java`
- [ ] T061 [US4] 实现注解式工具的扫描注册（来源标记 `builtin` / `annotation` / `mcp`） in `sableos-tool/src/main/java/com/sableos/tool/registry/AnnotationToolScanner.java`
- [ ] T062 [US4] 跑 `/speckit-analyze` 核对一致性并修正漂移
- [ ] T063 [US4] 打 commit 固化用户故事 4 的稳定状态（提交范围：`sableos-tool/`、`sableos-core/`、`sableos-storage/`）

**检查点**：零代码 MCP 接入 + 技能按需读取 + 沙箱拒绝留痕（FR-018 ~ FR-026）

---

## Phase 7: 用户故事 5 - Web Service 与三种触发入口收口（优先级：P5）

**目标**：外部系统经 HTTP/JSON 调用；CLI / HTTP / 定时三入口复用同一条链路；会话跨重启保留

**独立验证方式**：完成"创建会话 → 调用 Agent → 查历史"闭环；重启后仍可查；定时到点自动运行且可手动补跑

### 用户故事 5 的测试

- [ ] T064 [P] [US5] HTTP 契约测试：10 个端点可达、统一信封、404 / 400 / 503 / 504 语义正确 in `sableos-web/src/test/java/com/sableos/web/HttpContractTest.java`
- [ ] T065 [P] [US5] 会话身份测试：三渠道的用户标识来源与同组合复用 in `sableos-core/src/test/java/com/sableos/core/session/SessionIdentityTest.java`

### 用户故事 5 的实现

- [ ] T066 [US5] 实现 `WebServer`：Spring MVC + virtual thread，`sableos serve` 触发，默认端口 8080 in `sableos-web/src/main/java/com/sableos/web/WebServer.java`
- [ ] T067 [US5] 统一响应信封与 `GlobalExceptionHandler`：成功与错误共用一个信封，错误码映射到标准状态码 in `sableos-web/src/main/java/com/sableos/web/GlobalExceptionHandler.java`
- [ ] T068 [US5] 实现 `SessionApiController` 四个端点：创建（同组合复用）、发消息、查历史（默认最近 100 条）、归档（不物理删除） in `sableos-web/src/main/java/com/sableos/web/SessionApiController.java`
- [ ] T069 [US5] 实现 `AgentApiController.invoke`：无状态调用端点，与 CLI 走同一条 `AgentService` 链路（也是定时任务手动补跑的入口） in `sableos-web/src/main/java/com/sableos/web/AgentApiController.java`
- [ ] T070 [P] [US5] 实现信息查询三个控制器：`ProfileApiController`、`MemoryApiController`、`ToolApiController`（均只读、脱敏） in `sableos-web/src/main/java/com/sableos/web/`
- [ ] T071 [P] [US5] 实现 `SystemApiController`：`health`（进程 / 存储 / Agent 数）与 `info`（版本 / 工作区 / provider 与 MCP 列表，不含密钥） in `sableos-web/src/main/java/com/sableos/web/SystemApiController.java`
- [ ] T072 [US5] 会话持久化切到 SQLite：`SessionRepository` 落地、消息追加入库、跨重启恢复查询；按会话维度加进程内锁实现同会话串行（等待上限为单次调用超时，不拒绝、不丢写，FR-045） in `sableos-storage/src/main/java/com/sableos/storage/session/`
- [ ] T073 [US5] 实现 `AgentScheduler`：`ThreadPoolTaskScheduler` + `CronTrigger` 动态注册；单任务进程内锁防重叠（上一次未结束则跳过）；失败只记日志且仍走完整审计路径 in `sableos-core/src/main/java/com/sableos/core/agent/AgentScheduler.java`
- [ ] T074 [US5] 会话身份规则落地：命令行取本机用户名、HTTP 由请求参数提供（缺省 `anonymous`）、定时固定 `scheduler`；`channel + user + profile` 联合生成并在同组合下复用 in `sableos-core/src/main/java/com/sableos/core/session/SessionIdentity.java`
- [ ] T075 [US5] 多 Agent 并存校验：同实例两个不同 provider 的 Agent 同时可用、配置与会话不串扰 in `sableos-core/src/main/java/com/sableos/core/agent/AgentLoader.java`
- [ ] T076 [US5] 补全 CLI 命令：`serve` / `gateway` / `status` / `profile list|create|show|delete` / `provider list` / `tool list` / `session list`（合计 12 个子命令） in `sableos-cli/src/main/java/com/sableos/cli/command/`
- [ ] T077 [US5] 接入 OpenAPI 文档（`springdoc`，暴露 `/swagger-ui`） in `sableos-web/src/main/java/com/sableos/web/OpenApiConfig.java`
- [ ] T078 [US5] 跑 `/speckit-analyze` 核对一致性并修正漂移
- [ ] T079 [US5] 打 commit 固化用户故事 5 的稳定状态（提交范围：`sableos-web/`、`sableos-core/`、`sableos-storage/`、`sableos-cli/`）

**检查点**：三入口同一链路 + 跨重启恢复 + 多 Agent 并存（FR-027 ~ FR-036）

---

## Phase 8: Polish（收尾与跨故事事项）

**目的**：跨故事的收尾、示例资产与端到端验收

- [ ] T080 [P] 把三个 Demo 的 Agent 目录与公共 Skill 示例纳入 `sableos init` 的模板产物（每日天气、每日科技日报、每日 GitHub 日报） in `sableos-cli/src/main/resources/templates/`
- [ ] T081 [P] 项目主页同步：在既有站点补上五大能力与快速开始（对应 SC-012） in `website/index.html`、`website/assets/js/`
- [ ] T082 [P] 日志与审计脱敏复核：确认密钥、通知地址、Bootstrap 正文均不出现在日志与 HTTP 响应 in `sableos-boot/src/main/resources/logback-spring.xml`、`sableos-web/src/main/java/com/sableos/web/`
- [ ] T083 稳定性与性能验证：10 Agent 连续 4 小时、100 并发会话、会话创建 P99 < 200ms、转发开销 < 50ms（quickstart 第 7 节） in `specs/001-runtime-core/quickstart.md`
- [ ] T084 端到端跑通 quickstart 的 3 个 Demo、6 条失败路径与 8 条工程化验证，记录结果 in `specs/001-runtime-core/quickstart.md`
- [ ] T085 文档同步：把实现过程中产生的口径变化回写 `AGENTS.md` 与相关 `docs/`（含漂移清单）

---

## 依赖与执行顺序

### 阶段依赖

- **Phase 1 Setup**：无依赖，可立即开始
- **Phase 2 Foundational**：依赖 Setup 完成，**阻塞所有用户故事**
- **Phase 3–7 用户故事**：均依赖 Foundational 完成
- **Phase 8 Polish**：依赖所需用户故事完成

### 用户故事之间的依赖

- **US1（P1）**：无故事依赖 —— MVP
- **US2（P2）**：依赖 US1（没有模型调用就没有循环）
- **US3（P3）**：依赖 US2（记忆通过循环被使用）
- **US4（P4）**：依赖 US2；**与 US3 互不依赖**，可并行
- **US5（P5）**：依赖 US1–US4（触发入口是前四个能力的出口）

### 阶段内顺序

- 模型与实体先于服务，服务先于端点
- 核心实现先于集成装配
- 每个阶段以 `/speckit-analyze` + commit 收尾（宪章原则 IX 的质量门槛）

### 并行机会

- Phase 1 内 T002–T005 可并行
- Phase 2 内 T007–T011、T014、T022 可并行（不同文件）
- Phase 4 内 T037（HttpTools）与 T038（内存会话存储）可并行
- Phase 5 内 T045（Markdown 档）与 T046（SQLite 档）可并行
- Phase 6 内 T054（FileTools）与 T055（ShellTools）可并行
- Phase 7 内 T070（三个查询控制器）与 T071（System 控制器）可并行
- Phase 8 内 T080–T082 可并行
- **US3 与 US4 整体可由两人并行推进**

---

## 并行示例：用户故事 4

```bash
# 同一条消息里并排发起互不依赖的实现任务：
Task: "实现 FileTools（read_file/write_file/list_dir，执行前过沙箱） in sableos-tool/.../builtin/FileTools.java"
Task: "实现 ShellTools（白名单精确匹配、argv 直传、带超时） in sableos-tool/.../builtin/ShellTools.java"
```

---

## 实施策略

### 先做 MVP（只做用户故事 1）

1. 完成 Phase 1 Setup
2. 完成 Phase 2 Foundational（**关键**，阻塞全部故事）
3. 完成 Phase 3 用户故事 1
4. **停下来独立验收**：两个 provider 跑通同一提示
5. 可演示后再推进

### 增量交付

1. Setup + Foundational → 地基就绪
2. US1 → 独立验收 → 提交（MVP）
3. US2 → 独立验收（`sableos chat` 多轮 + HTTP 工具）→ 提交
4. US3 与 US4 → 可并行推进，各自独立验收 → 各自提交
5. US5 → 独立验收（三入口同一链路 + 跨重启）→ 提交
6. Polish → 三个 Demo 端到端跑通 → 收尾提交

### 多人并行策略

1. 全员先合做 Setup + Foundational
2. 之后：一人 US3（Memory），一人 US4（Tool 与沙箱）；US1/US2 是串行前置，建议由同一人连续推进
3. 最后合流做 US5 与 Polish

---

## 注意事项

- `[P]` 表示不同文件、无未完成依赖，可并行
- `[Story]` 标签用于把任务追溯到用户故事，便于逐条核对规格
- 每个用户故事都应当能独立完成与独立验证
- 每个用户故事结束时跑 `/speckit-analyze` 并打 commit，不要攒到最后
- 避免：任务描述含糊、同一文件被多任务并发修改、跨故事依赖破坏独立性
- 本清单不含任何"修改宪章"的任务（宪章由项目方维护，AI agent 无权改动）
