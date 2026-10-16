---
description: "Task list for 005-lesson20-tool（第20节 Tool 体系）"
---

# Tasks: 第20节 Tool 体系（统一注册表 / 内置与扩展工具 / MCP / 注解管道 / Sandbox 前向接口 / notify 注册）

**Input**: Design documents from `/specs/005-lesson20-tool/`
**Prerequisites**: plan.md、spec.md（6 个 User Story）、research.md（D-01~D-12）、data-model.md、contracts/（5 份）、quickstart.md

**Tests**: 本特性测试为必选（课件"验收 harness"硬要求）。测试任务先于或伴随对应实现任务；方法名英文驼峰、课件中文原文进 `@DisplayName`。

**Organization**: 按 User Story 分组。US4 的机制本体（`AnnotatedToolAdapter`）与 US1 同批落地（`ToolRegistry.registerAnnotated` 编译耦合，见 T005 注）；US3 的 `McpToolAdapter` 先行落地（T006，ToolRegistryTest 三来源用例的编译依赖）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1~US6（Setup / Foundational / 装配收口 / Polish 无标签）
- 所有路径相对仓库根

## Path Conventions

主战场 `sableos-tool/`（Maven 多模块单仓）；装配改动在 `sableos-boot/`；CLI 清单在 `sableos-cli/`；配置示例在 `config/`。

---

## Phase 1: Setup

**Purpose**: 依赖与配置骨架（对齐 research.md D-12）

- [X] T001 `sableos-tool/pom.xml` 新增三个依赖（**无版本号，BOM 管理**）：`org.springframework.ai:spring-ai-model`、`io.modelcontextprotocol.sdk:mcp`、`org.yaml:snakeyaml`；跑 `mvn -B -pl sableos-tool dependency:tree` 核实三件可解析（附录 A-1 已预核）
- [X] T002 [P] 新增 `config/mcp_servers.yaml.example`：`servers:` 列表 + `name/transport/command/env` 四字段示例与注释（运行时读取 `.sableos/mcp_servers.yaml`）；不新增配置键（对齐 oryxos 先例）

---

## Phase 2: Foundational

**Purpose**: Sandbox 前向接口五件——所有涉外工具构造强制要求校验器，阻塞全部工具

**⚠️ CRITICAL**: 本阶段完成前任何工具不可实现

- [X] T003 新增 `sableos-tool/src/main/java/com/sableos/tool/sandbox/` 五件：`Sandbox`（`enforce(SandboxAction)`）、`SandboxAction`（`record(ActionType, String)`）、`ActionType`（**四值定死**：`FILE_READ`/`FILE_WRITE`/`SHELL_COMMAND`/`HTTP_REQUEST`，24 节沿用）、`SandboxViolationException`（RuntimeException）、`PermissiveSandbox`（临时装配：放行必 WARN 留痕、日志 CRLF 净化 `replaceAll("[\\r\\n]", "_")` 形态勿改）——契约 [contracts/sandbox-forward.md](./contracts/sandbox-forward.md) S1~S7

**Checkpoint**: `mvn -B -pl sableos-tool test-compile` 通过

---

## Phase 3: User Story 1 - 统一抽象与注册表 (Priority: P1) 🎯 MVP 地基

**Goal**: 三种来源的工具以统一抽象注册进唯一注册表；按 Profile `tools` 过滤"不多不少"；装配后 chat 链路取到注册表内容。
**Independent Test**: 注册三来源 → `contains`/`all` 断言；重复注册拒绝；过滤精确等于声明列表。

### Tests for User Story 1（harness 先行：本任务先写、随 T005~T007 落地转绿）

