# Phase 0 Research: ReAct 循环——Agent 的核心循环引擎

**Feature**: 002-lesson17-react | **Date**: 2026-10-14

本文件记录规划阶段的全部技术决策与核实证据。证据分三类：

- **文档锚点**：仓库内权威文档（课件 17 / 《技术方案》/ 宪章 / AGENTS.md）；
- **本地依赖实测**：对 Maven 本地仓库（`D:\maven\Repository`）内已解析 jar 的 javap / `dependency:tree` 核对；
- **前序节实测**：第 16 节已落地代码的公共契约逐字核对。

权威顺序（AGENTS.md 第 4 章）：多份文档打架时以《技术方案》为准，其次 skill，再次课件。课件与《技术方案》冲突处一律记入附录 B，随 Step 5 停点逐条上报，不在规划中静默取舍。

## 决策摘要

| # | 决策 |
| --- | --- |
| D-01 | 4 个前向契约放 core（`Session`/`SessionStore`、`ToolCatalog`、`ToolInvocationAuditor`、`MemoryProvider`），真实现归后节（18/20/22），本节给最小/内存实现 |
| D-02 | ReAct 多轮上下文**拍平成一段文本**放进 `ProviderRequest.content`；工具仍走 `availableTools` 原生 schema；**第 16 节 Provider 契约零改动** |
| D-03 | `Session` 建模：可变聚合（标识/profileName/channel/userId/消息列表/status/时间戳）+ 三种 append |
| D-04 | 工具集合契约命名 `ToolCatalog`（避开 20 节将落地的 `ToolRegistry` 类名） |
| D-05 | 长期记忆注入用可选 `MemoryProvider`（17 节空 lambda，22 节落地） |
| D-06 | `ProfileContext` 用 `ThreadLocal`（ScopedValue 在 JDK 21 仍是 preview，开 `--enable-preview` 会挂构建） |
| D-07 | 工具失败语义：越界/未注册/执行异常一律**收敛为失败结果回填**（不中断循环），异常原因落审计与结果——「异常不吞」= 原因留痕 |
| D-08 | `tool_invocations` 表结构 + 按节追加双轨 `V2__tool_invocations.sql` |
| D-09 | 审计 fail-closed + `trace_id` 取 `TraceContext.traceId()`（禁 `UUID.randomUUID()`） |
| D-10 | 本节 boot 装配空 `ToolCatalog`（无工具可解析 → 都失败） |
| D-11 | `ContextLoader` 自持 frontmatter 剥离（不动 `AgentLoader` 私有实现） |
| D-12 | 上下文渲染格式与「一条消息 = 一轮」的截断口径 |
| D-13 | 当前日期时间由 `PromptBuilder` 追加到 system prompt 末尾（harness 断言点） |
| D-14 | 转满上限返回**固定终止文案**（含「达到最大轮数」字样） |

---

## D-01 4 个前向契约放 core，真实现归后节

**Decision**: 第 17 节的编排需要四个能力，其正式归属都在后节，本节在 core 定义**前向契约**并给最小/内存实现：

| 前向契约 | 位置 | 本节实现 | 正式实现归属 |
| --- | --- | --- | --- |
| `Session` / `SessionMessage` / `MessageRole` / `SessionStore` | `com.sableos.core.session` | `Session` 为完整可变聚合；`SessionStore` 由 storage 的 `InMemorySessionStore` 实现 | 18 节（`sessions` 表 + 跨重启恢复 + `SessionManager`） |
| `ToolCatalog`（按名解析 + 按 Profile 过滤） | `com.sableos.core.tool` | boot 装配一个空实现 | 20 节（`ToolRegistry` + 三档接入） |
| `ToolInvocationAuditor`（工具审计写入） | `com.sableos.core.tool` | storage 的 `JpaToolInvocationAuditor` | 20/24 节延伸（保留同一契约） |
| `MemoryProvider`（长期记忆段文本） | `com.sableos.core.memory` | boot 装配空 lambda（返回 null） | 22 节（`MemoryService` / `LongTermMemory`） |

**Rationale**:

- 沿用第 16 节已确立的依赖倒置范式（契约在 core、实现在后置模块）：`LlmCallAuditor`（core 契约）→ `JpaLlmCallAuditor`（storage 实现）就是同款先例，17 节照此办理。
- 沿用课件第 20 节既有范式「Sandbox 前向接口 + `PermissiveSandbox` 临时装配」——前向依赖是 SableOS 的既定处理方式，不是本节发明。
- core 的 `ReActLoop` / `AgentService` 必须在 core 内自洽（core 不依赖 provider 外的任何模块），故这四个契约必须由 core 自己定义。

**Alternatives considered**:

- 等后节落地再写循环——循环无法编译，本节零交付；
- 在 core 内自造一套完整实现（如自建 `sessions` 表）——越 18 节范围，且与其 data-model 口径冲突（软门禁 #1）。

## D-02 多轮上下文拍平成一段文本进 `ProviderRequest.content`

**Decision**: `PromptBuilder.build(Session, Profile)` 返回 **`ProviderRequest`**（第 16 节中立 DTO）：把 system prompt + 记忆段 + 最近 N 条历史（含工具结果）**拍平成一段纯文本**放进 `content`；`availableTools` = `toolCatalog.toolsFor(profile)`。**第 16 节 `ProviderService.chat(String, Profile, ProviderRequest)` 与 `ProviderRequest(String, List<SableTool>)` 逐字不变**。

**Rationale**:

- 第 16 节 `SpringAiProviderServiceImpl` 证实：它构造 `new Prompt(List.of(new UserMessage(request.content())), options)`——`content` 就是**单条用户消息的纯文本**，工具走 options 里的原生 schema。要承载多轮历史而**零改动**前序契约，唯一形态就是把历史折叠进这一段文本。
- 课件 17 骨架里的 `Prompt`/`resp.text()` 是 Spring AI 类型，core 不得染（第 16 节 D-01 同款理由）；采用 `ProviderRequest`/`ProviderResponse` 的中立形态。
- 「不引入 core 自己的 Message 类型体系」——课件的多轮 Message 列表属未来需求，本节按 D-07（第 16 节）「不提前设计」办理。

**Alternatives considered**:

- 给 `ProviderRequest` 加 `List<Message>` 字段——**改动前序节已定字面量**（软门禁 #2），且 core 需自养 Message 体系；
- 每轮把历史重新拼成多条 Message 塞进 Spring AI `Prompt`——core 染 Spring AI 类型。

## D-03 `Session` 建模

**Decision**: `Session`（可变聚合，`com.sableos.core.session`）：

| 成员 | 类型 | 说明 |
| --- | --- | --- |
| `id` | String | 会话标识（构造传入，不可变） |
| `profileName` | String | 所属 Agent 运行配置名（不可变） |
| `channel` | String | 触发渠道（cli/web/scheduler；可空） |
| `userId` | String | 用户标识（可空） |
| `messages` | `List<SessionMessage>` | 对话历史（内部可变，外部只读） |
| `status` | String | 会话状态（本节固定 `active`，状态机归 18 节） |
| `createdAt` / `lastActiveAt` | `Instant` | 创建 / 最后活跃时间 |

方法：`appendUserMessage(String)`、`appendAssistantMessage(ProviderResponse)`、`appendToolResult(String toolName, ToolResult)`、`messages()`（返回不可变视图）。`SessionMessage(MessageRole role, String content, List<ToolCallRequest> toolCalls, String toolName, Boolean success)`；`MessageRole { USER, ASSISTANT, TOOL }`；静态工厂 `user(...)` / `assistant(ProviderResponse)` / `tool(String, ToolResult)`。

**Rationale**: 课件 FR4「每轮响应与工具结果累积进会话」要求会话持有可变历史；`sessions` 表 18 节才落，本节字段按 18 节 data-model 口径预留（id/profileName/channel/userId/历史/status/时间戳）以免回改。`appendAssistantMessage` 直接吃 `ProviderResponse`（携带文本 + toolCalls），保留工具调用请求供后续轮参考。

**Alternatives considered**: `Session` 用 record（字段全不可变）——append 语义无法自然表达；把工具结果与模型输出合成一条消息——丢角色区分，审计与续接变难。

