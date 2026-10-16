# Implementation Plan: Tool 体系——Agent 真正能动手干事的那双手

**Branch**: `005-lesson20-tool` | **Date**: 2026-10-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-lesson20-tool/spec.md`

## Summary

交付"Agent 的手"：**统一注册表**（`ToolRegistry` 替换 17 节的空工具表，ReAct 循环/执行器对工具来源零感知）+ **六个内置工具**（read_file / write_file / list_dir / shell / http_get / http_post，全走 `@Tool` 注解管道、方法第一行过安全校验）+ **五个扩展基础工具**（edit_file / grep / glob / ask_user / web_search）+ **MCP 接入**（`.sableos/mcp_servers.yaml` → 连接 → tools/list 包装注册；单 server 失联只 WARN 不拖垮启动）+ **Sandbox 前向五件**（含 `PermissiveSandbox` 临时装配，真实现 24 节）+ **NotifyTools 完成注册**（19 节登记契约兑现，渠道来源 = 有序 Map 最小组合、本节装配空表）。全部落既有模块 `sableos-tool`（AGENTS.md §5.2"不把 Tool 拆多模块"），装配点改 `sableos-boot`，`tool list` 刷新为静态 12 工具清单。

技术路径（决策详见 [research.md](./research.md) D-01~D-12，签名对齐已核实）：

- **`sableos-tool`**（主战场，五个子包）：
  - `com.sableos.tool`：`ToolRegistry implements ToolCatalog`（两条注册路径 + 重名拒绝 + "不多不少"过滤）、`AnnotatedToolAdapter`（`@Tool` 管道机制本体，异常不吞）。
  - `com.sableos.tool.sandbox`：`Sandbox` / `SandboxAction` / `ActionType`（**四值定死**：FILE_READ/FILE_WRITE/SHELL_COMMAND/HTTP_REQUEST，24 节沿用）/ `SandboxViolationException` / `PermissiveSandbox`（放行必 WARN 留痕）。
  - `com.sableos.tool.builtin`：`FileTools`（6 个 `@Tool` 方法）、`ShellTools`（`shell`，超时 30s、不经 Shell 解释、杀进程树）、`HttpTools`（`http_get`/`http_post`）、`InteractionTools`（`ask_user`）、`WebSearchTools`（`web_search`）、`NotifyTools`（直实现 `SableTool`，有序 Map + 四态解析 + enforce 先于 send）。
  - `com.sableos.tool.mcp`：`McpServerConfig` / `McpConfigLoader`（snakeyaml，缺失=零/非法=点名）/ `McpClientService`（工厂注入、connectAll 只 WARN）/ `McpToolAdapter`（三要素映射 + 协议转发 + isError→可重试失败）。
  - `com.sableos.tool.interaction`：`UserInteraction` + `ConsoleUserInteraction` + `UnsupportedUserInteraction`（JDK 异常，零新对外异常类型）。
  - `com.sableos.tool.web`：`SearchProvider`（内嵌 `SearchResult`）+ `DuckDuckGoSearchProvider`（Instant Answer，enforce 落引擎入口实 URL）。
- **`sableos-boot`**：新增 `com.sableos.boot.tool.ToolConfiguration`（仿 ProviderConfiguration）——Sandbox/ToolRegistry/McpClientService 三个 bean + 装配注册（内置五 bean registerAnnotated、NotifyTools 空表 register、connectAll）；`ReactConfiguration` 删除 `EmptyToolCatalog`（FR-007）。
- **`sableos-cli`**：`ToolListCommand` 刷新静态 12 工具清单 + 如实标注（clarify #2）。
- **配置**：`config/mcp_servers.yaml.example`（运行时读 `.sableos/mcp_servers.yaml`）。
- **测试**：10 个新测试类（harness 映射见 D-11）+ `ReactAssemblyTest` 同步升级；`NotifyToolsTest` 登记不落码（24 节后）。

## Technical Context

**Language/Version**: Java 21（enforcer 钉死 `[21,)`；P3C/PMD 以 17 目标语法解析——避开 Java 18+ 语法形态：增强 switch 的 `default ->`、record pattern、pattern switch 一律不用；records/sealed 可用）

**Primary Dependencies**:

- `sableos-tool/pom.xml` 新增三件（**均无版本号、BOM 管理**，附录 A-1 实核在位）：
  - `org.springframework.ai:spring-ai-model`（`@Tool/@ToolParam` 注解、`ToolCallback/ToolCallbacks`——schema 自动生成管道）
  - `io.modelcontextprotocol.sdk:mcp`（聚合 mcp-core 1.1.4 + mcp-json-jackson3：`McpClient/McpSyncClient/StdioClientTransport/ServerParameters/McpSchema/McpJsonDefaults`）
  - `org.yaml:snakeyaml`（McpConfigLoader；Boot 传递管理 2.4）
- 既有：`spring-web`（RestClient，19 节已引入）、`jackson-databind`（core 传递）、`slf4j`（spring-boot 传递）。
- 测试：父 POM 自动注入 `spring-boot-starter-test`（JUnit5/Mockito/AssertJ）；假 HTTP 服务用 JDK `jdk.httpserver`——零新增。
- 消费前序交付：core `SableTool`/`ToolResult`/`ToolCatalog`/`ToolExecutor`/`ProfileContext`/`Profile`（17 节，**不改**）；`sableos-tool` notify 三件（19 节，**不改**，`NotifyTools` 构建其上）。

**Storage**: **N/A——本节零数据库迁移、零新表**。审计走 17 节既有 `tool_invocations` 路径（`ToolExecutor` 收敛，工具成败两态自动落库，模型不知情）。渠道注册表（`notify_channels`）与 MCP 管理数据归后续节。

**Testing**: JUnit 5 + AssertJ + Mockito。全部单测零 Spring 上下文、零出网、零真子进程（JDK `HttpServer` 随机端口假服务 + mock `McpSyncClient`）；`ReactAssemblyTest`（boot，@SpringBootTest 临时 SQLite）守装配级端到端。测试方法名英文驼峰，课件中文原文进 `@DisplayName`（含 `http_get_应能取回响应` / `http_get_命中白名单外域名应被拦下` 两条模板逐条落地）。集成冒烟打 `@Tag("integration")` CI 跳过（本节不新增）。ShellTools 夹具按 OS 分支（ubuntu CI / Windows 开发机）。

**Target Platform**: JVM 服务端单体（fat JAR 形态不变；装配在 boot 起效）。

**Project Type**: Maven 多模块（改动 4 个既有模块：`sableos-tool` 主战场、`sableos-boot` 装配、`sableos-cli` 命令文案、`sableos-boot` 测试；不新增模块）。

**Performance Goals**: N/A 量化目标——同步阻塞工具调用；`shell` 带 30s 超时兜底（防挂死拖垮循环）；grep/glob 200 条上限（防上下文撑爆）。

**Constraints**:

- 已定字面量逐字保真（软门禁 #2）：`ActionType` 四值、工具名、`SandboxAction(type, target)`、`ToolRegistry` 重名文案、`@Tool` 方法签名与课件示例一致。
- 安全不变量：每个涉外工具方法**第一行** `enforce`；校验不过动作零发生；`PermissiveSandbox` 放行逐次 WARN（绝不静默裸奔）。
- 失败口径：校验违规 = 异常上抛（课件模板 `assertThrows`）；notify 发送失败 = 适配器上抛；http 非 2xx = RestClient 异常含状态码；三者均经 `ToolExecutor` 既有收敛路径落失败结果 + 审计——执行层"包结果不抛"（17 节已定的 javadoc 口径）。
- 渠道解析四态 + `enforce` 先于 `send`（19 节登记契约逐条兑现）；`ProfileContext` 静态调用（无实例可注入）。
- 用户可控字符串入日志前 CRLF 净化（`replaceAll("[\\r\\n]", "_")` 形态，勿改）。
- 零新对外概念（软门禁 #1）：不造 `NotifyChannelRegistry`、`InteractionUnavailableException` 等清单外类型；不建文档外抽象层。
- 不出现 Reactor/`CompletableFuture`/自建线程池（本结构排序流式；虚拟线程用于 shell 流排空——`Thread.ofVirtual`/`newVirtualThreadPerTaskExecutor` 可用）；无 Spring AI 自动工具执行路径。
- 避开 Java 18+ 语法；首次提交前 `mvn spotless:apply`（格式违规挂构建）。
- 全程不自动 commit/push/运行 package.sh（同步时机由用户决定）。

**Scale/Scope**: 4 模块改动；`sableos-tool` 新增约 20 个主类 + 10 个测试类；`sableos-boot` 1 新配置类 + 1 处删减 + 1 测试改造；`sableos-cli` 1 处文案；1 个示例配置文件；0 表 / 0 迁移 / 0 新第三方 / 0 新模块。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据/说明 |
| --- | --- | --- |
| I. JDK 21 + Spring Boot 3.x 单体 | ✅ | 零新模块；三个新依赖均 BOM 构件；虚拟线程用于 shell 流排空（JDK 21 原生）；不引响应式 |
| II. 自实现 ReAct 循环 | ✅ | 本节不碰循环；执行入口唯一在 17 节 `ToolExecutor`（只换注入的目录实现）；Provider 层不调 `execute` |
| III. Spring AI 只用一半 | ✅ | 只用 `@Tool` schema 生成 + 反射调用（`ToolCallbacks`）；**无自动工具执行路径**（`AnnotatedToolAdapter` 只暴露 `execute` 由 `ToolExecutor` 调）；管道不吞异常 |
| IV. Provider 显式映射 | ✅ | 不触碰 Provider 路由 |
| V. Tool 三档且单模块；Agent 目录不当 Tool | ✅ | 三档全落**单模块** `sableos-tool`（不拆新模块）；`.sableos/agents/` 仍归 `ContextLoader`（不碰） |
| VI. 审计 day one 落库 | ✅(兑现) | 工具成败经 `ToolExecutor` 既有路径两态落 `tool_invocations`（含 `trace_id`）；NotifyTools 不建旁路；MCP 工具同走该路径——零新审计逻辑、零改表 |
| VII. Sandbox 接口先行 | ✅(本节主体) | 五件交付 + 每个涉外工具第一行 `enforce`；`PermissiveSandbox` 临时装配（放行必 WARN）、真实现与 `PromptBuilder` 接线位在 24 节 |
| VIII. 范围收敛 | ✅ | Tool Policy / 按需加载 / 自己变 MCP server / 容器沙箱 / 并行工具调用全在范围外（课件边界）；两处拍板收敛见 Complexity Tracking |
| IX. 每个 US 可演示 Demo | ✅ | US1~US5 由 harness 即 Demo（注册表契约参数化、假 HTTP server 收发与拦截、MCP mock 失联隔离）；US6 由 NotifyTools 单测（mock 适配器）演示解析四态 + InOrder，完整链路 24 节后 |
| 硬约束（AGENTS §5.1/§5.2） | ✅ | 自实现循环无涉；禁用自动执行已在原则 III；单模块；无 synchronized 阻塞调用（shell 虚拟线程排空）；无 Reactor/CompletableFuture；traceId/留痕不新增（工具审计在既有 auditor 内）；`session_id` 单点不碰 |

**Post-Phase 1 re-check**: 设计未引入新违规，十处决策（D-01~D-12）逐条对照宪章与 AGENTS.md §5 无冲突。三处拍板取舍记入 Complexity Tracking：①NotifyTools 渠道来源 Map 最小组合 + 本节装配空表（clarify #1，注册表本体后节）；②Sandbox 五件含 `PermissiveSandbox` 临时装配（课件明文，24 节替换）；③web_search enforce 落引擎入口（D-08，与 oryxos 形态差异已论证）。两处静态门禁预案（非违规）：`AnnotatedToolAdapter`/`McpToolAdapter` 构造注入若触发 `EI_EXPOSE_REP2`，按 `WebhookNotifyAdapter` 先例定点抑制并写理由；`PermissiveSandbox` WARN 日志 CRLF 报警按 AGENTS 指定形态处理。

## Project Structure

### Documentation (this feature)

```text
specs/005-lesson20-tool/
├── plan.md              # 本文件
├── spec.md              # /speckit-specify + /speckit-clarify 产出（含 2 条拍板澄清）
├── research.md          # Phase 0：D-01~D-12 决策 + 附录 A（依赖/前序产物/oryxos 对齐三级核实）
├── data-model.md        # Phase 1：注册表/工具面/Sandbox 五件/MCP 配置/NotifyTools 实体与不变量
├── quickstart.md        # Phase 1：可执行验收向导（全量门禁 + 关键回归 + 人工项）
├── contracts/
│   ├── tool-registry.md    # 注册表 + 注解管道：接口/注册语义/过滤不多不少/harness 映射
│   ├── sandbox-forward.md  # Sandbox 五件：签名/四值/放行留痕/24 节接线口径
│   ├── builtin-tools.md    # 9 个内置/扩展工具方法级契约 + 失败口径 + harness 映射
│   ├── mcp.md              # 配置格式/加载规则/连接注册语义/适配器契约/harness 映射
│   └── notify-tool.md      # NotifyTools 实现契约（Map 形态兑现 19 节登记）
├── checklists/
│   └── requirements.md      # 规格质量清单
└── tasks.md             # /speckit-tasks 产出
```

### Source Code (repository root)

```text
sableos-tool/pom.xml                                  # 本节修改：+ spring-ai-model / io.modelcontextprotocol.sdk:mcp / snakeyaml（均 BOM 管控）