- [X] T004 [US1] 新增 `sableos-tool/src/test/java/com/sableos/tool/ToolRegistryTest.java`，5 用例：
  - `allThreeSourcesRegisterAsSableTool`：直接实现替身 + `@Tool` 注解 bean + `new McpToolAdapter(mock(McpSyncClient.class), 夹具 Tool)` 三件均 `contains`、`all().size()==3`
  - `duplicateNameIsRejected`：`assertThrows(IllegalStateException)` 且消息含工具名
  - `filterByProfileToolsIsExact`：声明 `[read_file, http_get, not_registered]` → 结果恰 `[read_file, http_get]`、顺序随声明
  - `annotatedToolForwardsJsonArgumentsToMethod`（[US4]）：注解 bean 经管道注册，`execute(JSON 实参)` 断言方法真被调、返回包成 success
  - `annotatedToolExceptionPropagates`（[US4]）：方法抛异常 → execute 上抛**不吞**（执行层收敛的前提；`@DisplayName` 对 课件/US4.2）

### Implementation

- [X] T005 [US1] 新增 `sableos-tool/src/main/java/com/sableos/tool/ToolRegistry.java`——`implements com.sableos.core.tool.ToolCatalog`；`register(SableTool)` / `registerAnnotated(Object)` / `contains(String)` / `all()`（只读快照）/ `filterByNames(List<String>)` + 继承 `toolsFor/find`；**重名 → `IllegalStateException("工具重名，拒绝注册: " + name)`**；`filterByNames` 精确子集：未知名跳过不报错、顺序随声明；`toolsFor(profile)` 空声明得空结果；`find` 未命中返 `null`——契约 C1~C5
- [X] T006 [US4] 新增 `sableos-tool/src/main/java/com/sableos/tool/AnnotatedToolAdapter.java`——`implements SableTool`，构造接 `ToolCallback`；三要素直读 `callback.getToolDefinition()`；`execute`：`callback.call(input == null ? "{}" : input.toString())` → `ToolResult.success(output)`；**异常不捕获**（管道不吞、执行层统一收敛）——契约 C6；**注**：与 T005 同批落地（`registerAnnotated` 编译耦合）
- [X] T007 [US3] 新增 `sableos-tool/src/main/java/com/sableos/tool/mcp/McpToolAdapter.java`——`implements SableTool`，构造 `(McpSyncClient, McpSchema.Tool)`；getName/getDescription/getInputSchema 直映射（schema `MAPPER.writeValueAsString(tool.inputSchema())`，失败 `IllegalStateException` 点名）；`execute`：`JsonNode` → `Map`（`convertValue`）→ `client.callTool(new McpSchema.CallToolRequest(tool.name(), args))` 参数原样转发；`isError=true` → `ToolResult.failure("MCP 调用失败: " + content, true)` **可重试**；不含 64KB 截断（后节）——契约 M5~M7；**注**：US3 交付物先行落地（T004 三来源用例 MCP 支路的编译依赖）

**Checkpoint**: `mvn -B -pl sableos-tool test -Dtest=ToolRegistryTest` 全绿

---

## Phase 4: User Story 2 - 六个内置工具（能干活且越不了界）(Priority: P1)

**Goal**: read_file / write_file / list_dir / shell / http_get / http_post 交付：合法输入跑通；执行第一步过安全校验、校验不过动作零发生。
**Independent Test**: 每工具"正常 + 拦截"两条（拒绝型 Sandbox 替身）。

### Tests for User Story 2（先写）

