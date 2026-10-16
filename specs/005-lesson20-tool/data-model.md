# Data Model: 第 20 节 Tool 体系

**Feature**: `005-lesson20-tool` | **Date**: 2026-10-16

> **零持久化模型**：本节零表、零迁移、零 `ddl-auto`。工具审计走 17 节既有 `tool_invocations`（列不变）；下列实体全部为**运行期内存模型**（类/record 形态），是装配、过滤与测试的语义依据。

## 1. 工具注册表（ToolRegistry，运行期内存）

| 项 | 定义 |
| --- | --- |
| 形态 | `com.sableos.tool.ToolRegistry implements com.sableos.core.tool.ToolCatalog` |
| 内部存储 | `ConcurrentHashMap<String, SableTool>`（名称 → 工具，唯一权威） |
| 注册路径① | `register(SableTool)`——直接实现来源（`NotifyTools`、`McpToolAdapter` 包装后） |
| 注册路径② | `registerAnnotated(Object bean)`——`ToolCallbacks.from(bean)` 逐回调包 `AnnotatedToolAdapter` 后注册（内置五 bean 与方式三共用） |
| 唯一性 | 重名注册 → `IllegalStateException("工具重名，拒绝注册: " + name)`；**不静默覆盖** |
| 查询 | `find(String)`：未命中返回 `null`（core 契约）；`contains(String)`；`all()`（`List.copyOf`，测试 MethodSource 用） |
| 过滤 | `filterByNames(List<String>)` → 结果 = 声明列表中存在于注册表的项、**顺序随声明顺序**；未知名跳过不报错；空声明得空列表 |
| Profile 视图 | `toolsFor(Profile)` = `filterByNames(profile.tools())`——"不多不少"（FR-006，多一/少一都错） |

**不变量**：

- 注册表是 Prompt 组装（`PromptBuilder` 取 `toolsFor`）与执行解析（`ToolExecutor` 取 `find`）的同一份真相——两者注入同一 bean，MCP 注册完成与首轮对话间无时间窗差异。
- 注册表不感知来源：内部只有 `SableTool`，无来源标记字段（"循环对来源无感知"，FR-001）。

## 2. 工具面（12 个注册工具；9 个内置 + 3 个扩展文件工具家族视图）

| 工具名 | 类・来源 | 首行校验 | 关键语义 |
| --- | --- | --- | --- |
| `read_file` | FileTools・@Tool | FILE_READ, path | 非普通文件点名报错；不存在不抛裸异常 |
| `write_file` | FileTools・@Tool | FILE_WRITE, path | **不自动建父目录**（spec 边界）；失败点名路径 |
| `list_dir` | FileTools・@Tool | FILE_READ, path | 非目录点名；名称排序输出 |
| `edit_file` | FileTools・@Tool | FILE_WRITE, path | old 唯一匹配才替换；找不到/多处 → 报错且**文件零字节改动** |
| `grep` | FileTools・@Tool | FILE_READ, path | 输出 `文件:行号:内容`；**200 条截断注明**；二进制/非 UTF-8 跳过不中断 |
| `glob` | FileTools・@Tool | FILE_READ, path | 通配找路径；**200 条截断注明** |
| `shell` | ShellTools・@Tool | SHELL_COMMAND, executable | argv 直执行（无 Shell 解释）；30s 超时杀进程树；非零退出带 stderr |
| `http_get` | HttpTools・@Tool | HTTP_REQUEST, url | RestClient GET，返回响应体 |
| `http_post` | HttpTools・@Tool | HTTP_REQUEST, url | JSON body POST，返回响应体 |
| `ask_user` | InteractionTools・@Tool | （无涉外 IO，不设校验位） | 递 `UserInteraction`；拿不到回答抛异常 |
| `web_search` | WebSearchTools・@Tool | （治理在引擎入口，见 §6） | 渲染标题/链接/摘要文本 |
| `notify` | NotifyTools・**直实现** | HTTP_REQUEST, url（解析后） | 四态渠道解析 + enforce 先于 send（见 §7） |

**后续节补位（登记，本节不做）**：`save_memory` / `recall_memory`（22 节注册）→ 九个内置工具面齐。

**失败口径对照表**（三条并存、互不混用）：

| 场景 | 工具层行为 | 执行层（ToolExecutor 既有路径） |
| --- | --- | --- |
| 校验违规（任何工具） | 抛 `SandboxViolationException`、外部动作零发生 | 收敛为失败结果 + 审计（`执行异常` 路径） |
| http 非 2xx | RestClient 默认异常（含状态码），不自建错误页 | 同上——模型拿到带状态码的失败结果可自救 |
| notify 发送失败 | 适配器契约上抛（19 节口径） | 同上 |
| notify 解析类失败（无渠道/未注册/上下文缺失） | 直接返回 `ToolResult.failure(点名, false)` | 审计失败两态之一（非异常路径） |

## 3. AnnotatedToolAdapter（管道机制本体）

| 项 | 定义 |
| --- | --- |
| 形态 | `com.sableos.tool.AnnotatedToolAdapter implements SableTool`，构造接 `ToolCallback` |
| 三要素 | `getName`/`getDescription`/`getInputSchema` 直读 `callback.getToolDefinition()`（name/description/inputSchema——schema 由 Spring AI 生成） |
| 执行 | `callback.call(input == null ? "{}" : input.toString())` → `ToolResult.success(output)` |
| 异常 | **不捕获**（管道自身不吞——方法抛异常由执行层统一收敛，US4） |

## 4. Sandbox 前向五件（签名即契约，24 节沿用）