sableos-tool/src/main/java/com/sableos/tool/
├── ToolRegistry.java                                 # 新增：注册表（implements core ToolCatalog）
├── AnnotatedToolAdapter.java                         # 新增：@Tool 管道机制本体
├── sandbox/
│   ├── Sandbox.java                                  # 新增：校验接口（enforce(动作)）
│   ├── SandboxAction.java                            # 新增：record(ActionType, String)
│   ├── ActionType.java                               # 新增：四值定死（24 节沿用）
│   ├── SandboxViolationException.java                # 新增：违规异常（RuntimeException）
│   └── PermissiveSandbox.java                        # 新增：临时装配（放行必 WARN；24 节替换）
├── builtin/
│   ├── FileTools.java                                # 新增：read_file/write_file/list_dir/edit_file/grep/glob（6 个 @Tool 方法）
│   ├── ShellTools.java                               # 新增：shell（超时 30s、argv 直执行、杀进程树）
│   ├── HttpTools.java                                # 新增：http_get/http_post（RestClient）
│   ├── InteractionTools.java                         # 新增：ask_user（递 UserInteraction）
│   ├── WebSearchTools.java                           # 新增：web_search（渲染 SearchProvider 结果）
│   └── NotifyTools.java                              # 新增：notify（直实现 SableTool；19 节契约兑现）
├── interaction/
│   ├── UserInteraction.java                          # 新增：交互抽象（接口先行）
│   ├── ConsoleUserInteraction.java                   # 新增：终端档（读一行；EOF 抛异常）
│   └── UnsupportedUserInteraction.java               # 新增：无人值守档（明确报不支持）
└── mcp/
    ├── McpServerConfig.java                          # 新增：record(name/transport/command/env)
    ├── McpConfigLoader.java                          # 新增：snakeyaml 读 .sableos/mcp_servers.yaml
    ├── McpClientService.java                         # 新增：连接维护 + connectAll（失联只 WARN）
    └── McpToolAdapter.java                           # 新增：SableTool 包装（三要素映射 + 转发 + 结果包装）