- [X] T008 [P] [US2] 新增 `sableos-tool/src/test/java/com/sableos/tool/builtin/FileToolsTest.java`（`@TempDir`）——契约逐条：`readFile_returnsFileContent` / `writeFile_writesContent` / `listDir_listsSortedNames` / `readFile_outsideWhitelistIsBlocked`（拒绝型 Sandbox + 断言文件未被碰）/ `editFile_replacesUniqueOccurrence` / `editFile_oldStringNotFoundFailsWithoutWriting` / `editFile_multipleOccurrencesFailWithoutWriting` / `grep_returnsPathLineContent` / `grep_truncatesAtTwoHundredWithNote` / `grep_skipsBinaryAndNonUtf8Files` / `glob_matchesRecursivePattern` / `glob_truncatesAtTwoHundredWithNote`
- [X] T009 [P] [US2] 新增 `sableos-tool/src/test/java/com/sableos/tool/builtin/ShellToolsTest.java`（OS 分支夹具按 `os.name`：Windows `cmd /c echo hi`、`cmd /c exit 3`、`ping -n 5 127.0.0.1`；Linux `echo`、`sh -c "exit 3"`、`sleep 5`）——`shell_echoReturnsStdout` / `shell_outsideWhitelistIsBlocked` / `shell_nonZeroExitCarriesStderr` / `shell_timeoutKillsProcess`（注入短超时）
- [X] T010 [P] [US2] 新增 `sableos-tool/src/test/java/com/sableos/tool/builtin/HttpToolsTest.java`（JDK `HttpServer` 随机端口，零出网）——`httpGet_returnsResponseBody`（课件 `http_get_应能取回响应`）/ `httpGet_domainOutsideWhitelistIsBlocked`（课件 `http_get_命中白名单外域名应被拦下`，含假服务零请求断言）/ `httpPost_returnsResponseBody` / `httpGet_nonSuccessStatusCarriesStatusCode`（非 2xx 异常含状态码）

### Implementation for User Story 2

- [X] T011 [P] [US2] 新增 `sableos-tool/src/main/java/com/sableos/tool/builtin/FileTools.java`——构造 `FileTools(Sandbox)`；6 个 `@Tool` 方法逐字（name/description/@ToolParam 文案照 [contracts/builtin-tools.md](./contracts/builtin-tools.md)）：`read_file`（非文件点名 IAE）/ `write_file`（**不自动建父目录**）/ `list_dir`（排序 `\n` 连接）/ `edit_file`（`indexOf` 唯一性检查先行，找不到/多处 → IAE 且文件零字节改动）/ `grep`（`"文件:行号:内容"`、**200 条上限截断注明**、二进制/非 UTF-8 跳过不中断）/ `glob`（`**/` 根下单段相对路径补匹配、200 条上限）；**每个方法第一行 `enforce`**
- [X] T012 [P] [US2] 新增 `sableos-tool/src/main/java/com/sableos/tool/builtin/ShellTools.java`——构造 `ShellTools(Sandbox)` + package-private 超时注入；`shell(executable, List<String> arguments)` 首行 `enforce(SHELL_COMMAND, executable)`；`ProcessBuilder` **不经 Shell 解释**、输出按 `Charset.defaultCharset()` 解码；默认 **30s 超时 → 杀进程树**（`descendants().destroyForcibly` + 自身）报"命令超时"；非零退出 `IllegalStateException("命令退出码 N: <stderr>")`；stdout/stderr 虚拟线程并发排空
- [X] T013 [P] [US2] 新增 `sableos-tool/src/main/java/com/sableos/tool/builtin/HttpTools.java`——构造 `HttpTools(Sandbox, RestClient)`；`http_get`/`http_post` 首行 `enforce(HTTP_REQUEST, url)`；`retrieve().body(String.class)`；非 2xx **不特判不自建错误页**（RestClient 默认异常含状态码 → 执行层收敛）

**Checkpoint**: `mvn -B -pl sableos-tool test -Dtest='FileToolsTest,ShellToolsTest,HttpToolsTest'` 全绿

---

## Phase 5: User Story 3 - MCP 接入（失联不拖垮）(Priority: P2)

**Goal**: 按 `.sableos/mcp_servers.yaml` 连接外部 MCP server；tools/list 包装注册；失联只 WARN；配置缺失按零 server。
**Independent Test**: mock `McpSyncClient`——好 server 注册、坏 server 不注册且不抛；配置逐项解析。

### Tests for User Story 3（先写；T006 已落地 McpToolAdapter）