## D-04 工具集合契约命名 `ToolCatalog`

**Decision**: `com.sableos.core.tool.ToolCatalog` 接口，两方法：`List<SableTool> toolsFor(Profile)`（按 Profile 过滤，供 PromptBuilder 组装 schema）、`SableTool find(String name)`（按名解析，供 ToolExecutor；解析不到返回 null）。

**Rationale**: 20 节课件与《技术方案》§6.2 的注册表类名是 `ToolRegistry`（落 `sableos-tool` 模块）。本节在 core 定义的是**同名职责的前向契约**，若也叫 `ToolRegistry` 会与 20 节的真实类名撞名（分处 core/tool 两模块，import 时极易混淆）。取名 `ToolCatalog` 表达「工具目录」同一语义，且不占用后节类名。

**Alternatives considered**: 直接叫 `ToolRegistry`——与 20 节落地的 `com.sableos.tool.ToolRegistry` 同名，跨模块 import 冲突；命名 `ToolResolver`——只覆盖 `find`，漏掉 `toolsFor`。

## D-05 长期记忆注入用可选 `MemoryProvider`

**Decision**: `com.sableos.core.memory.MemoryProvider` 单方法 `String memorySection(Profile profile)`（返回记忆段文本，无记忆返回 null）；`PromptBuilder` 构造注入，可空（null 或返回 null 时跳过记忆段）。

**Rationale**: FR-006「长期记忆（未配置则跳过该段）」+ spec Assumptions「本次只保留可选注入位」。给 22 节留一个明确的契约点，避免 22 节回改 `PromptBuilder` 方法签名。

**Alternatives considered**: 本节直接 `String` 硬编码记忆段——无注入点，22 节要改构造签名；引入 `Optional` 包装——多一层无收益。

## D-06 `ProfileContext` 用 `ThreadLocal`

**Decision**: `com.sableos.core.profile.ProfileContext` 静态工具类（私有构造）：`set(Profile)` / `current()` / `clear()`，底层 `ThreadLocal<Profile>`。

**Rationale**: 课件明确用 ThreadLocal；JDK 21 下 `ScopedValue` 仍是 **preview** API，使用需 `--enable-preview` 编译与运行——本仓库 `maven-compiler-plugin` 锁 Java 21 正式目标，开 preview 会挂构建（且 P3C/ASM 解析风险）。每请求独占一虚拟线程，ThreadLocal 天然不串台（FR-012）。

**Alternatives called off**: `ScopedValue`——preview，构建门禁不允许；`InheritableThreadLocal`——AGENTS.md §5.2 明令禁止（虚拟线程子任务必空 + 串号风险）。

## D-07 工具失败语义：「异常不吞」= 原因留痕 + 收敛回填

**Decision**: `ToolExecutor.execute` 的失败来源一律**收敛为失败 `ToolResult`**，**不进重抛、不中断循环**，由轮数上限兜底：

| 失败来源 | 结果 |
| --- | --- |
| 工具名未注册（`ToolCatalog.find` 返回 null） | `ToolResult.failure("未注册的工具：" + name, false)` |
| `argumentsJson` 非法 JSON | `ToolResult.failure("工具入参解析失败：" + msg, true)` |
| 工具执行抛 `RuntimeException` | `ToolResult.failure("工具执行异常：" + msg, true)` |

三条分支**都写审计**（`success=false` + `errorMessage`）。

**Rationale**: FR-009「结果必须被包装成带成功标识的结果，失败时带原因，异常不得被吞掉」+ FR-008「解析不到按失败处理并回填，不中断循环，由轮数上限兜底」+ spec Edge Cases 逐条同口径。**「不吞」的语义是「原因必须留痕（结果 + 审计），不能被静默丢弃」，而非「必须向上抛」**——若向上抛，一次工具失败会终止整个多轮处理，与 FR-008「不中断循环」矛盾。此处为**软门禁 #3 触发点**（课件 FR6「异常不吞」字面有歧义），随 Step 5 停点上报确认。

**Alternatives considered**: 异常直接上抛中断循环——与 FR-008/Edge Cases 冲突；捕获后返回空成功——丢原因、违反 FR-009。

## D-08 `tool_invocations` 表与双轨迁移