sableos-tool/src/main/java/com/sableos/tool/web/
├── SearchProvider.java                               # 新增：搜索抽象（内嵌 SearchResult）
└── DuckDuckGoSearchProvider.java                     # 新增：Instant Answer 档（enforce 落引擎入口）

sableos-tool/src/test/java/com/sableos/tool/          # 10 个新测试类（harness 映射，见 D-11）
├── SableToolContractTest.java                        # 参数化遍历注册表契约三件套（MethodSource 镜像运行时装配）
├── ToolRegistryTest.java                             # 三来源统一注册 + 重名拒绝 + 过滤不多不少
├── builtin/FileToolsTest.java                        # 正常 + 拦截 + edit_file 唯一匹配 + grep/glob 上限与跳过
├── builtin/ShellToolsTest.java                       # 正常 + 拦截 + 非零退出带 stderr + 超时终止（OS 分支夹具）
├── builtin/HttpToolsTest.java                        # 正常（JDK HttpServer）+ 拦截零请求 + 非 2xx 异常含状态码
├── builtin/InteractionToolsTest.java                 # 终端档拿到回答 + 无人值守报错
├── builtin/WebSearchToolsTest.java                   # 渲染 + 白名单先行零请求 + 引擎解析
├── mcp/McpConfigLoaderTest.java                      # 缺失=零/逐项解析/非法点名
├── mcp/McpClientServiceTest.java                     # 好 server 注册、坏 server WARN 不炸
└── mcp/McpToolAdapterTest.java                       # 三要素映射 + 转发原样 + 结果包装