- [X] T014 [P] [US3] 新增 `sableos-tool/src/test/java/com/sableos/tool/mcp/McpConfigLoaderTest.java`——`missingFileYieldsZeroServers` / `parsesAllFieldsPerEntry`（含 env 缺省→空 Map）/ `malformedFileFailsLoud`（非列表/条目非对象 → IAE 点名）
- [X] T015 [P] [US3] 新增 `sableos-tool/src/test/java/com/sableos/tool/mcp/McpToolAdapterTest.java`——`contractMapsFromMcpToolSpec`（三要素直映射含 schema 序列化）/ `executeForwardsArgsAndWrapsResult`（ArgumentCaptor：name 与 arguments 与输入**逐字相等**；成功包装）/ `mcpErrorWrapsAsRetryableFailure`（`isError=true` → `success=false` + `retryable=true` + 消息含文本）
- [X] T016 [P] [US3] 新增 `sableos-tool/src/test/java/com/sableos/tool/mcp/McpClientServiceTest.java`——`goodServerRegisters_badServerOnlyWarns`（课件骨架逐条对齐：好 server `listTools` 返回 `[good_mcp_tool]`、坏 server 抛 `RuntimeException("refused")` → `connectAll` **不抛**；`contains("good_mcp_tool")` 真、`contains("bad_mcp_tool")` 假）；夹具 `McpSchema.Tool.builder().name().description().inputSchema(McJsonDefaults.getMapper(), "{}").build()`

### Implementation for User Story 3

- [X] T017 [P] [US3] 新增 `sableos-tool/src/main/java/com/sableos/tool/mcp/McpServerConfig.java`——`record McpServerConfig(String name, String transport, String command, Map<String, String> env)`；env 缺省空 Map
- [X] T018 [US3] 新增 `sableos-tool/src/main/java/com/sableos/tool/mcp/McpConfigLoader.java`——snakeyaml 读 `.sableos/mcp_servers.yaml`（构造传 `Path`）；**`servers:` 顶层键**列表；文件缺失/键缺失 → `List.of()` 不报错；YAML 解析失败 / `servers` 非列表 / 条目非对象 → `IllegalArgumentException` **点名报错**；`command` 原样存（空白拆分归 service）
- [X] T019 [US3] 新增 `sableos-tool/src/main/java/com/sableos/tool/mcp/McpClientService.java`——构造 `(McpConfigLoader)` + 注入版 `(McpConfigLoader, Function<McpServerConfig, McpSyncClient>)`；`connectAll(ToolRegistry)`：逐 config `factory → initialize → listTools → 逐个 McpToolAdapter 注册`；transport 非 `stdio` → WARN 跳过；**任何 RuntimeException → WARN("MCP server {name} 连接失败，跳过它的工具: {msg}") 后继续，永不抛出**；默认工厂 `StdioClientTransport(ServerParameters.builder(parts[0]).args(...).env(...).build(), McpJsonDefaults.getMapper())` + `McpClient.sync(...)`——契约 M1~M4

**Checkpoint**: `mvn -B -pl sableos-tool test -Dtest='McpConfigLoaderTest,McpToolAdapterTest,McpClientServiceTest'` 全绿

---

## Phase 6: User Story 5 - 五个扩展基础工具（对齐业界水准）(Priority: P2)

**Goal**: edit_file/grep/glob（已随 FileTools）+ ask_user + web_search；全部复用既有抽象、校验位与注解管道。
**Independent Test**: ask_user 无人值守报错；web_search 白名单先行零请求；edit_file/grep/glob 边界语义（已入 Phase 4）。

### Implementation（接口先行件）