**Decision**: 表结构以 `db/migration/{vendor}/V2__tool_invocations.sql` 为唯一权威（双轨 1:1）。列：`id / session_id / tool_name / input_json / result_json / success / error_message / duration_ms / trace_id / created_at`；索引 `idx_tool_invocations_session_id`、`idx_tool_invocations_trace_id`。类型风格逐列对齐 16 节 `V1__llm_calls.sql`（sqlite `INTEGER PRIMARY KEY AUTOINCREMENT` / `TEXT` vs postgresql `BIGSERIAL` / `VARCHAR(n)` / `TIMESTAMPTZ`）。

**Rationale**: 口径来源是 `specs/001-runtime-core` 的 data-model §2.2（该 spec 目录已随 `17e2cfc` 删除，本节经 `git show 17e2cfc^:specs/001-runtime-core/data-model.md` 取回原始口径）。原则 VI + 课件「手工建表脚本」→ 落 Flyway 双轨（AGENTS.md §5.1 第 6 条）。迁移按节追加（`V1` 已被 16 节占用，本节取 `V2`），与 `db/migration/README.md` 的「按节追加策略」一致。

**Alternatives considered**: 与 16 节 `V1` 合并——已执行脚本不可改（Flyway 纪律）；改 `README.md` 计划口径——无必要。

## D-09 审计 fail-closed + `trace_id`

**Decision**: `ToolInvocationAuditor` 七参签名（对齐 `LlmCallAuditor` 风格）：`void record(String sessionId, String toolName, String inputJson, String resultJson, boolean success, String errorMessage, long durationMs)`。`JpaToolInvocationAuditor`（storage）**fail-closed**：写库异常 → LOG.error（CRLF 净化）→ 抛 `IllegalStateException`。`trace_id` 由实现内部取 `TraceContext.traceId()`（无上下文为 null），**不禁用 UUID、不经参数传递**。

**Rationale**: 与 16 节 `LlmCallAuditor`/`JpaLlmCallAuditor` **逐条对齐**（同一栈、同一纪律），审计是审计不能静默丢（原则 VI）。traceId 规范见 AGENTS.md §5.2。

**Alternatives considered**: fail-open——与 16 节不一致且违背原则 VI。

## D-10 本节 boot 装配空 `ToolCatalog`

**Decision**: boot 的 `ReactConfiguration` 装配一个**包内私有**的空 `ToolCatalog`（`toolsFor` 返回空表、`find` 返回 null）：零 key / 无工具环境下循环可装配可运行，模型若要求调工具则按「未注册」失败处理并回填。20 节用真实 `ToolRegistry` 替换该 bean。

**Rationale**: 本节交付物不含任何工具实现（工具归 19/20/24 节）；但 `ToolExecutor` 与循环必须可装配（§IX 装配可启动）。空实现是无工具环境下语义正确的行为（FR-008 未注册失败）。

**Alternatives considered**: 不装配 `ToolCatalog` bean（Optional 注入）——`ToolExecutor` 依赖变成可选，逻辑分支多余；装配 `null` bean——Spring 不允许。

## D-11 `ContextLoader` 自持 frontmatter 剥离

**Decision**: `com.sableos.core.context.ContextLoader(Path agentsDir, Path bootstrapDir, Path skillsRoot)`，方法 `String systemPrompt(Profile profile)`：读 `<agentsDir>/<name>/AGENT.md` 的**正文**（自持 `---` 围栏剥离，不调 `AgentLoader` 私有方法）、拼 `Profile.identity().prompt()`、读 `Profile.bootstrap()` 列出的文件（缺失 WARN）、扫 `<agentsDir>/<name>/skills/*` 相对软连接（每项须有 `SKILL.md` 且含 name/description，只注入 name/description/本地绝对路径，正文不预载；显式引用缺失 → 报错点名）。**每次调用现读，不缓存**。

**Rationale**: `AgentLoader.parseFrontmatter` 是私有实现且职责是「派生 Profile」；`ContextLoader` 只需正文，**只需剥离围栏**（`---` 首行到次个 `---`），不需 YAML 解析。把 `AgentLoader` 私有方法提 public 属改动前序节公共接口（软门禁 #4）。

