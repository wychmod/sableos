# Phase 0 Research: Agent Provider——对接大模型的统一入口

**Feature**: 001-lesson16-provider | **Date**: 2026-10-14

本文件记录规划阶段的全部技术决策与核实证据。证据分三类：

- **文档锚点**：仓库内权威文档（课件 16 / 《技术方案》/ 宪章 / AGENTS.md）；
- **本地依赖实测**：对 Maven 本地仓库（`D:\maven\Repository`）内已解析 jar 的 javap 核对；
- **同构先例**：参照仓库 `D:\idea\oryxos`（同课件序列的先行实现，已运行验证）。

权威顺序（AGENTS.md 第 4 章）：多份文档打架时以《技术方案》为准，其次 skill，再次课件。课件与《技术方案》冲突处一律记入附录 B，随 Step 5 停点逐条上报，不在规划中静默取舍。

## 决策摘要

| # | 决策 |
| --- | --- |
| D-01 | Provider 契约（接口 + 中立 DTO）放 core，Spring AI 实现放 provider（依赖倒置） |
| D-02 | `AgentLoader` 取代课件 `ProfileLoader`，扫 `.sableos/agents/<name>/AGENT.md` 派生 Profile |
| D-03 | OpenAI 兼容端点用 raw `spring-ai-openai`；DashScope starter 以单属性 `spring.ai.dashscope.enabled=false` 禁用自动装配 |
| D-04 | 显式映射工厂 + per-call 同型 options（显式关自动执行） |
| D-05 | 审计编排语义：成败两态、auditor fail-closed、失败路径原异常上抛 |
| D-06 | `llm_calls` 表结构（含 `trace_id`）+ 按节手写双轨 `V1__llm_calls.sql` |
| D-07 | 中立 DTO 最小形：`ProviderRequest(content, tools)` 等四件 |
| D-08 | `SableTool`/`ToolResult` 契约提前到本节落 core（§6.1） |
| D-09 | base-url 归一、超时常量、单次重试（不引入配置键） |
| D-10 | 测试分层：单测 mock 零网络、装配测试、冒烟 `@Tag("integration")` |
| D-11 | `sableos.providers` 配置模型：name/type/api-key/base-url + 空清单可启动 |
| D-12 | AGENT.md frontmatter 用 SnakeYAML 解析；不做 frontmatter 环境变量占位 |

---

## D-01 契约倒置：ProviderService 接口与中立 DTO 放 core，实现放 provider

**Decision**: `com.sableos.core.provider` 放契约——`ProviderService` 接口、`ProviderRequest`/`ProviderResponse`/`ToolCallRequest`/`Usage`/`LlmCallAuditor` 中立 DTO 与接口；`com.sableos.provider`（模块 `sableos-provider`）放 Spring AI 实现 `SpringAiProviderServiceImpl`、`ProviderChatModelFactory`、`ToolSchemaAdapter`、`ProvidersProperties`、`ProviderNotFoundException`；`sableos-storage` 放 `LlmCall`/`LlmCallRepository`/`JpaLlmCallAuditor`；`sableos-boot` 只做装配。

**Rationale**:

- 第 17 节 ReActLoop 在 `sableos-core` 内消费 `providerService.chat(session.id(), profile, prompt)`（课件 17 第 60 行），而本仓库 core 模块当前只依赖 `slf4j-api`（core/pom.xml 注释「core 保持框架无关」）；接口若放 provider 模块，core→provider 成环。
- core 不得染 Spring AI 类型：DTO 全为 JDK 类型（String/List/record），`Profile` 亦然——这保证 17 节 ReActLoop 写测试时无需 Spring AI 环境。
- 《技术方案》§8.5 已有同款先例：「契约在 core、实现在 storage（依赖倒置）：`ScheduledTaskStore` 接口放 `sableos-core`，JPA 实现放 `sableos-storage`」。§3.1 的「ProviderService 归 provider 模块」说的是模块归属，不排斥在其内部分层。

**Alternatives considered**:

- 接口直接放 provider 模块（照 §10 模块表字面）——core 反向依赖 provider，17 节无法落地；
- 接口放 core 但 DTO 直接复用 Spring AI 的 `Prompt`/`ChatResponse`——core 染框架，违背 core 现有约束。

## D-02 AgentLoader 扫 `.sableos/agents/<name>/AGENT.md`

**Decision**: 类名 `AgentLoader`，构造参数 `(Path agentsDir, Set<String> knownProviders)`；`loadAll()` 扫各子目录，`AGENT.md` frontmatter 经 `deriveProfile` 派生 `Profile` 注册进 `ProfileRegistry`；单目录失败（无文件 / 解析失败 / provider 未登记）记错误日志并跳过，不阻断其余加载。

**Rationale**:

- 《技术方案》§8.2（第 483 行）逐句就是此形：「扫 `.sableos/agents/` 各子目录，`deriveProfile` 把每个 `AGENT.md` 的 frontmatter 派生成一个 `Profile`……校验失败的 Agent 不阻断启动但记录错误日志」；
- AGENTS.md §6「一个目录 = 一个 Agent」与宪章原则 V 同口径；29 节的运行时注册也锚定此结构；
- 课件 16（第 64 行）写的是 `ProfileLoader` 扫 `.sableos/profiles/*.yaml`——**属课件与《技术方案》的口径差，记附录 B(A)，随停点上报**。

**Alternatives considered**: 照课件保留 `ProfileLoader` + `.sableos/profiles/` 目录——与《技术方案》§8.2、宪章修订和 29 节锚点全部冲突，按权威顺序否决。

## D-03 依赖形态：raw spring-ai-openai + DashScope starter 单属性禁用

**Decision**:

1. OpenAI 兼容端点（deepseek / kimi / glm 等）走 `org.springframework.ai:spring-ai-openai`（1.1.8，随 BOM）：显式 `OpenAiApi.builder()` + `OpenAiChatModel.builder()` 构造，不经任何 starter；
2. DashScope（qwen）走父 POM 已锁的 `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope`（1.1.2.4-security-fix），boot 的 `application.yaml` 写 `spring.ai.dashscope.enabled: false` 全局关掉其自动装配。

**Rationale**（均为本地实测证据，见附录 A）：

- `spring-ai-openai-1.1.8.jar` 内**无任何 autoconfiguration 类**——不需要排除、也不会因缺 key 启动失败；经 `dependency:tree` 义务（课件坑三）在本地仓库已确认可下载、可解析（jar 实存且 pom 完整）。
- `spring-ai-alibaba-autoconfigure-dashscope` 的 **10 个 autoconfig 全部带 `@ConditionalOnDashScopeEnabled`**（= `@ConditionalOnProperty(name="spring.ai.dashscope.enabled", ...)`）：DashScopeChat / Agent / Image / Video / AudioSpeech / AudioTranscription / Rerank / Embedding / MultimodalEmbedding / AsyncToolCallingManager。设 `spring.ai.dashscope.enabled: false` 即整体禁用，且**新增/升级带来的第 11 个 autoconfig 也自动被同一开关覆盖**——比 10 个 FQCN 的排除类清单健壮得多。
- 课件 26 节曾出现的排除实例 `org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration` 在 1.1.x 类路径中**根本不存在**（陈旧路径），照抄必错——也印证「排除清单」这一手法易随升级失效。

**Alternatives considered**:

- 排除类清单（10 个 FQCN 或 1 个陈旧 FQCN）——升级后静默失效或直接写错类名；
- 裸 `spring-ai-alibaba-dashscope` 核心 jar（绕开 starter）——无 BOM 管版本、需自行收敛全部传递依赖，收益为零。

## D-04 显式映射工厂 + per-call 同型 options（关自动执行）

**Decision**:

- `ProviderChatModelFactory.build(ProvidersProperties) → Map<String, ChatModel>`：遍历声明清单**逐条显式构造**——`type: openai` 走 `OpenAiChatModel.builder().openAiApi(OpenAiApi.builder().baseUrl(..).apiKey(..).build()).defaultOptions(OpenAiChatOptions.builder()...build()).retryTemplate(单次).build()`；`type: dashscope` 走 `DashScopeChatModel.builder().dashScopeApi(DashScopeApi.builder()...build()).defaultOptions(DashScopeChatOptions.builder()...build()).retryTemplate(单次).build()`。零类型扫描（宪法原则 IV）。
- 两类模型的 defaultOptions 均内嵌 `internalToolExecutionEnabled(false)`（belt & braces）。boot 侧不注册任何 `ChatModel` 的 Spring bean——**模型不经容器暴露**，路由表只存在于 `ProviderService` 实现内部，杜绝「容器里多类型 ChatModel 分不清谁是谁」的情况再次出现。
- `SpringAiProviderServiceImpl` per-call 选项**按 provider type 构造同型 options**（openai→`OpenAiChatOptions.builder()`；dashscope→`DashScopeChatOptions.builder()`），每次调用显式设置：`model`（来自 Profile）、`temperature`（Profile 有值才设）、`internalToolExecutionEnabled(false)`、`toolCallbacks`（适配器翻译产物，非空才设）；`new Prompt(List.of(new UserMessage(request.content())), options)`。

**Rationale**:

- 全部 API 面已 javap 实测存在（附录 A），包括 dashscope 的 `withModel/withTemperature/withToolCallbacks/withInternalToolExecutionEnabled` 与 openai 的 `model/temperature/toolCallbacks/internalToolExecutionEnabled`；
- 「prompt 选项与模型默认选项同型」保证选项合并走同类路径——这是 oryxos 已验证的运行形态（其全链路依赖 prompt options 里的 toolCallbacks 抵达真实请求、且关自动执行生效）；
- harness 测试 3 的断言口径（`Prompt.getOptions()` 转 `ToolCallingChatOptions` 后读 `getInternalToolExecutionEnabled()` 与工具列表）对两型均成立：javap 证实 `OpenAiChatOptions` 与 `DashScopeChatOptions` 都 `implements ToolCallingChatOptions`。

**Alternatives considered**:

- 通用 `ToolCallingChatOptions.builder()`（产 `DefaultToolCallingChatOptions`）——跨类合并（generic→typed）的语义未在本地源码级核实（`ModelOptionsUtils.mergeBeans` 的跨类分支需实测），不做未知路径的赌注；
- oryxos 原样硬编码 `OpenAiChatOptions`——不支持 dashscope 型，且把类型知识散在 impl 里。

## D-05 审计编排语义：成败两态、fail-closed、原异常上抛

**Decision**:

- core 接口 `LlmCallAuditor` 七参签名——`record(String sessionId, String provider, String model, Usage usage, boolean success, String errorMessage, long durationMs)`（与课件 harness 测试 2 的 `verify(audit).record(eq("s-1"), eq("deepseek"), any(), isNull(), eq(false), contains("timeout"), anyLong())` 逐参对齐）；
- `JpaLlmCallAuditor`（storage）**fail-closed**：写库失败记 LOG.error（CRLF 净化）后抛 `IllegalStateException`；
- `chat()` 编排：`model.call` 置于 `try`；`catch (RuntimeException e)` 内先 `auditor.record(..., false, e.getMessage(), ...)`——若审计写自身失败，LOG.error + `e.addSuppressed`，**原异常照抛**；成功路径的 `record(..., true, usage...)` 放在 `try` 之后，若写失败仅 LOG.error（fail-open），业务结果照常返回。

**Rationale**:

- 课件骨架（第 121-128 行）成功记录写在 try 内——成功记录自身若抛异常会坠入 catch 再补一条失败记录，同一次调用出现「一败一胜」两条互相矛盾的审计；把成功记录移出 try 消除该窗口（附录 B(H) 上报）；
- 测试 2 用 mock auditor（不会真抛），断言不受编排位置影响；
- fail-closed 保住「审计是审计、不能静默丢」的底线（原则 VI），成功路径 fail-open 避免用审计写抖动否决一次已完成的真实调用——与 oryxos 现行语义一致。