- [X] T020 [P] [US5] 新增 `sableos-tool/src/main/java/com/sableos/tool/interaction/` 三件：`UserInteraction`（接口 `String ask(String question)`）、`ConsoleUserInteraction`（打印+读一行；EOF → `IllegalStateException`；IO → `UncheckedIOException`）、`UnsupportedUserInteraction`（`UnsupportedOperationException("当前渠道不支持向用户提问（无人值守渠道）")`）——**零新对外异常类型**（软门禁 #1，不用 oryxos 的 InteractionUnavailableException）
- [X] T021 [P] [US5] 新增 `sableos-tool/src/main/java/com/sableos/tool/web/SearchProvider.java`——接口 `List<SearchResult> search(String query)` + 内嵌 `record SearchResult(String title, String url, String snippet)`
- [X] T022 [P] [US5] 新增 `sableos-tool/src/main/java/com/sableos/tool/web/DuckDuckGoSearchProvider.java`——Instant Answer 免 key（`format=json&no_html=1`）；**`search()` 第一行 `enforce(HTTP_REQUEST, <真实请求 URL>)`**（治理单点，工具层零伪目标——D-08）；解析 `AbstractText/AbstractURL/Heading` + `RelatedTopics[].Text/FirstURL` → `SearchResult`；package-private 构造可注入 endpoint（测试指向本地 server）

### Tests for User Story 5（先写）

- [X] T023 [P] [US5] 新增 `sableos-tool/src/test/java/com/sableos/tool/builtin/InteractionToolsTest.java`——`askUser_returnsConsoleAnswer`（注入流构造）/ `askUser_unattendedChannelFailsLoud`（`UnsupportedOperationException` + 消息含"不支持"）
- [X] T024 [P] [US5] 新增 `sableos-tool/src/test/java/com/sableos/tool/builtin/WebSearchToolsTest.java`——`webSearch_rendersProviderResults`（假 provider）/ `webSearch_whitelistCheckedBeforeAnyRequest`（真 DDG 引擎 + 拒绝型 Sandbox + mock RestClient `verifyNoInteractions`）/ `duckDuckGoSearch_parsesInstantAnswerJson`（JDK `HttpServer` 夹具）

### Implementation（工具件）

- [X] T025 [P] [US5] 新增 `sableos-tool/src/main/java/com/sableos/tool/builtin/InteractionTools.java`——构造 `InteractionTools(UserInteraction)`；`@Tool(name="ask_user", description="向当前用户提出一个问题并等待其回答（用于澄清需求或确认危险操作）")`；只管递问题带回答回来，不吞异常、不设校验位
- [X] T026 [P] [US5] 新增 `sableos-tool/src/main/java/com/sableos/tool/builtin/WebSearchTools.java`——构造 `WebSearchTools(SearchProvider)`（**无 Sandbox**——enforce 在引擎入口）；`@Tool(name="web_search", description="用搜索引擎检索网页，返回标题、链接和摘要列表")`；渲染每条 `- 标题\n  链接\n  摘要`；空结果提示文案

**Checkpoint**: `mvn -B -pl sableos-tool test -Dtest='InteractionToolsTest,WebSearchToolsTest'` 全绿

---

## Phase 7: User Story 6 - notify 完成注册（19 节接线兑现）(Priority: P3)

**Goal**: `NotifyTools` 以统一抽象进注册表（本节装配空表）；渠道解析严沿 19 节登记契约。
**Independent Test**: 构造含渠道/空渠道来源断言四态 + `InOrder`（**完整 harness 24 节后补跑，本节只登记**）。

### Implementation