sableos-boot/src/main/java/com/sableos/boot/
├── tool/ToolConfiguration.java                       # 新增：Sandbox/ToolRegistry/McpClientService + 装配注册（connectAll）
└── react/ReactConfiguration.java                     # 修改：删 EmptyToolCatalog 及其 @Bean（目录改由 ToolConfiguration 提供）

sableos-boot/src/test/java/com/sableos/boot/ReactAssemblyTest.java  # 修改：断言随装配升级（find 非 null、未知名工具换名、+成功链路）

sableos-cli/src/main/java/com/sableos/cli/command/ToolListCommand.java  # 修改：刷新静态 12 工具清单 + 如实标注

config/mcp_servers.yaml.example                       # 新增：配置格式示例（运行时读 .sableos/mcp_servers.yaml）

# 登记（本轮不落码，完成时点 24 节之后）——契约见 contracts/notify-tool.md：
#   sableos-tool/src/test/java/com/sableos/tool/builtin/NotifyToolsTest.java（第二批 harness：渠道解析三断言 + InOrder）
# 依赖登记：NotifyTools 的 enforce 真实现（WhitelistSandbox 替换 PermissiveSandbox）与 24 节接线
```

**Structure Decision**: 主战场为既有模块 `sableos-tool`（技术方案 §10：内置 Tool + MCP Client + ToolRegistry + Sandbox 三合一；AGENTS.md §5.2"不把 Tool 拆多模块"），包结构对齐课件与 oryxos（`builtin`/`sandbox`/`mcp`/`interaction`/`web` + 模块根包注册表/适配器）。装配落 `sableos-boot` 既有配置体系（新 `com.sableos.boot.tool` 包，仿 `provider`/`agent`/`react` 三配置先例）。依赖方向不变（`core ← sableos-tool ← sableos-boot`；本节新增依赖均 BOM 管理构件，无循环、不触 core）。

## Complexity Tracking

| 取舍 | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| NotifyTools 渠道来源 = 有序 `Map<渠道名, 通知目标>`、本节装配空表 | clarify #1 拍板：零新对外类型（软门禁 #1 不触发）；19 节契约"注册表本体后节落地"不变 | 新造 `NotifyChannelRegistry` 接口 → 触软门禁 #1；等注册表本体到位再注册 → 违课件"在此完成注册" |
| Sandbox 五件含 `PermissiveSandbox`（放行必 WARN） | 课件明文"先以接口调用形式接入"+ 工具构造强制要求校验器（不允裸奔）——无装配实现则本节不可运行 | 只交接口四件 → 装配无法构造工具；静默放行实现 → 违"绝不静默裸奔" |
| web_search 的 enforce 落引擎入口（实 URL），工具层不设伪目标 | SableOS `ActionType` 只有 `HTTP_REQUEST`（24 节按域名白名单）；伪目标（无 host）会逼 24 节特判伪目标、脏化白名单解析（D-08） | 照抄 oryxos 工具层伪目标 → 其 `HTTP_READ` 默认放行语义与 SableOS 四值口径不兼容 |
| `write_file` 不自动创建父目录（与 oryxos 不同） | spec 边界明文"不擅自递归建目录——那是模型该显式做的决定"；失败结果点名路径 | 照抄 oryxos `createDirectories` → 与已定 spec 边界冲突 |
| `InteractionUnavailableException` 不新造，用 JDK 异常 | 该类型不在课件交付物清单（软门禁 #1）；两条实现语义用 `UnsupportedOperationException`/`IllegalStateException` 精确表达 | 照抄 oryxos 专用异常 → 新增清单外 public 类型 |
