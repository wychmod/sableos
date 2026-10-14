# Tasks: Agent Provider——对接大模型的统一入口

**Input**: Design documents from `/specs/001-lesson16-provider/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅

**Tests**: 本节测试为**硬性交付**（课件「验收 harness」+ 用户明确要求）——测试任务与实现任务配对，三个课件关键回归测试原样落地（英文方法名 + `@DisplayName` 保留课件中文原文，断言逻辑逐条保真）。

**Organization**: 按 User Story 分组，保证每段可独立实现与验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1（路由）/ US2（审计）/ US3（工具翻译）

## Path Conventions

Maven 多模块（仓库根 `D:\idea\sableos`）：模块内 `src/main/java/...` 与 `src/test/java/...`；迁移脚本在 `sableos-storage/src/main/resources/db/migration/{vendor}/`。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 依赖与配置面准备（后续所有任务的编译/运行前提）

- [X] T001 [P] sableos-core/pom.xml：新增 `org.yaml:snakeyaml`、`com.fasterxml.jackson.core:jackson-databind` 两个依赖（版本由父 POM 的 Boot BOM 管理，不写版本号；保持 core 其余依赖不变）
- [X] T002 [P] sableos-provider/pom.xml：新增 `org.springframework.ai:spring-ai-openai`、`com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope`（父 POM 已锁版）、`org.springframework.retry:spring-retry`、`org.springframework.boot:spring-boot`（`@ConfigurationProperties` 注解所需；版本全部 BOM 管）
- [X] T003 [P] sableos-boot/src/main/resources/application.yaml：新增 `spring.ai.dashscope.enabled: false`（禁用 starter 全部 10 个 autoconfig）与 `sableos.providers: []`（空清单默认 + 注释示例，示例照 contracts/provider-config.md §1）
- [X] T004 [P] 根 pom.xml：surefire 配置 `<excludedGroups>integration</excludedGroups>`（集成冒烟默认不跑，`-Dgroups=integration` 手动触发）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 契约层 + 存储栈——US1/US2/US3 全部依赖，完成前任何故事不得开工

**⚠️ CRITICAL**: 本阶段不清空，后续任务全部无法编译

### core 契约层

- [X] T005 [P] `sableos-core/src/main/java/com/sableos/core/tool/SableTool.java` + `ToolResult.java`：接口四方法 `String getName()` / `String getDescription()` / `String getInputSchema()`（JSON Schema 文本）/ `ToolResult execute(JsonNode input)`（`com.fasterxml.jackson.databind.JsonNode`）；`ToolResult` 为 record `(boolean success, String content, String errorMessage, boolean retryable)` + 静态工厂 `success(String content)` / `failure(String message, boolean retryable)`（口径：research D-08、技术方案 §6.1）
- [X] T006 [P] `sableos-core/src/main/java/com/sableos/core/provider/` 四个中立 DTO：`ProviderRequest(String content, List<SableTool> availableTools)`、`ProviderResponse(String content, List<ToolCallRequest> toolCalls, Usage usage)`、`ToolCallRequest(String id, String name, String argumentsJson)`、`Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens)`（形状与可空性照 contracts/provider-service.md §3）
- [X] T007 [P] `sableos-core/src/main/java/com/sableos/core/provider/ProviderService.java`（接口：`ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request)`）+ `LlmCallAuditor.java`（七参 `record(String sessionId, String provider, String model, Usage usage, boolean success, String errorMessage, long durationMs)`，签名逐参对齐课件 harness 测试 2 的 verify）
- [X] T008 [P] `sableos-core/src/main/java/com/sableos/core/profile/Profile.java` + `ProfileRegistry.java`：Profile 为 record，嵌套 `Identity(agent_name, prompt)`、`ProviderRef(name, model, temperature)`、`ScheduleConfig(id, cron, zone, message)`、`Settings(max_iterations=10, max_history_turns=20 默认值)`；字段全集照 data-model.md §1（**不含 skills / notify_channels**）；ProfileRegistry 为 `ConcurrentHashMap` 索引，提供 get/all/register/exists
- [X] T009 `sableos-core/src/main/java/com/sableos/core/agent/AgentLoader.java`：构造 `(Path agentsDir, Set<String> knownProviders)`；`loadAll()` 扫 `<agentsDir>/*/AGENT.md` → 解析 `---` 围栏 frontmatter（SnakeYAML）→ `deriveProfile` → 注册进 ProfileRegistry。校验：`name` == 目录名、`provider.name` 在 knownProviders 内（报错点名 provider 与 Agent，用 `IllegalArgumentException`，不新增异常类型）；单目录失败记错误日志并跳过、不阻断其余；目录不存在返回空注册表。**依赖 T001/T008**
- [X] T010 `sableos-core/src/test/java/com/sableos/core/agent/AgentLoaderTest.java`：覆盖【合法 frontmatter 全字段解析】【多 Agent 并存加载】【坏文件（无 AGENT.md / YAML 破损 / name≠目录名）不阻断其余】【provider 未登记 → 报错点名、该 Agent 不注册】【agentsDir 缺失 → 空注册表不抛错】（临时目录 + 手写 AGENT.md 夹具）。**依赖 T009**

### provider 适配层（编译前置；其测试归 US3）

- [X] T011 [P] `sableos-provider/src/main/java/com/sableos/provider/ToolSchemaAdapter.java`：`List<ToolCallback> toSpringAiTools(List<SableTool> tools)`；每个 `SableTool` 映射为 `ToolDefinition.builder().name().description().inputSchema(String).build()` 逐字保真的 `ToolCallback`；`call(...)` **恒抛 `IllegalStateException`**（消息含「Provider 只翻译工具、不执行工具」）；空入参 → 空表（口径：contracts/provider-service.md §4）

### storage 审计栈

- [X] T012 [P] `sableos-storage/src/main/java/com/sableos/storage/LlmCall.java`：`@Entity @Table(name="llm_calls")`，12 列逐列 `@Column(name=...)` 映射照 contracts/llm-calls-schema.md §5；`@Id @GeneratedValue(IDENTITY)`；`createdAt` 由 `@PrePersist` 补 `Instant.now()`
- [X] T013 [P] `sableos-storage/src/main/java/com/sableos/storage/LlmCallRepository.java`：`extends JpaRepository<LlmCall, Long>`
- [X] T014 [P] `sableos-storage/src/main/java/com/sableos/storage/JpaLlmCallAuditor.java`：实现 core 的 `LlmCallAuditor`；`trace_id` 内部取 `TraceContext.traceId()` 写入（**禁 `UUID.randomUUID()`**）；fail-closed——写库异常 LOG.error（消息先经 `replaceAll("[\\r\\n]", "_")` 净化，保持该形态勿改 Pattern/Matcher）后抛 `IllegalStateException`
- [X] T015 [P] `sableos-storage/src/main/resources/db/migration/sqlite/V1__llm_calls.sql`：DDL 逐字照 contracts/llm-calls-schema.md §1（含两个索引）
- [X] T016 [P] `sableos-storage/src/main/resources/db/migration/postgresql/V1__llm_calls.sql`：DDL 逐字照 contracts/llm-calls-schema.md §2（与 sqlite 轨版本号一致、语义 1:1）
- [X] T017 `sableos-storage/src/main/resources/db/migration/README.md`：把已悬空的 T015 五表预留段改写为「按节追加」策略（引用已随提交 17e2cfc 删除的 specs/001-runtime-core 不得保留）

**Checkpoint**: 契约、适配器、存储栈就绪——可开始任一用户故事。

---

## Phase 3: User Story 1 - 多 Agent 多模型并存，按名路由不串台 (Priority: P1) 🎯 MVP

**Goal**: 实例声明多个 provider；Profile 按名选中模型，调用零错配；未登记 provider 显式报错点名。

**Independent Test**: 空清单装配可启动；mock 双 ChatModel 路由断言不串台；ProvidersProperties 违规配置报错点名。

### Tests for User Story 1（先写、初始红）⚠️

- [X] T018 [US1] `sableos-provider/src/test/java/com/sableos/provider/ProviderServiceTest.java`（本故事测试集，与 US2/US3 追加同文件）：
  - `routeByName_twoProvidersDoNotCross` —— 课件关键回归 **测试 1 原样落地**：mock `ChatModel` deepseek/kimi 入 map，以 `profileUsing("kimi")` 调 chat，`verify(kimi, times(1)).call(any())` + `verify(deepseek, never()).call(any())`；`@DisplayName("按名路由：两个 provider 不串台")`
  - `unknownProvider_throwsProviderNotFound` —— 未登记名抛 `ProviderNotFoundException` 且消息点名
  - `responseContent_isReturnedVerbatim` —— 模型文本原样出现在 `ProviderResponse.content()`
  夹具：mock auditor + map 构造（`Map.of("deepseek", m1, "kimi", m2)`）+ Profile 构造器 helper
- [X] T019 [P] [US1] `sableos-provider/src/test/java/com/sableos/provider/ProvidersPropertiesTest.java`：名字空白/重复、api-key 空白/含未展开 `${`、openai 型缺 base-url、非法 type——断言启动校验消息**点名该 provider**

### Implementation for User Story 1

- [X] T020 [P] [US1] `sableos-provider/src/main/java/com/sableos/provider/ProvidersProperties.java`：`@ConfigurationProperties(prefix="sableos")` record（`providers` 清单 + `validate()` 失败快；字段与规则照 contracts/provider-config.md §1，空清单合法）
- [X] T021 [P] [US1] `sableos-provider/src/main/java/com/sableos/provider/ProviderChatModelFactory.java`：`Map<String, ChatModel> build(ProvidersProperties)` 逐条**显式构造**（宪法 IV 禁类型扫描）；`openai`→`OpenAiApi.builder()`/`OpenAiChatModel.builder()`，`dashscope`→`DashScopeApi.builder()`/`DashScopeChatModel.builder()`；共性一律：defaultOptions 内嵌 `internalToolExecutionEnabled(false)`、`retryTemplate` = `RetryTemplate.builder().maxAttempts(1)`、`JdkClientHttpRequestFactory`（连接 10s/读取 120s）、HTTP/1.1、不跟随重定向、base-url 归一（去尾 `/` 与 `/v1`）（口径：research D-04/D-09）
- [X] T022 [P] [US1] `sableos-provider/src/main/java/com/sableos/provider/ProviderNotFoundException.java`：`RuntimeException` 子类，消息含 provider 名
- [X] T023 [US1] `sableos-provider/src/main/java/com/sableos/provider/SpringAiProviderServiceImpl.java`：构造 `(Map<String, ChatModel> models, ProvidersProperties properties, ToolSchemaAdapter adapter, LlmCallAuditor auditor)`；`chat` 全编排——路由（miss→`ProviderNotFoundException`）→ 适配器翻译 → **按 provider type 构造同型 per-call options**（openai→`OpenAiChatOptions.builder()`；dashscope→`DashScopeChatOptions.builder()`；设 model/温度可选/`internalToolExecutionEnabled(false)`/工具非空才挂）→ `model.call(new Prompt(List.of(new UserMessage(content)), options))` → 响应映射（`getResult().getOutput().getText()`、`getToolCalls()`→`ToolCallRequest` 原样、`getMetadata().getUsage()` null 安全）；审计编排照 research D-05（失败先落盘再原异常上抛、成功记录 fail-open）。**依赖 T021/T022/T011/T007**
- [X] T024 [US1] `sableos-boot/src/main/java/com/sableos/boot/provider/ProviderConfiguration.java`：装配 ProvidersProperties（校验）、ToolSchemaAdapter、`LlmCallAuditor`←`JpaLlmCallAuditor`、`ProviderService`←`SpringAiProviderServiceImpl`（bean 方法内 `new ProviderChatModelFactory().build(props)` 构造映射表）；**不注册任何 `ChatModel` bean**
- [X] T025 [US1] `sableos-boot/src/main/java/com/sableos/boot/agent/AgentConfiguration.java`：`AgentLoader`（agentsDir=`.sableos/agents`，knownProviders=清单名字集）+ `ProfileRegistry`（启动 `loadAll()`）；并改 `sableos-boot/src/main/java/com/sableos/boot/SableOsApplication.java` 追加 `@EntityScan` / `@EnableJpaRepositories`（`com.sableos.storage`）
- [X] T026 [US1] `sableos-boot/src/test/java/com/sableos/boot/ProviderAssemblyTest.java`：`@SpringBootTest`（`@DynamicPropertySource` 把 datasource url 指到临时目录 SQLite，防污染仓库）——空 providers 清单上下文可启动；六个 bean 齐备；ProfileRegistry 空；以未登记 provider 调用抛 `ProviderNotFoundException`。**依赖 T024/T025**

**Checkpoint**: US1 独立可验——路由/校验/装配全绿。

---

## Phase 4: User Story 2 - 每次调用可审计：成功与失败都留痕 (Priority: P2)

**Goal**: 成败两态落 `llm_calls`（含 trace_id）；失败先留痕再上抛。

**Independent Test**: 失败调用断言「审计落了 success=false + 原因」且异常上抛；仓库层真 Flyway 迁移读写两态记录。

### Tests for User Story 2

- [X] T027 [US2] `sableos-provider/src/test/java/com/sableos/provider/ProviderServiceTest.java` 追加（同文件，续在 T018 后）：
  - `failedCall_auditRecordsFailureAndRethrows` —— 课件关键回归 **测试 2 原样落地**：`thenThrow(new RuntimeException("connect timeout"))`，`assertThrows` + `verify(audit).record(eq("s-1"), eq("deepseek"), any(), isNull(), eq(false), contains("timeout"), anyLong())`；`@DisplayName("调用失败：审计必须留下 success=false 的记录且异常继续上抛")`
  - `successfulCall_auditsSuccessWithUsage` —— 成功路径 `record(..., eq(true), isNull(), anyLong())` 且 usage 透传
  - `successAuditWriteFailure_returnsResponseAnyway` —— 成功审计写失败 fail-open（返回业务结果）
- [X] T028 [US2] `sableos-storage/src/test/java/com/sableos/storage/StorageTestApplication.java` + `LlmCallRepositoryTest.java`：测试引导类 + SQLite 临时库 + **真 Flyway 迁移**（拒绝 ddl-auto）；断言：成败两态存取往返；`success`/`error_message`/`trace_id` 列经 `PRAGMA table_info` 真实存在；`TraceContext.openRoot("web")` 作用域内经 `JpaLlmCallAuditor` 写入后读回的 `trace_id` 与上下文一致（无上下文时 null）。**依赖 T012-T016**

**Checkpoint**: US1+US2 独立可验——审计链全绿。

---

## Phase 5: User Story 3 - 工具说明只翻译不执行，执行权留在上层 (Priority: P3)

**Goal**: schema 保真翻译；请求内自动执行关闭；模型工具请求原样上交、零执行。

**Independent Test**: 适配器产物字段一一对齐且 `call()` 显性抛错；带工具调用时捕获的 Prompt 选项断言 `internalToolExecutionEnabled=false`。

### Tests for User Story 3

- [X] T029 [P] [US3] `sableos-provider/src/test/java/com/sableos/provider/ToolSchemaAdapterTest.java`：name/description/inputSchema 逐字进 `ToolDefinition`；非空工具 → 非空 `ToolCallback` 表；`call(...)` 抛 `IllegalStateException`（消息含「只翻译不执行」）；空表 → 空表
- [X] T030 [US3] `sableos-provider/src/test/java/com/sableos/provider/ProviderServiceTest.java` 追加（同文件，续在 T027 后）：
  - `toolSchemaCall_disablesInternalToolExecution` —— 课件关键回归 **测试 3 适配版原地落地**：`ArgumentCaptor<Prompt>` 捕获 `model.call` 入参，`getOptions()` 转 `ToolCallingChatOptions` 后断言 `getInternalToolExecutionEnabled() == Boolean.FALSE`、toolCallbacks 非空且与适配器产物一致、选项里的 model 名与 Profile 一致；`@DisplayName("带工具 schema 调用：请求里关闭了自动执行")`
  - `modelToolCalls_returnedVerbatimWithoutExecution` —— 模型返回工具请求 → `ProviderResponse.toolCalls()` 的 id/name/argumentsJson 逐字段原样

**Checkpoint**: 三个故事全部独立可验。

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T031 [P] `sableos-boot/src/test/java/com/sableos/boot/ProviderSmokeIT.java`：`@Tag("integration")`；`@DynamicPropertySource` 注入 `DEEPSEEK_API_KEY` 环境值（无 key 走 Assumptions 跳过）+ `sableos.providers[0]`（name=deepseek/type=openai/base-url=https://api.deepseek.com）；真调一次断言非空文本；经 `LlmCallRepository` 断言新增 `success=true` 行（provider/model/token/耗时非空）
- [X] T032 全量门禁：`mvn -q spotless:apply` → `mvn clean verify` 全绿（Spotless/Checkstyle/P3C/SpotBugs 含测试 + **前序节全部测试回归**；不删断言、不 @Disabled、不放宽阈值）
- [X] T033 [P] 手工项核对（quickstart 场景 E）：`grep -rn "sk-"` 在 `*.java/*.yaml/*.sql` 零命中；零 key 起服务确认 `llm_calls` 表经 Flyway 生成（场景 C）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup（T001-T004）**: 无依赖，立即开工
- **Foundational（T005-T017）**: 依赖 Setup；**阻塞全部故事**（T009 依赖 T001/T008；T010 依赖 T009）
- **US1（T018-T026）**: 依赖 Foundational；T023 依赖 T021/T022/T011/T007；T026 依赖 T024/T025
- **US2（T027-T028）**: 依赖 Foundational（存储栈）+ T023（编排已就位）；T027 与 T018 同文件**串行**
- **US3（T029-T030）**: 依赖 Foundational（T011）+ T023；T030 与 T027 同文件**串行**
- **Polish（T031-T033）**: 依赖全部故事；T031 依赖 T024-T026

### Within Each User Story

- 测试先行（课件：「测试清单要在实现之前（或同时）定下来」）；本表按「测试 → 实现」排布，同文件测试任务（T018→T027→T030）严格串行
- 同故事内实现顺序：配置模型 → 工厂 → 服务 → 装配

### Parallel Opportunities

- T001-T004 全并行（四处不同文件）
- Foundational 中 T005/T006/T007/T008/T011/T012/T013/T014/T015/T016/T017 可并行（不同文件）；T009/T010 串行尾随
- US1 内 T020/T021/T022 可并行；US2 的 T028 与 US3 的 T029 可并行（不同文件）
- US1 完成后 US2/US3 的实现面已具备（共用 T023 编排），仅测试文件串行追加

---

## Parallel Example: Foundational 批量

```text
# 可同时下发的契约层任务（不同文件）：
Task: "T005 SableTool + ToolResult in sableos-core/src/main/java/com/sableos/core/tool/"
Task: "T006 四个中立 DTO in sableos-core/src/main/java/com/sableos/core/provider/"
Task: "T007 ProviderService + LlmCallAuditor 接口 in sableos-core/src/main/java/com/sableos/core/provider/"
Task: "T015 sqlite/V1__llm_calls.sql in sableos-storage/src/main/resources/db/migration/"
Task: "T016 postgresql/V1__llm_calls.sql in sableos-storage/src/main/resources/db/migration/"
```

## Parallel Example: US1 实现面

```text
Task: "T020 ProvidersProperties in sableos-provider/src/main/java/com/sableos/provider/ProvidersProperties.java"
Task: "T021 ProviderChatModelFactory in sableos-provider/src/main/java/com/sableos/provider/ProviderChatModelFactory.java"
Task: "T022 ProviderNotFoundException in sableos-provider/src/main/java/com/sableos/provider/ProviderNotFoundException.java"
# T023 待三件就位后串联
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Setup（T001-T004）→ Foundational（T005-T017）→ US1（T018-T026）
2. **STOP and VALIDATE**：`mvn -pl sableos-core,sableos-provider,sableos-storage,sableos-boot test`——路由/校验/装配独立可验

### Incremental Delivery

1. Foundational 就绪 → 契约与存储可独立编译
2. US1 → 路由 MVP（可演示「双 Provider 不串台」）
3. US2 → 审计补齐（可演示「失败也留痕」）
4. US3 → 工具边界钉死（可演示「只翻译不执行」）
5. Polish → 冒烟 + 全量门禁

### 与 sableos-lesson-dev 门禁的衔接

- 每个任务落地即跑对应模块测试（任务级 DoD）；T032 是节级「实现完成」的硬门禁定义
- 三个课件关键回归测试（T018/T027/T030）不作任何弱化；测试方法名英文、`@DisplayName` 保留课件中文

---

## Notes

- [P] = 不同文件、无未完成依赖；**同文件测试追加任务（T027/T030）不可并行**
- 实现期第三方 API 一律以本地 javap 复核为准（research 附录 A 已核实基线；写码中新引入的 API 再查）
- traceId：只用 `TraceContext.traceId()` / `TraceIds`，禁 `UUID.randomUUID()`；用户可控字符串入日志先 CRLF 净化（`replaceAll("[\\r\\n]", "_")` 形态）
- 全程不自动 commit / push / 跑 package.sh