**Alternatives considered**: 复用 `AgentLoader` 的 frontmatter 解析——需改其可见性；复制整段 YAML 解析——重复造轮子。

## D-12 上下文渲染格式与截断口径

**Decision**: `PromptBuilder` 把 content 按固定顺序拼成一段文本（段落间空行分隔）：

```text
<system prompt（含角色设定 + Agent 正文 + bootstrap 文件内容 + skills 说明）>

<记忆段（MemoryProvider 非 null 时；否则整段省略）>

<会话历史：最近 N 条 SessionMessage 按角色前缀逐条渲染>

<当前用户消息>
```

- 截断：只留**最近 N 条 `SessionMessage`**（默认 20，`Profile.settings().maxHistoryTurns()` 覆盖），一条消息计一轮（用户消息 / 模型输出 / 工具结果各计一条）；system prompt 与当前用户消息**不参与截断**（FR-007）。
- 当前用户消息：`Session` 内已 `appendUserMessage` 后由 `messages()` 尾部的 USER 条目承载（与课件「先 append 再 build」一致），不在 build 内重复追加。

**Rationale**: FR-006 固定顺序 + clarify「一条消息 = 一轮」+ 课件 FR5「只留最近 N 轮」。角色前缀（如 `User:`/`Assistant:`/`Tool[<name>]:`）让模型能区分历史角色与工具结果来源。

**Alternatives considered**: 按「用户消息对数」计轮——clarify 已定为「一条消息 = 一轮」；截断也作用于 system prompt——违反 FR-007。

## D-13 当前日期时间由 `PromptBuilder` 追加

**Decision**: `PromptBuilder` 在 system prompt 末尾追加**当前日期时间**（形如 `当前时间：2026-10-14T15:30:45+08:00`，用 `Instant.now()` + 系统时区，或 `LocalDateTime.now()`）。

**Rationale**: 课件 FR5「system prompt 末尾附当前日期时间」+ harness 关键回归点 ④「system prompt 末尾含当前日期时间」。**追加点选 `PromptBuilder` 而非 `ContextLoader`**：`ContextLoader` 只负责「读文件得到文本」（无时间语义），时间语义归属「本轮组装」的 `PromptBuilder`。测试用「断言末尾含今日日期字符串」或注入可控钟点。

**Alternatives considered**: 放 `ContextLoader`——职责混入时间语义，且 `ContextLoader` 单测要 mock 时钟；放 `ReActLoop`——组装逻辑应集中在 `PromptBuilder`。

## D-14 转满上限返回固定终止文案

**Decision**: `ReActLoop.run` 转满 `maxIterations` 后 `return "达到最大轮数，已停止"`（**固定文案，不拼接最后一条模型输出**）。

**Rationale**: clarify 已确认（与课件骨架 `return "达到最大轮数，已停止"` 逐字一致）。`specs/001-runtime-core` plan 原有「最后一条模型输出 + 终止原因」的写法已随该 spec 删除失效；本节以课件字面 + 用户确认为准。

**Alternatives considered**: 拼接最后输出——clarify 已否决。

---

## 附录 A：本地依赖核实清单（`dependency:tree` + javap 实测）

核对对象为 `sableos-core` 解析出的锁定版本（`D:\maven\Repository`）：

| 构件 | 版本 | 已核实的关键 API |
| --- | --- | --- |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.4 | `ObjectMapper.readTree(String)`（抛 checked `JsonProcessingException` + `JsonMappingException`）→ `JsonNode`；`JsonNode.isNull()/isMissingNode()/asText()/toString()`。**core 已在依赖内，本节零新增依赖**（工具入参 JSON 解析用） |
| `org.yaml:snakeyaml` | 2.4 | 既有（`AgentLoader` 用），本节 `ContextLoader` 不消费（只剥离围栏） |
| `org.slf4j:slf4j-api` | 2.0.18 | 既有，用于 WARN/ERROR 日志 |
| 第 16 节 core 契约 | 本仓 | `ProviderService.chat(String, Profile, ProviderRequest)`、`ProviderRequest(String, List<SableTool>)`、`ProviderResponse(String, List<ToolCallRequest>, Usage)`、`ToolCallRequest(String, String, String)`、`SableTool` 四方法、`ToolResult(boolean, String, String, boolean)` + 静态工厂、`Profile.Settings(Integer, Integer)` 默认 10/20、`LlmCallAuditor` 七参——**逐字核对，本节零改动** |
| `com.sableos.core.trace.TraceContext` | 本仓 | `traceId()`（无上下文返回 null）、`openIfAbsent()`（AgentService 兜底入口）、`snapshot()` |