**Alternatives considered**: 课件字面骨架（成功记录在 try 内）——双写窗口；审计全 fail-open——违背原则 VI。

## D-06 `llm_calls` 表与双轨迁移

**Decision**: `llm_calls` 12 列——`id / session_id / provider / model / prompt_tokens / completion_tokens / total_tokens / trace_id / success / error_message / duration_ms / created_at`（dose 定义见 data-model 与 contracts/llm-calls-schema.md）。`trace_id` 可空，写入方取 `TraceContext.traceId()`（无链路上下文时为 null），**禁用 `UUID.randomUUID()`**（AGENTS.md §5.2）。迁移按节手写 `V1__llm_calls.sql` 双轨（`sqlite/` 与 `postgresql/` 1:1）；storage 的 `db/migration/README.md` 中已悬空的 T015 五表预留段同步改写为「按节追加」策略。

**Rationale**: 原则 VI「审计 day one 落库」+ 课件第 133 行「建表用手工维护的脚本，别指望 ddl-auto」；原 README 预留引用的 `specs/001-runtime-core/data-model.md` 已随提交 `17e2cfc` 删除——引用悬空必须修正（附录 B(F) 上报）。成本列 `cost_micros`、`profile_name` 是 oryxos 后续节扩展，本节 spec 实体口径不含（附录 B(I) 上报）。

**Alternatives considered**: ①一次性建五表（旧 T015 预留）——为未实现能力建空表且引用已失效；②拖到 US 完成统一建——每节不可独立验证。

## D-07 中立 DTO 最小形

**Decision**: `ProviderRequest(String content, List<SableTool> availableTools)`；`ProviderResponse(String content, List<ToolCallRequest> toolCalls, Usage usage)`（content 可为 null/空，toolCalls 恒非空）；`ToolCallRequest(String id, String name, String argumentsJson)`（id 原样透传）；`Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens)`（可整体为 null——失败或供应商未返回用量时）。全部为 core record，零 Spring AI 类型。

**Rationale**: oryxos 的 `ProviderRequest` 演进到多轮后仍保留 `(content, availableTools)` 兼容构造器——其早期形态（对应 SableOS 本节位置）就是 content+工具列表；课件 17 的消费面只用到 `resp.toolCalls()`（第 65 行）与文本，此形足够；Message 列表等 ReAct 多轮需求出现时（17 节后）再演进，不提前设计。

**Alternatives considered**: 一步到位 `(systemPrompt, List<Message>, tools)`——为未来需求预建抽象，且 core 需自养 Message 类型体系。

## D-08 `SableTool`/`ToolResult` 契约提前落 core

**Decision**: `SableTool` 四方法——`String getName()` / `String getDescription()` / `String getInputSchema()`（JSON Schema **文本**）/ `ToolResult execute(JsonNode input)`（`com.fasterxml.jackson.databind.JsonNode`，Jackson 2 由 BOM 管版本）；`ToolResult` 为 record——`(boolean success, String content, String errorMessage, boolean retryable)` + 静态工厂 `success(String content)` / `failure(String message, boolean retryable)`。

**Rationale**: 本节交付物点名「工具格式适配器」，其入参就是 `SableTool.getInputSchema()`（课件 16 第四节）；《技术方案》§6.1 已定义四方法与 `ToolResult` 四字段（成功标识、结果内容、错误信息、是否可重试）；课件 20 草图里的 `JsonSchema` 类型在锁定版本（spring-ai-model 1.1.8）中**不存在**（jar 列表只有 `JsonSchemaGenerator`/`JsonSchemaUtils`），且 Spring AI `ToolDefinition.inputSchema()` 收的就是 String（javap 实证）——用 String 反而与下游零转换。jackson-databind 为 core 新增依赖（BOM 管版本），与 §6.1「execute 接收 JSON 输入」对应。