| 类型 | 定义 |
| --- | --- |
| `Sandbox`（接口） | `void enforce(SandboxAction action)`；校验不过抛 `SandboxViolationException` |
| `SandboxAction`（record） | `(ActionType type, String target)`——目标为路径 / 可执行文件 / URL |
| `ActionType`（enum） | **四值定死**：`FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST`（24 节 WhitelistSandbox 直接沿用，不增不改） |
| `SandboxViolationException` | `extends RuntimeException`（message 点名） |
| `PermissiveSandbox` | `implements Sandbox`：放行一切但逐次 `LOG.warn`（含 type + 净化后的 target）——"绝不静默裸奔"；24 节替换为 WhitelistSandbox |

**调用位不变量**：每个涉外工具方法**第一行**调用 `enforce`；违规时文件未动 / 命令未跑 / 请求未发（动作零发生）。`ToolExecutor` 中 17 节留下的接线位（TODO）归 24 节——本节校验在工具侧，不在执行器侧重复。

## 5. MCP 运行期模型

**McpServerConfig（record，配置文件条目映射）**：

| 组件 | 类型 | 规则 |
| --- | --- | --- |
| `name` | String | server 名（日志/点名用） |
| `transport` | String | 核心阶段仅 `stdio` 支持；其余 WARN 跳过 |
| `command` | String | 空白拆分可执行 + args（对齐 oryxos 已核实推定） |
| `env` | Map<String,String> | 缺省/ null → 空 Map |

**`.sableos/mcp_servers.yaml` 解析规则**（McpConfigLoader）：

| 输入 | 结果 |
| --- | --- |
| 文件不存在 | `List.of()`——零 server，启动照常（不报错） |
| 顶层 `servers:` 键缺失/null | 零 server |
| `servers` 值非列表 / 条目非对象 / YAML 解析失败 | `IllegalArgumentException` **点名报错**（不静默按零 server 吞掉） |
| 合法条目 | 逐项映射 `McpServerConfig` |

**运行时注册流**（McpClientService.connectAll(ToolRegistry)）：

```text
for cfg in loader.load():
    factory(cfg) → initialize() → listTools() → 逐个 McpToolAdapter 注册
    任何 RuntimeException → WARN "MCP server {name} 连接失败，跳过它的工具" → 继续下一个
```

- 连接工厂 `Function<McpServerConfig, McpSyncClient>` 构造注入（测试替换 mock，不真起子进程）。
- **无条件不变量**：connectAll 自身永不抛出——好 server 注册率 100%、坏 server 工具不在注册表、启动成功率 100%（SC-003）。
- `McpToolAdapter`：`execute(JsonNode)` → `convertValue` 为 `Map<String,Object>` → `client.callTool(CallToolRequest(name, args))`（**参数原样**）；`isError=true` → `failure(content, retryable=true)`；成功 → `success(TextContent 拼接)`。

## 6. 交互与搜索（接口先行）

**UserInteraction**（`com.sableos.tool.interaction`）：

| 档 | 行为 | 失败形态 |
| --- | --- | --- |
| `ConsoleUserInteraction` | 打印问题 + 读一行；构造可注入 `(InputStream, PrintStream, Charset)` | EOF → `IllegalStateException`（点名"输入流已结束"）；IO 异常 → `UncheckedIOException` |
| `UnsupportedUserInteraction` | 一律报不支持 | `UnsupportedOperationException`（点名"不支持向用户提问"） |

**SearchProvider**（`com.sableos.tool.web`）：`List<SearchResult> search(String query)`；`SearchResult(title, url, snippet)` 为接口内嵌 record（课件形态）。`DuckDuckGoSearchProvider`：`search()` 第一行 `enforce(HTTP_REQUEST, <真实请求 URL>)` → GET Instant Answer（`format=json&no_html=1`）→ 解析 `AbstractText/AbstractURL/Heading` + `RelatedTopics[].Text/FirstURL` → 结果列表；package-private 构造可换 endpoint（测试用）。

## 7. NotifyTools（19 节登记契约的兑现形态）

| 项 | 定义 |
| --- | --- |
| 形态 | `com.sableos.tool.builtin.NotifyTools implements SableTool`（直实现来源） |
| 构造 | `(Sandbox sandbox, NotifyChannelAdapter adapter, Map<String, NotifyTarget> channels)`；channels 经 `Collections.unmodifiableMap(new LinkedHashMap<>(...))` **保序**（不用 `Map.copyOf`——不保序） |
| 入参 schema | `content` 必传（模型侧声明）；`channel` 可选 = 全局注册表渠道名 |
| 解析顺序 | ① content 缺失/空白 → 失败点名 ② `ProfileContext.current()==null` → 失败"当前无 Agent 上下文" ③ channel 缺省/空白/`default`（哨兵优先）→ 第一个；空表 → 失败"无已注册渠道"；显式名未命中 → 失败点名**不回退** ④ `enforce(HTTP_REQUEST, target.config().get("url"))` ⑤ `adapter.send(target, content)` → `success("已推送")` |
| 顺序不变量 | **enforce 必须先于 send 被调用**（24 节后 `InOrder` 钉死；顺序反了就是漏洞） |
| 装配（本节） | channels = 空 Map → 调用即失败点名、零请求；注册表本体（SQLite+CRUD）留后续节、届时不改本类 |
| 审计 | 不建旁路——成败经 `ToolExecutor` 既有路径落 `tool_invocations` |

## 8. 与 core 的边界（本特性不改前序公共接口）

| core 既有类型 | 本节关系 |
| --- | --- |
| `SableTool` / `ToolResult` | 只实现/构造，签名零改 |
| `ToolCatalog` | `ToolRegistry` 新实现（接口零改） |
| `ToolExecutor` | 零改（注入的目录实现从空表换成注册表；24 节 TODO 接线位保留原地） |
| `Profile` / `ProfileContext` | 只读消费（`tools()` 过滤、`current()` 存在性检查） |