- [X] T027 [US6] 新增 `sableos-tool/src/main/java/com/sableos/tool/builtin/NotifyTools.java`——`implements SableTool`（直实现来源）；构造 `(Sandbox, NotifyChannelAdapter, Map<String, NotifyTarget> channels)`，channels 经 `Collections.unmodifiableMap(new LinkedHashMap<>(channels))` **保序**（不用 `Map.copyOf`）；执行顺序即安全不变量：①content 缺失/空白 → `failure("notify 缺少必填参数 content", false)` ②`ProfileContext.current()==null` → failure 点名零请求 ③渠道解析（缺省/空白/`default` → **第一个**；非空按名、未命中**不回退**、空表点名"无已注册渠道"）④`enforce(HTTP_REQUEST, target.config().get("url"))` ⑤`adapter.send`（失败上抛——19 节口径）⑥`success("已推送")`；入参 schema content 必传、channel 可选；**不建审计旁路**（走 ToolExecutor 既有路径）——契约 [contracts/notify-tool.md](./contracts/notify-tool.md)
- [X] T028 [US6] **登记核对（不落码）**：确认 `contracts/notify-tool.md` 三测试方法名（`missingChannelConfigFailsLoud` / `channelDefaultsToFirstRegistered` / `enforceIsCalledBeforeSend`）与 24 节后补跑口径在位；`NotifyToolsTest.java` **不建文件**

**Checkpoint**: `mvn -B -pl sableos-tool test -Dtest=ToolRegistryTest` 仍全绿（NotifyTools 可被注册）

---

## Phase 8: 装配收口（跨故事，FR-007 / SC-006）

**Goal**: 注册表替换空工具表接通 17 节执行器与 18 节 chat；CLI 静态清单刷新；契约与装配级测试。
**Independent Test**: `ReactAssemblyTest` 覆盖装配断言；`SableToolContractTest` 遍历全工具。

- [X] T029 [US1] 新增 `sableos-boot/src/main/java/com/sableos/boot/tool/ToolConfiguration.java`——`@Bean`：`Sandbox`（`PermissiveSandbox`）+ `RestClient`（`RestClient.builder().build()`，provider 自建先例）+ `ToolRegistry`（同时作为 `ToolCatalog`）+ 内置五 bean `registerAnnotated`（FileTools/ShellTools/HttpTools/InteractionTools/WebSearchTools；`UserInteraction` **按运行模式条件装配**：`resolveUserInteraction(ctx instanceof WebApplicationContext)`——chat(`WebApplicationType.NONE`)→`ConsoleUserInteraction`、serve/gateway(Servlet web)→`UnsupportedUserInteraction`，对齐 oryxos `OryxOsRuntime.resolveUserInteraction` 先例）+ `new NotifyTools(sandbox, new WebhookNotifyAdapter(restClient), Map.of())` register（**空表**）+ `McpClientService` bean（`McpConfigLoader(Path.of(".sableos/mcp_servers.yaml"))`）+ `mcpClientService.connectAll(registry)`——对齐 D-09
- [X] T030 [US1] 修改 `sableos-boot/src/main/java/com/sableos/boot/react/ReactConfiguration.java`——删除嵌套 `EmptyToolCatalog` 类及其 `@Bean`（目录 bean 由 ToolConfiguration 的 ToolRegistry 提供）；其余定义不动
- [X] T031 [US1] 修改 `sableos-boot/src/test/java/com/sableos/boot/ReactAssemblyTest.java`——装配升级同步：`find("http_get")` 断言由 null 改**非 null**；失败审计用例换**真未知名工具**（如 `weather_query`）；新增一条注册工具成功调用端到端（含 `tool_invocations` 成功审计）+ `toolsFor(空 profile)` 为空断言
- [X] T032 [US1] 新增 `sableos-tool/src/test/java/com/sableos/tool/SableToolContractTest.java`——`@ParameterizedTest @MethodSource("allRegisteredTools")`：name/description/inputSchema 三件套全非空；**MethodSource 按运行时装配同构**构造（内置五 bean + NotifyTools 空表 + mock 依赖全注册后 `registry.all().stream()`）；课件中文 `@DisplayName` 原文保真（"每个工具的契约三件套都不能缺"）
- [X] T033 [US1] 修改 `sableos-cli/src/main/java/com/sableos/cli/command/ToolListCommand.java`——刷新为**静态 12 工具清单**（read_file/write_file/list_dir/shell/http_get/http_post/edit_file/grep/glob/ask_user/web_search/notify 各一行简述）+ 如实标注"运行期实时清单（含 MCP 连接结果）归 26 节 Web 管理台；轻命令不启动 Spring"；零 Spring、秒回