**Alternatives considered**: ①推迟到 20 节——本节适配器无类型可译，20 节还要回改前序节公共接口；②自造 core `JsonSchema` 类型——凭空造概念（软门禁 #1 范围外）。

## D-09 base-url 归一、超时常量、单次重试

**Decision**: 工厂内置三项网络策略（常量，不新增配置键）：①base-url 归一——去尾部 `/`、去尾部 `/v1`（避免与 SDK 自带路径叠成 `/v1/v1/...`）；②HTTP 连接 10s / 读取 120s（`JdkClientHttpRequestFactory`），HTTP/1.1、不跟随重定向；③`RetryTemplate.builder().maxAttempts(1).build()` 替掉 Spring AI 默认的 10 次指数退避。

**Rationale**: 三项均为 oryxos 运行验证过的同款策略；单次重试让失败确定性上抛、避免框架层静默重试放大延迟与 token 消耗（fallback/重试策略属扩展阶段，本节边界明确不做）；超时防冒烟与运行期悬挂。常量形态使本节不新增任何未列明配置键（软门禁 #1 自律），后续按需升格为配置。

**Alternatives considered**: 保留默认 RetryTemplate——失败延迟不可控；超时与 base-url 做成配置键——超出交付物清单。

## D-10 测试分层与落点

**Decision**: 六个测试类——

| 测试类 | 模块 | 形态 |
| --- | --- | --- |
| `AgentLoaderTest` | core | 纯单测（临时目录 + 手写 AGENT.md） |
| `ProviderServiceTest` | provider | 纯单测（mock ChatModel/auditor；含课件三个关键回归测试，原样落地） |
| `ToolSchemaAdapterTest` | provider | 纯单测 |
| `ProvidersPropertiesTest` | provider | 纯单测（校验报错点名 provider） |
| `LlmCallRepositoryTest` | storage | `@SpringBootTest`（测试引导类 + SQLite 临时库 + 真 Flyway 迁移） |
| `ProviderAssemblyTest` | boot | `@SpringBootTest`（空 key 可启动 + 装配存在性 + 未知名抛错） |
| `ProviderSmokeIT` | boot | `@SpringBootTest` + `@Tag("integration")`（真 key 真调 + 审计落库断言） |

三个关键回归测试按课件第 168-201 行原样落地，方法名译英文（`routeByName_twoProvidersDoNotCross` 等），`@DisplayName` 保留课件中文原文。

**Rationale**: 课件「先定分层」：单测不碰网络（mock `ChatModel`、mock auditor），冒烟按 tag 隔离、CI 默认跳过；`LlmCallRepositoryTest` 的「手工脚本建表」精神在 SableOS = **真 Flyway 迁移**（拒绝 ddl-auto），并断言 `success`/`error_message` 列真实存在（PRAGMA 表结构核对）；冒烟放 boot 因需完整装配（真工厂 + 真 SQLite + 真迁移），断言「非空响应 + `llm_calls` 新增 success=true 一条」两个要点。

**Alternatives considered**: 冒烟放 provider 模块——需自建完整 Spring 装配，与 boot 重复劳动。

## D-11 `sableos.providers` 配置模型

**Decision**: 全局层 `sableos.providers[]`，条目四键——`name`（必填，名字键）、`type`（`openai`|`dashscope`，缺省 `openai`）、`api-key`（必填，`${ENV:}` 占位，含未解析 `${` 拒绝）、`base-url`（openai 型必填非空；dashscope 型可选）。`ProvidersProperties`（`@ConfigurationProperties(prefix="sableos")` record）提供 `validate()` 启动期失败快：名字空白/重复、api-key 空白/未展开、base-url 缺失——报错信息均**点名具体 provider**。默认清单为空列表（无 key 即可启动，CI/装配测试全绿），`application.yaml` 只留注释示例。