## 附录 B：课件与权威文档口径差清单（随 Step 5 停点逐条呈报）

| # | 课件 17 原文口径 | SableOS 采用口径 | 依据 |
| --- | --- | --- | --- |
| A | `Prompt prompt = promptBuilder.build(...)` / `resp.text()` / `resp.hasToolCalls()` | `ProviderRequest build(...)` / `resp.content()` / `!resp.toolCalls().isEmpty()` | 第 16 节中立 DTO（core 不染 Spring AI）；D-02 |
| B | `ContextLoader` 按「Profile 的 `skills` 字段」读 Skill | 扫 Agent 目录内 `skills/` 相对软连接（Profile 无 skills 字段） | 项目约定（《技术方案》§11.1 + AGENTS.md §6）：Skill 绑定用相对软连接、不写 frontmatter；《技术方案》§8.3 |
| C | `profile.maxIterations()` | `profile.settings().maxIterations()` / `maxHistoryTurns()` | 第 16 节 `Profile` record 结构（settings 嵌套） |
| D | 工具表类名 `ToolRegistry` | 本节 core 契约名 `ToolCatalog`（实现类 `ToolRegistry` 归 20 节） | 避免与 20 节跨模块同名 import 冲突；D-04 |
| E | `session.append(resp)` / `session.appendToolResult(result)` / `sessionManager.save(session)` | `session.appendAssistantMessage(resp)` / `appendToolResult(toolName, result)` / `sessionStore.save(session)` | D-03 建模；「保存」契约归 `SessionStore`（18 节 `SessionManager` 落地） |
| F | 转满上限「返回终止结果」 | 固定文案 `"达到最大轮数，已停止"` | clarify 确认；D-14 |
| G | 「异常不吞」（工具执行异常） 含义歧义 | 原因留痕（结果 + 审计）+ 收敛回填、不中断循环 | FR-008/FR-009/Edge Cases；D-07（**Soft-gate 上报**） |
| H | 手工建表脚本（无版本管理描述） | Flyway 双轨 `V2__tool_invocations.sql`（按节追加） | AGENTS.md §5.1 第 6 条；D-08 |
| I | harness 五个测试类 | 五类原样 + 增 `ToolInvocationRepositoryTest`（storage）、`ReactAssemblyTest`（boot） | 审计建表与零 key 装配需独立可验证；其余五类与清单一一对应 |
| J | 课件未提 `tool_invocations` 的 `trace_id` 列 | 含 `trace_id`（`TraceContext.traceId()` 写入） | AGENTS.md §5.2 + 16 节 `llm_calls` 同口径；D-09 |
| K | 课件未提工具的 profile 过滤 | `ToolCatalog.toolsFor(Profile)` 按 Profile 过滤 | 技术方案 §6.2「按 Agent 声明的工具集过滤」；D-04 |
| L | `application.yaml` 既有键 `sandbox.tool_result_max_chars: 16384` | 本节**不消费**（工具结果超长截断不在本次范围） | spec「明确不做」；键为前序骨架既有，非本节新增 |

> **Step 5 停点待确认项（本节）**：
> ① D-07「异常不吞」的解释（G 条，最有争议）——请确认「收敛为失败结果回填 + 原因落审计」即可，不必向上抛；
> ② 4 个前向契约的命名与落位（D-01/D-04）：`Session`/`SessionStore`/`ToolCatalog`/`ToolInvocationAuditor`/`MemoryProvider`/`ProfileContext`；
> ③ 本节新增 2 个测试类（I 条）超出 harness 五类清单。
> 其余偏差 A/B/C/E/F/H/J/K/L 按上表执行，用户无异议则可直接进 implement。
