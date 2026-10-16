# Research: 第 20 节 Tool 体系——Phase 0 决策与核实证据

**Feature**: `005-lesson20-tool` | **Date**: 2026-10-16 | **Spec**: [spec.md](./spec.md)

> 全部落 `sableos-tool`（唯一改动主模块）+ `sableos-boot`（装配点）+ `sableos-cli`（tool list 静态清单刷新）+ `sableos-boot` 测试；零新模块、零迁移、零新第三方（三个新依赖均为 BOM 已管理构件）。决策编号 D-01~D-12，核实证据见附录 A。

## D-01 注册表：`ToolRegistry implements ToolCatalog`，落 `com.sableos.tool`

- **Decision**: `ToolRegistry` 实现 17 节 core 的 `ToolCatalog` 契约（`List<SableTool> toolsFor(Profile)` / `SableTool find(String)`），落 `com.sableos.tool`（模块根包）。两条注册路径：`register(SableTool)`（直接实现来源——NotifyTools / MCP 适配）与 `registerAnnotated(Object)`（`ToolCallbacks.from(bean)` 逐回调包装 `AnnotatedToolAdapter` 后注册——内置工具与方式三共用）。重名注册 `IllegalStateException("工具重名，拒绝注册: " + name)`；前缀不再追加各来源子路径（如 `registerMcpTool`）——MCP owner 追踪是 oryxos 后节为 `mcp_servers` 白名单加的能力，非本节交付物。`hasTools()/all()` 供契约测试 MethodSource 与 `contains(name)` 供失联隔离断言。
- **Rationale**: 课件"ReAct 循环只跟 `SableTool` 打交道"；`ToolCatalog` 是 17 节就位的目录契约，装配只需换实现（`EmptyToolCatalog → ToolRegistry`），`PromptBuilder`（[PromptBuilder.java:80](../../sableos-core/src/main/java/com/sableos/core/prompt/PromptBuilder.java#L80) 调 `toolCatalog.toolsFor(profile)`）与 `ToolExecutor` 零改动。方法签名对齐 oryxos 已核实版（`register/registerAnnotated/contains/all/filterByNames`）。
- **Alternatives considered**: 新造 `ToolCatalog` 接口 / 改 core 契约 → 违"前序公共接口不动"；注册表放 core → 违技术方案 §10 模块分工（注册表归 sableos-tool）。
- **实现细节**: 内部 `ConcurrentHashMap`；`filterByNames` 遍历声明列表取注册表命中项（"不多不少"，未知名跳过）；`toolsFor(profile)` = `filterByNames(profile.tools())`；`find` 未命中返回 null（core 契约）。

## D-02 Sandbox 前向五件：落 `com.sableos.tool.sandbox`

- **Decision**: 五件一次性交付，签名对齐 oryxos 已核实版：`Sandbox`（`void enforce(SandboxAction)`）、`SandboxAction`（`record(ActionType type, String target)`）、`SandboxViolationException extends RuntimeException`、`PermissiveSandbox`（实现 `Sandbox`：放行一切但逐次 `LOG.warn` 留痕）、`ActionType` **四值定死**：`FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST`（本节定死、24 节 WhitelistSandbox 沿用；oryxos 后期的 `HTTP_READ/SMTP_SEND` 是后节演进，不在本节范围）。
- **Rationale**: 课件明文"先以接口调用形式接入，Sandbox 本体 23/24 节交付"；24 节课件已把 `ActionType` 四值写死（技术方案 §6.7 同口径）；`PermissiveSandbox` 是本节可运行性的临时装配（24 节替换为白名单实现）。
- **Alternatives considered**: 只交接口不交装配实现 → 本节装配无法起（工具构造强制要求校验器，不允"没有校验器就裸奔"）；`PermissiveSandbox` 静默放行 → 违"绝不静默裸奔"。
- **实现细节**: `PermissiveSandbox.enforce` 的 WARN 日志中 target 为用户可控字符串，入日志前 `replaceAll("[\\r\\n]", "_")` 净化（AGENTS.md §5.2 / findsecbugs 只认该形态）；`SandboxAction` record 不追加防御拷贝（target 为工具侧构造的字符串）。

## D-03 内置三件套：FileTools / ShellTools / HttpTools 走 `@Tool` 注解管道，落 `com.sableos.tool.builtin`

- **Decision**: 六个内置工具（read_file / write_file / list_dir / shell / http_get / http_post）以 `@Tool` 注解方法形态交付（类：`FileTools`、`ShellTools`、`HttpTools`），装配处经 `registerAnnotated` 注册——**schema 由 Spring AI 自动生成**，与业务方方式三共用同一条管道（FR-004）。每个涉外方法**第一行** `sandbox.enforce(new SandboxAction(ActionType.X, target))`。构造注入 `Sandbox`，测试可直接 `new`（纯 POJO，零 Spring 注解）。
- **Rationale**: 课件 §三"内置 Tool 和方式三（Java @Tool）：用 Spring AI 的 `@Tool` 注解标在 Java 方法上……包装成 `SableTool`"（line 63）+ 课件 `http_get` 示例（line 104-108）逐字即注解方法 + line 266"装配处多 registerAnnotated 两行"，三处互证。oryxos 同名产物已核实为同构（`@Tool` 方法 + 首行 enforce）。
- **Alternatives considered**: 手写 JSON schema 直实现 `SableTool` → 与课件三处明文冲突，且违 FR-004"共用同一条管道"。
- **逐工具语义**（与 spec 边界逐条对齐）:
  - `read_file`：enforce(FILE_READ, path) → 非普通文件 `IllegalArgumentException` 点名 → `Files.readString`。
  - `write_file`：enforce(FILE_WRITE, path) → `Files.writeString`；**不**自动 `createDirectories`（spec 边界：目标目录不存在=失败结果点名，"不擅自递归建目录"——与 oryxos 不同，本仓口径优先）。
  - `list_dir`：enforce(FILE_READ, path) → 非目录点名 → `Files.list` 排序输出。
  - `shell`：enforce(SHELL_COMMAND, executable) → `ProcessBuilder(executable, args...)` **不经 Shell 解释**；超时 30s 常量（package-private 构造可注入）、超时递归杀进程树（`descendants().destroyForcibly` + 自身）报失败；非零退出码 `IllegalStateException` 携带 stderr；stdout/stderr 虚拟线程并发排空（防管道写满被误判超时）；输出按 `Charset.defaultCharset()` 解码（Windows GBK 先例）。输出上限/深浅杀等 oryxos 后期特性不属本节。
  - `http_get / http_post`：enforce(HTTP_REQUEST, url) → `RestClient` 收发（`http_post` 以 `application/json` 提交 body）。**非 2xx 不特判、不自建错误页**：RestClient 默认抛含状态码异常，经 `ToolExecutor` 既有收敛路径成为失败结果（模型可自救）——与 19 节 notify 适配器"失败上抛"并存，两条口径最终同归"执行层包结果、不向循环抛"（ToolExecutor javadoc 已定）。
- **harness 模板保真**: 课件 `http_get_应能取回响应` / `http_get_命中白名单外域名应被拦下` 两条逐条落地（方法名译英文 + `@DisplayName` 保留课件原文）；越界被拦 = 拒绝型 Sandbox 替身 + `assertThrows`（课件模板同款）。

## D-04 MCP 四件：落 `com.sableos.tool.mcp`

- **Decision**: 四件：`McpServerConfig`（`record(name, transport, command, Map<String,String> env)`——课件四字段；不做 url/headers/requestTimeout，那是 oryxos 后节为远程 SSE 与管理台加的）、`McpConfigLoader`（snakeyaml；读 `.sableos/mcp_servers.yaml`）、`McpClientService`（连接维护 + `connectAll(ToolRegistry)`）、`McpToolAdapter implements SableTool`（协议转发 + 结果包装）。
- **McpConfigLoader**: 顶层 `servers:` 键 → 列表（对齐 oryxos 已核实的格式推定；`servers` 键缺失/null → 零 server）；文件缺失 → `List.of()` 启动照常；YAML 解析失败 / `servers` 非列表 / 条目非对象 → `IllegalArgumentException` 点名（不静默按零 server 处理）。`command` 字符串按空白拆分为可执行 + args（对齐 oryxos）。
- **McpClientService**: 构造注入 `Function<McpServerConfig, McpSyncClient>` 连接工厂（生产默认 `stdio` 档：`McpClient.sync(new StdioClientTransport(ServerParameters.builder(cmd).args(...).env(...).build(), McpJsonDefaults.getMapper())).build()`；测试替换 mock 工厂，**不真起子进程、不碰网**）。`connectAll` 对每份 config：连接 → `initialize()` → `listTools()` → 逐个 `McpToolAdapter` 注册；**任何 RuntimeException 只 WARN 跳过该 server**，不拖垮启动（课件守点 + SC-003）。transport 非 `stdio` → WARN 跳过（async 档演进不在本节）。
- **McpToolAdapter**: `getName/getDescription` 直映射 `McpSchema.Tool`；`getInputSchema()` = `MAPPER.writeValueAsString(tool.inputSchema())`（对齐 oryxos 已核实实现）；`execute(JsonNode)`：`MAPPER.convertValue(input, Map)` 原样转发 `client.callTool(new McpSchema.CallToolRequest(name, args))`；`TextContent` 拼接；`isError=true` → `ToolResult.failure("MCP 调用失败: " + content, true)`（可重试）；成功 → `ToolResult.success(content)`。**不含** oryxos 后期 64KB 截断（课件最小骨架 + harness 未点名，属后节演进）。
- **Rationale**: 课件"31 节日报 Agent 硬依赖，不能只停在概念"；失联隔离 = 课件 harness 最值钱两条之一。
- **Alternatives considered**: 手工 YAML 解析 → 违"零新第三方优先用 BOM 构件"且 snakeyaml 已由 Boot 管理；真起子进程测 → 不可重复、CI 不准。

## D-05 注解管道机制本体：`AnnotatedToolAdapter`，落 `com.sableos.tool`

- **Decision**: `public class AnnotatedToolAdapter implements SableTool`，构造接 `org.springframework.ai.tool.ToolCallback`；三要素读 `callback.getToolDefinition()`；`execute(JsonNode)` = `callback.call(input == null ? "{}" : input.toString())` → `ToolResult.success(output)`；**异常不捕获**（"管道自身不吞异常"，由 17 节 `ToolExecutor` 统一收敛为失败结果并审计）。
- **Rationale**: 课件"只借 Spring AI 的 schema 生成与反射调用，不引入框架自动执行路径"（宪法原则 III）；oryxos 同构已核实。
- **Alternatives considered**: 在本类内 try/catch 转失败结果 → 违 FR"方法抛异常时由执行层统一收敛"（US4），且会让 `ToolExecutor` 的异常路径覆盖不到。

## D-06 NotifyTools：直实现 `SableTool` + 有序 Map 最小组合（clarify #1 拍板）

- **Decision**: `public class NotifyTools implements SableTool`（**直接实现**来源，非注解管道——19 节登记契约同步口径），落 `com.sableos.tool.builtin`。构造：`NotifyTools(Sandbox sandbox, NotifyChannelAdapter adapter, Map<String, NotifyTarget> channels)`；channels 以 `Collections.unmodifiableMap(new LinkedHashMap<>(channels))` 保序（**不用 `Map.copyOf`**——不保序，与"取第一个渠道"语义冲突）。工具名 `notify`，手写 JSON schema（`content` 必传、`channel` 可选）。
- **解析契约（严沿 19 节登记）**: 顺序为 ①`content` 缺失/空白 → 失败点名；②`ProfileContext.current()` 为 null → 失败"当前无 Agent 上下文"（该类仅静态方法，无构造注入——19 契约表述的落地形态）；③channel 缺省/空白/字面量 `default`（哨兵优先于同名匹配）→ 取 Map 第一个；Map 空 → 失败"无已注册渠道"；显式名字未命中 → 失败点名、**不回退**；④`sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target.config().get("url")))` → ⑤`adapter.send(target, content)`（发送失败由适配器上抛）→ `ToolResult.success("已推送")`。
- **Rationale**: clarify #1 拍板（零新对外类型、不触软门禁 #1）；19 节 `contracts/notify-tool.md` 四态 + InOrder 口径逐条兑现；本节装配空表（报错点名、零请求），注册表本体（SQLite+CRUD）留后续节、届时不改本类。
- **Alternatives considered**: 新造 `NotifyChannelRegistry` 接口 → 触软门禁 #1（拍板否决）；按 type 字段路由（oryxos 终态）→ 本仓渠道模型为"按名引用"（技术方案 §6.8），名→目标 Map 更直白。

## D-07 交互三件：`UserInteraction` + 两档实现 + `InteractionTools`，落 `com.sableos.tool.interaction`

- **Decision**: 接口 `UserInteraction { String ask(String question); }`（语汇零渠道特有词）；两档实现 `ConsoleUserInteraction`（打印问题到 out + `BufferedReader` 读一行；EOF → `IllegalStateException("未读取到用户回答（输入流已结束）")`；可注入 `(InputStream, PrintStream, Charset)` 构造供测试，默认构造走 `System.console()`→`Charset.defaultCharset()` 回退）+ `UnsupportedUserInteraction`（`UnsupportedOperationException("当前渠道不支持向用户提问（无人值守渠道）")`）；`InteractionTools`（`com.sableos.tool.builtin`）`@Tool(name="ask_user")` 方法只管递问题、带回答回来，不吞异常。
- **Rationale**: 课件 §6.3"接口先行、两档实现、拿不到回答就抛异常"；**不新造 `InteractionUnavailableException`**——课件交付物清单只点名 `InteractionTools` + `UserInteraction` 抽象与两档实现，异常类型未点名，用 JDK 标准异常（`UnsupportedOperationException`/`IllegalStateException`）零新对外概念（软门禁 #1 不触发）。
- **Alternatives considered**: 照抄 oryxos 的 `InteractionUnavailableException` → 新增交付物清单外 public 类型，触软门禁；测试断言以 `RuntimeException` + 消息点名"不支持交互"为准。

## D-08 搜索三件：`SearchProvider` + `DuckDuckGoSearchProvider` + `WebSearchTools`；enforce 落引擎入口真实 endpoint

- **Decision**: `SearchProvider`（`com.sableos.tool.web`）按课件形态：接口内嵌 `record SearchResult(String title, String url, String snippet)` + `List<SearchResult> search(String query)`；一档实现 `DuckDuckGoSearchProvider(Sandbox, RestClient)`（免 key Instant Answer API `https://api.duckduckgo.com/?q=…&format=json&no_html=1`，解析 `AbstractText/AbstractURL/Heading` + `RelatedTopics[].Text/FirstURL`）。**enforce 落引擎入口**：`search()` 第一行 `sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, <真实请求 URL>))`，通过后才发请求；package-private 构造可注入 endpoint（测试指向本地 JDK HttpServer）。`WebSearchTools(SearchProvider)`（`com.sableos.tool.builtin`）`@Tool(name="web_search")` 只渲染"标题/链接/摘要"文本；空结果给出提示文案。
- **Rationale（与 oryxos 的差异及原因）**: oryxos 在工具层用 `web_search:<query>` 伪目标 enforce + 引擎对真实 endpoint 复检——因为其 `HTTP_READ` 是"默认放行+SSRF 黑名单"语义，伪目标不破坏白名单解析。SableOS 的 `ActionType` **只有 HTTP_REQUEST（24 节按域名白名单）**：伪目标（无 host）会逼 24 节白名单特判伪目标，脏化解析且违"与 http_get 同口径被拦"。故本节单点 enforce 在唯一真实 IO 边界（引擎入口实 URL）——治理不被绕过（"引擎入口不绕过治理"），24 节白名单只需认识真实域名，换引擎时新实现按接口契约同款在入口 enforce。
- **Alternatives considered**: 工具层持 Sandbox + 伪目标（oryxos 终态）→ 与 SableOS 四值 `ActionType` 不兼容（上文）；工具层持 Sandbox 但无真实目标可用（接口不知道 endpoint）→ 只能伪目标，故排除。

## D-09 运行时装配：新 `ToolConfiguration`（boot）替换空工具表

- **Decision**: 新增 `com.sableos.boot.tool.ToolConfiguration`（仿 `ProviderConfiguration` 先例）：
  - `@Bean Sandbox` → `PermissiveSandbox()`（24 节替换为白名单实现）；
  - `@Bean ToolRegistry(ToolCatalog 型)`：`registerAnnotated` 五个内置工具 bean（`FileTools/ShellTools/HttpTools/InteractionTools/WebSearchTools`，各注入自己的依赖：Sandbox / Sandbox+RestClient / UserInteraction **按运行模式条件装配**（`resolveUserInteraction(ctx instanceof WebApplicationContext)`：chat(`WebApplicationType.NONE`)→`ConsoleUserInteraction`、serve/gateway(Servlet web)→`UnsupportedUserInteraction`——对齐 oryxos `OryxOsRuntime.resolveUserInteraction` 先例；serve/gateway 无人值守，ask_user 立刻报不支持、绝不堵在服务端 `System.in`）/ SearchProvider=DuckDuckGoSearchProvider）；`register(NotifyTools(sandbox, WebhookNotifyAdapter(RestClient), Map.of()))`（空表=零已注册渠道，报错点名、零请求）；末尾 `mcpClientService.connectAll(registry)`（缺 `mcp_servers.yaml` → 零 server，零副作用）；
  - `@Bean McpClientService`（`McpConfigLoader(Path.of(".sableos","mcp_servers.yaml"))`，生产默认工厂）；
  - `ToolCatalog` bean 从 `ReactConfiguration` 的 `EmptyToolCatalog` **删除**（连同嵌套类），改由本配置提供 `ToolRegistry`——`PromptBuilder/ToolExecutor` 注入点零改动（FR-007）。
- **Rationale**: 装配改动点在既有装配类（skill 模块落位句）；`EmptyToolCatalog` javadoc 自注"真实现归 20 节"，本节即兑现。
- **Alternatives considered**: 直接改 `ReactConfiguration` → 该配置只管 ReAct 链，工具装配独立成类更清（ProviderConfiguration/AgentConfiguration 同款）。

## D-10 `tool list`：保持轻命令 + 刷新静态内置清单（clarify #2 拍板）

- **Decision**: `ToolListCommand.ListCommand` 输出静态 12 工具清单（read_file/write_file/list_dir/shell/http_get/http_post/edit_file/grep/glob/ask_user/web_search/notify，各一行简述）+ 如实标注"运行期实时清单（含 MCP 连接结果）归 26 节 Web 管理台；轻命令不启动 Spring，故此处只列静态内置面"。
- **Rationale**: clarify #2（用户裁定按 oryxos 判断：oryxos 终态同为轻命令占位、其 20 节验收报告明载为可接受占位）；课件方式三人工项随此口径调整（经 chat 真链路验证）。
- **Alternatives considered**: 升级为重命令起 Spring 枚举 registry → 违"轻命令零 Spring 秒回"且登记归 26 节。

## D-11 测试策略：harness 映射 + 跨平台夹具

- **Decision**: 新增 10 个测试类（下表），全在 `sableos-tool`；`ReactAssemblyTest` 同步改造（不是删断言——装配现实变了，断言随 FR-007 升级：`find("http_get")` 由 null 变非 null、审计失败条目改用真未知名工具、新增一条成功工具调用端到端）。测试方法名英文驼峰，课件中文原文进 `@DisplayName`。
- **跨平台夹具**: CI 为 ubuntu-latest、开发机为 Windows——ShellTools 夹具按 `os.name` 分支选可执行文件（Windows：`cmd /c echo hi`、`cmd /c exit 3`、超时用 `ping -n 5 127.0.0.1`；Linux：`echo`、`sh -c "exit 3"`、`sleep 5`）；FileTools 用 `@TempDir`；HttpTools/DDG 用 JDK 内置 `HttpServer`（随机端口，零出网）；MCP 全程 mock 客户端（不真起子进程）。
- **harness 映射**（课件 §四 → 测试类）: `SableToolContractTest`（参数化遍历 MethodSource `allRegisteredTools`——按运行时装配同构构造注册表，新工具自动纳入）、`ToolRegistryTest`（三来源统一注册 + 重名拒绝 + 过滤不多不少）、`FileToolsTest`/`ShellToolsTest`/`HttpToolsTest`（正常 + 拦截 + 各自边界：edit_file 唯一匹配/找不到/多处且文件不动、grep/glob 200 上限注明与二进制跳过、shell 非零退出与超时、http 非 2xx 异常含状态码）、`McpToolAdapterTest`/`McpClientServiceTest`（转发原样/结果包装/失联不炸）、`McpConfigLoaderTest`（缺失=零/逐项解析/非法点名）、`InteractionToolsTest`（终端档拿到回答 + 无人值守报错）、`WebSearchToolsTest`（渲染 + 白名单先行零请求 + 引擎解析）、`NotifyToolsTest` 登记不落码（24 节后）。
- **Rationale**: skill"harness 先行"；课件两个最值钱测试（参数化契约 + MCP 失联）逐条保真。
- **Alternatives considered**: `NotifyToolsTest` 提前落码 → 违 19 节登记口径（enforce 真实现 24 节才到），拍板不落。

## D-12 依赖与交付面：pom 三件（均 BOM 管理）+ 配置文件示例

- **Decision**: `sableos-tool/pom.xml` 新增三个依赖（**无版本号，BOM 管理**）：`org.springframework.ai:spring-ai-model`（`@Tool/@ToolParam/ToolCallback/ToolCallbacks`）、`io.modelcontextprotocol.sdk:mcp`（聚合 mcp-core + mcp-json-jackson3）、`org.yaml:snakeyaml`（McpConfigLoader）。`sableos-boot`/`sableos-cli` 无新依赖。新增 `config/mcp_servers.yaml.example`（对齐 oryxos 先例，展示 `servers:` 列表与四字段；运行时读 `.sableos/mcp_servers.yaml`）。
- **Rationale**: 三者均为父 POM 已管理构件（附录 A-1），非新第三方（软门禁 #6 不触发）；示例文件不新增配置键（键名已被课件点名），仅落格式。
- **Alternatives considered**: 手写 MiniYAML → 重复造轮子且易错；示例文件放 `.sableos/` → 该目录为运行期目录（含 sqlite 库），放 `config/` 与 `dependency-check-suppressions.xml` 同处。

---

## 附录 A：核实证据（H0/H3 阶段实测，2026-10-16）

### A-1 依赖三级核实（BOM 声明 → 本地仓库 → 依赖树）

- 父 POM：`spring-ai.version=1.1.8`、`mcp.version=1.1.4`，`io.modelcontextprotocol.sdk:mcp` 在 `dependencyManagement`。
- `mvn dependency:tree`（实测）：`spring-ai-model:1.1.8` 可解析、`snakeyaml:2.4` 由 Boot 传递管理可解析。
- JAR 类清单（python zipfile 实测）：`McpClient/McpSyncClient/StdioClientTransport/ServerParameters/McpSchema/McpJsonDefaults` 均在 `mcp-core-1.1.4.jar`（`mcp-1.1.4.jar` 是聚合壳，无类）；`org/springframework/ai/support/ToolCallbacks.class`、`org/springframework/ai/tool/ToolCallback.class`、`org/springframework/ai/tool/annotation/{Tool,ToolParam}.class` 在 `spring-ai-model-1.1.8.jar`。
- `McpSyncClient` 为非 final 类（可 Mockito mock）；`McpSchema.Tool.builder().name().description().inputSchema(McpJsonMapper,"{}").build()` 与 `McpSchema.CallToolRequest(String, Map<String,Object>)` 已核实。

### A-2 前序交付物在位核实（依赖存在性检查）

- core：`SableTool`（四方法，`getInputSchema()` 返回 **String**——本仓 17 节口径，非课件草图的 `JsonSchema`；本节不改）、`ToolResult`（`success(String)`/`failure(String,boolean)`）、`ToolCatalog`（`toolsFor/find`）、`ToolExecutor`（`TODO(24 节): Sandbox.enforce(...)` 接线位）、`ProfileContext`（纯静态：`set/current/clear`）。
- boot：`ReactConfiguration` 的 `EmptyToolCatalog`（待替换点）；测试 `ReactAssemblyTest` 两处断言将随装配升级同步改造（D-11）。
- cli：`ToolListCommand` 占位文案（待刷新）；tool 模块现仅 notify 三件 + `spring-web` + spotbugs-annotations(provided)。

### A-3 oryxos 对齐表（已核实签名，差异逐处注明理由）

| 产物 | 对齐 | 本仓差异（理由） |
| --- | --- | --- |
| `ToolRegistry` | 方法集与重名拒绝文案对齐 | 不设 `registerMcpTool/unregister/asMap`（后节能力） |
| `AnnotatedToolAdapter` | 逐行为同构 | `ToolResult.success`（本仓 API 名） |
| `PermissiveSandbox` | WARN 留痕语义对齐 | 四值 `ActionType`（行内注明）；CRLF 净化用 AGENTS 指定形态 |
| `McpConfigLoader` | `servers:` 列表 + command 空格拆分 + fail-loud 对齐 | 只留四字段；`${ENV}` 占位与 save/raw 管理台能力归后续节 |
| `McpClientService` | 工厂注入 + connectAll 只 WARN 对齐 | 仅 stdio 档（远程 SSE 归后节）；无 status/disconnect（管理台归 26 节） |
| `McpToolAdapter` | 三要素映射 + 转发 + isError→可重试失败对齐 | 不含 64KB 截断（后节演进） |
| `FileTools` | `@Tool` 方法 + 首行 enforce 对齐 | 无 guards（22 节）、write_file 不建目录（spec 边界）、grep/glob 含截断注明 |
| `ShellTools` | 直接执行 + 超时 + 杀进程树对齐 | 无 64KB 输出上限/ProcessStarter 后端（后节） |
| `HttpTools` | `@Tool` + 首行 enforce + RestClient 对齐 | 不手动跟随重定向（出网管控归 23/24 节）；`HTTP_READ` 概念不存在（四值口径） |
| `InteractionTools`/`UserInteraction` | 接口形态对齐 | `InteractionUnavailableException` → JDK 异常（D-07 理由） |
| `SearchProvider`/`DuckDuckGoSearchProvider`/`WebSearchTools` | 接口内嵌 record + Instant Answer 对齐 | enforce 单点落引擎入口实 URL（D-08 理由） |
| `NotifyTools` | 解析四态 + InOrder + 审计无旁路对齐 | Map 最小组合（clarify #1）；按名引用渠道模型（19 节契约） |

### A-4 平台与门禁笔记

- ShellTools 夹具跨平台分支（ubuntu-latest CI / Windows 开发机）；子进程输出按 `Charset.defaultCharset()` 解码。
- 静态门禁预案：`AnnotatedToolAdapter`/`McpToolAdapter` 构造持有回调/客户端若触发 SpotBugs `EI_EXPOSE_REP2`，按 `WebhookNotifyAdapter` 先例方法级定点抑制并写理由；WARN 日志 target 的 findsecbugs `CRLF_INJECTION_LOGS` 报警按 `PermissiveSandbox` 写法定点处理。
- P3C/ASM 语法禁区照旧（不用增强 switch `default ->` 等 Java 18+ 形态）；首次提交前 `mvn spotless:apply`。