**Rationale**: 课件全局层示例只有 `name + api-key`（第 100 行附近）；SableOS 两个协议栈共存必须能分辨类型（`type`），OpenAI 兼容端点必须能指到厂商地址（`base-url`）——两项为该清单外新增配置键（附录 B(D) 上报）；空清单默认值使「声明即校验」（FR-002）成立的同时不破坏无凭证环境。

**Alternatives considered**: 照课件只留 name+api-key——openai 型无法指向 deepseek/kimi 端点、无法区分 dashscope；给 base-url 硬编码默认——耦合单一厂商。

## D-12 AGENT.md frontmatter 解析策略

**Decision**: 只解析 `AGENT.md` 顶部 `---` 围栏内的 frontmatter：SnakeYAML（BOM 管版本，core 新增依赖）安全加载为 Map，再逐字段映到 Profile record；`name` 必须与目录名一致，`provider.name` 必须已登记，其余字段本节只做结构解析、不做深层校验（工具/渠道/Bootstrap 的校验随各自模块到位后替补）。**不做** frontmatter 环境变量占位解析——本节 Profile 里没有凭证字段（凭证只在全局层，由 Spring 占位符机制负责）。

**Rationale**: 《技术方案》§8.2「frontmatter 派生成 Profile」+ §8.3 正文与 frontmatter 分属 AgentLoader/ContextLoader 两层职责；课件 harness 的 `${ENV}` 占位测试项（第 159 行）原文针对 Profile YAML 中的凭证场景——SableOS 结构调整后该项由 `ProvidersPropertiesTest` + 冒烟 IT 承担（附录 B(J) 上报）。SnakeYAML 的 YAML 1.1 语义（`yes/no` 会被解析为布尔）属已知特性，示例与测试中字符串一律加引号规避。

**Alternatives considered**: 自写 frontmatter 解析——重复造轮子且 SnakeYAML 已在 BOM 内；把 `${ENV}` 占位解析也搬进 frontmatter——无实际使用场景（本节无凭证字段）。

---

## 附录 A：本地依赖核实清单（javap 实测）

全部核对对象为 `D:\maven\Repository` 内已解析的锁定版本 jar：

| 构件 | 版本 | 已核实的关键 API |
| --- | --- | --- |
| `spring-ai-openai` | 1.1.8 | 无 autoconfiguration 包；`OpenAiApi.builder()`；`OpenAiChatModel.builder().openAiApi().defaultOptions().retryTemplate().build()`；`OpenAiChatOptions implements ToolCallingChatOptions`，Builder 有 `model/temperature/toolCallbacks/internalToolExecutionEnabled/build` |
| `spring-ai-alibaba-dashscope` | 1.1.2.4-security-fix | `DashScopeApi.builder()`；`DashScopeChatModel.builder().dashScopeApi().defaultOptions().retryTemplate().build()`；`DashScopeChatOptions implements ToolCallingChatOptions`，Builder 有 `withModel/withTemperature/withToolCallbacks/withInternalToolExecutionEnabled/build` |
| `spring-ai-alibaba-autoconfigure-dashscope` | 1.1.2.4-security-fix | 10 个 autoconfig 全部挂 `@ConditionalOnDashScopeEnabled`（单属性开关） |
| `spring-ai-model` | 1.1.8 | `ToolCallingChatOptions.getInternalToolExecutionEnabled()/setToolCallbacks()/builder()`（静态）；`DefaultToolCallingChatOptions` 存在；`ToolDefinition.builder().name().description().inputSchema(String).build()`；`ToolCallback.getToolDefinition()/call(String)`；`ModelOptionsUtils`（选项合并，`BEAN_MERGE_FIELD_EXCISIONS=["class"]`） |
| `spring-ai-client-chat` | 1.1.8 | `Prompt(List<Message>, ChatOptions)` 构造与 `getOptions()`；`ChatModel.call(Prompt)` / `getDefaultOptions()`；`ChatOptions.copy()`；`ChatResponse.getResult().getOutput().getText()`；`AssistantMessage.ToolCall(String id,String type,String name,String arguments)` record；`ChatResponseMetadata.getUsage()` → `Usage.getPromptTokens()/getCompletionTokens()/getTotalTokens()` |
| `spring-ai-retry` | 1.1.8 | `RetryUtils.DEFAULT_RETRY_TEMPLATE`（默认重试模板存在，我们以 `RetryTemplate.builder().maxAttempts(1)` 替换）；spring-retry 1.3.1+ 在本仓库可解析 |
| `spring-ai-alibaba-starter-dashscope` | 1.1.2.4-security-fix | 父 POM dependencyManagement 已显式锁版本（「阿里 BOM 未收录」注记） |