**Checkpoint**: `mvn -B -pl sableos-boot test -Dtest=ReactAssemblyTest` + `mvn -B -pl sableos-tool test -Dtest=SableToolContractTest` 全绿

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T034 `mvn spotless:apply` → `mvn clean verify` **全绿**（含 Spotless/P3C/Checkstyle/SpotBugs+FindSecBugs/PMD 与**前序节全部测试回归**）
- [X] T035 H4 全局不变量 grep 级自查（quickstart.md §5 表逐条：涉外首行 enforce / 审计无旁路 / 无明文 key / 无 Reactor/CompletableFuture/自建线程池（仅 shell 虚拟线程执行器）/ 无 Spring AI 自动执行（仅 registerAnnotated 与 AnnotatedToolAdapter 两处）/ CRLF 净化形态 / session_id 无散落）+ **零新对外概念**对照 spec.md 交付物清单逐类型核对
- [X] T036 quickstart.md 可执行部分核对（§1~§4 命令逐条跑）+ 人工项清单（§6）移交 Step 7 验收报告；**不自动 commit/push**

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Setup——**阻塞全部工具**（构造强制要求 Sandbox）
- **Phase 3 (US1+US4机制+US3适配器前置)**: 依赖 Phase 2；测试 T004 先写，T006 与 T005 同批（编译耦合）、T007 先行（T004 三来源用例编译依赖）
- **Phase 4 (US2)**: 依赖 Phase 2（Sandbox）；测试先写、实现随即转绿
- **Phase 5 (US3)**: T006 已在 Phase 3 落地；其余依赖 Setup（mcp 依赖）+ Phase 2
- **Phase 6 (US5)**: 依赖 Phase 2；`edit_file/grep/glob` 含在 T011（FileTools）
- **Phase 7 (US6)**: 依赖 Phase 2（Sandbox）+ 19 节既有 `notify` 包（已在位，A-2 已核）
- **Phase 8 (装配收口)**: 依赖 Phase 3~7 全部落地（组装全工具）
- **Phase 9 (Polish)**: 依赖全部

### 关键跨阶段依赖（编译序）

- T006 `AnnotatedToolAdapter` → T005 `registerAnnotated`（同批）
- T007 `McpToolAdapter` → T004 三来源用例 MCP 支路（前置）
- T011/T012/T013/T025/T026/T027 → T029 装配（全工具到齐）
- T029 → T031 ReactAssemblyTest / T032 SableToolContractTest（装配同构）

### Parallel Opportunities

- T002 独立于 T001
- T008/T009/T010 三份测试文件互不冲突；T011/T012/T013 三份实现互不冲突
- T014/T015/T016 互不冲突；T020/T021/T022 互不冲突；T023/T024 互不冲突；T025/T026 互不冲突
- 单人执行时按 T 编号顺序逐任务落地（harness 先于/伴随实现）

---

## Implementation Strategy

### 顺序交付（单开发者，本课口径）

1. Phase 1~2 → Sandbox 与依赖就位
2. Phase 3 → 注册表 + 管道 + 适配器（MVP 地基，ToolRegistryTest 绿）
3. Phase 4~7 → 逐故事落地工具与测试，每 Phase checkpoint 绿后再进下一
4. Phase 8 → 装配收口（ReactAssemblyTest + SableToolContractTest + CLI）
5. Phase 9 → 全量门禁 + H4 自查，收尾报告

### Notes

- 测试方法名英文驼峰；课件中文原文（`http_get_应能取回响应` 等）进 `@DisplayName`
- 实现错修实现；不得删断言 / `@Disabled` / 放宽阈值
- 全绿前不得宣称完成；`mvn clean verify` 是实现的完成定义
- 不自动 commit / push / 跑 package.sh