## 附录 B：课件与权威文档口径差清单（随 Step 5 停点逐条呈报）

| # | 课件 16 原文口径 | SableOS 采用口径 | 依据 |
| --- | --- | --- | --- |
| A | `ProfileLoader` 扫 `.sableos/profiles/*.yaml` | `AgentLoader` 扫 `.sableos/agents/<name>/AGENT.md` | 《技术方案》§8.2 + 宪章原则 V；测试类相应改名 `AgentLoaderTest` |
| B | Profile 字段含 `skills`、`notify_channels` | 不含二者（字段集按 §8.2：name/description/identity/provider/tools/mcp_servers/channels/schedules/bootstrap/settings） | §8.2 明示 notify_channels 不入 Profile；项目约定（《技术方案》§11.1 + AGENTS.md §6）：Skill 绑定由软连接表达，不写 frontmatter |
| C | 课件未提 `SableTool`/`ToolResult` 归属 | 契约随本节落 core | 课件 16 适配器引用 `getInputSchema()`；§6.1 已定义 |
| D | 全局层仅 `name` + `api-key` | 增 `type`、`base-url` 两键 | 双协议栈（openai/dashscope）与现实端点要求 |
| E | 「阿里的 starter」泛指 | `spring-ai-alibaba-starter-dashscope` + 单属性禁用；openai 走 raw jar | 本仓库锁定依赖实测（附录 A） |
| F | 「手工建表脚本」未言版本管理 | Flyway 双轨 `V1__llm_calls.sql`（按节追加） | AGENTS.md §5.1 第 6 条（表结构唯一权威是 Flyway 双轨目录）；旧 T015 预留引用已悬空 |
| G | `ProviderService.chat(sessionId, Profile, Prompt)` | core 接口 `ProviderService.chat(String, Profile, ProviderRequest)` + provider 实现 | D-01（core 不染 Spring AI；`Prompt` 为 Spring AI 类型）；方法语义与调用位移位保持不变 |
| H | 成功审计写在 try 内 | 成功审计移出 try | D-05（消除双写窗口）；三个回归测试断言不受影响 |
| I | （未涉及列集） | `llm_calls` 不含 `cost_micros`/`profile_name` | 本节 spec 实体口径；成本与画像列随对应能力节追加 |
| J | `ProfileLoaderTest` 覆盖 `${ENV}` 占位解析 | 改由 `ProvidersPropertiesTest` + 冒烟 IT 承担 | 本节 Profile 无凭证字段；全局层占位由 Spring 机制负责 |
| 多 | harness 清单四个测试类 | 增 `ProvidersPropertiesTest`、`ProviderAssemblyTest` | 配置校验（FR-002）与装配装配可启动（无 key CI）需要；其余六类与清单一一对应 |

> **Step 5 停点确认（2026-10-14）**：经用户逐项确认——
> ① D（增 `type`/`base-url` 两键）按本节口径执行；
> ② G（接口放 core 用中立 DTO、实现放 provider）确认为依赖倒置落法，理由与 §8.5 `ScheduledTaskStore`、§10 knowledge 两处既有先例一致（用户已理解 core→provider 环的成因）；
> ③ H 的成功路径语义定为 fail-open（审计写失败仅记日志、返回业务结果）。
> 其余偏差 A/B/C/E/F/I/J 与「多」出任务项用户无异议，照清单执行；tasks.md 无需返工。
