# Contract: MCP 接入（配置 / 加载 / 连接注册 / 适配器）

**来源**: 课件 §三（`McpClientService` + `McpToolAdapter` 两段骨架逐字）+ FR-005 + 技术方案 §6.4。落 `com.sableos.tool.mcp`（四件：`McpServerConfig` / `McpConfigLoader` / `McpClientService` / `McpToolAdapter`）。

## 配置文件契约（`.sableos/mcp_servers.yaml`）

```yaml
# config/mcp_servers.yaml.example 提供同名示例；运行时读取 .sableos/mcp_servers.yaml
servers:
  - name: filesystem                # server 名（日志/点名）
    transport: stdio                # 核心阶段仅 stdio
    command: npx -y @modelcontextprotocol/server-filesystem /tmp   # 空白拆分：可执行 + args
    env:                            # 可选
      SOME_TOKEN: ${SOME_TOKEN}     # ${ENV} 占位解析不做（后节）；本节原样透传
```

| 输入 | 结果 |
| --- | --- |
| 文件不存在 | 零 server（`List.of()`），启动照常——**不报错** |
| `servers:` 键缺失 / null | 零 server |
| YAML 解析失败 / `servers` 非列表 / 条目非对象 | `IllegalArgumentException` **点名报错**，不静默吞掉 |
| 合法条目 | `McpServerConfig(name, transport, command, env)`；`env` 缺省 → 空 Map |

## McpClientService 契约

```java
public McpClientService(McpConfigLoader configLoader);                                           // 生产：默认工厂
public McpClientService(McpConfigLoader configLoader, Function<McpServerConfig, McpSyncClient> clientFactory);  // 测试：mock 工厂
public void connectAll(ToolRegistry registry);
```

| # | 契约 | 守点 |
| --- | --- | --- |
| M1 | 对每个 config：`factory(cfg)` → `initialize()` → `listTools()` → 逐个 `McpToolAdapter` 注册进 registry | "tools/list 返回的工具被包装注册"（US3） |
| M2 | transport 非 `stdio` → `LOG.warn` 跳过（核心阶段口径，如实）；任何 **RuntimeException**（连接失败 / listTools 抛错）→ `LOG.warn("MCP server {name} 连接失败，跳过它的工具: {msg}")` 后继续下一个——**connectAll 永不抛出** | 课件守点：失联不拖垮启动（SC-003） |
| M3 | 好 server 工具注册率 100%、坏 server 工具不在注册表、其余照常 | 课件 harness 最值钱断言 |
| M4 | `StdioClientTransport(ServerParameters.builder(parts[0]).args(...).env(cfg.env()).build(), McpJsonDefaults.getMapper())` → `McpClient.sync(...).build()`——真实 SDK 调用链；子进程参数按空白拆分 | 已核实的 1.1.4 API |

## McpToolAdapter 契约（`implements SableTool`）

```java
public McpToolAdapter(McpSyncClient client, McpSchema.Tool tool);

getName()        → tool.name()
getDescription() → tool.description()
getInputSchema() → MAPPER.writeValueAsString(tool.inputSchema())   // 序列化失败 → IllegalStateException 点名
execute(input)   → 参数原样转发 + 结果包装
```

| # | 契约 | 守点 |
| --- | --- | --- |
| M5 | `execute`：`JsonNode` → `Map<String,Object>`（`convertValue`）→ `client.callTool(new McpSchema.CallToolRequest(tool.name(), args))`——**参数原样转发**（不增不改不缩写） | US3.2 / harness ArgumentCaptor 断言 |
| M6 | `CallToolResult.isError=true` → `ToolResult.failure("MCP 调用失败: " + content, true)`（**可重试**——外部故障多为瞬态）；成功 → `ToolResult.success(TextContent 文本拼接)` | 课件骨架 `.orElseGet(() -> ToolResult.failure("MCP 调用失败", true))` |
| M7 | 不含 64KB 截断等 oryxos 后期特性（课件最小骨架，harness 未点名） | 范围收敛 |

## Harness 映射

| 测试 | 断言 |
| --- | --- |
| `McpConfigLoaderTest.missingFileYieldsZeroServers` | 不存在路径 → `List.of()` 不抛 |
| `McpConfigLoaderTest.parsesAllFieldsPerEntry` | name/transport/command/env 逐项解析（含 env 缺省→空 Map） |
| `McpConfigLoaderTest.malformedFileFailsLoud` | 非列表 / 条目非对象 → `IllegalArgumentException` 消息点名 |
| `McpClientServiceTest.goodServerRegisters_badServerOnlyWarns` | mock 工厂：好 server `listTools` 返回 `[good_mcp_tool]`、坏 server 抛 `RuntimeException("refused")` → `connectAll` 不抛；`registry.contains("good_mcp_tool")` 真、`contains("bad_mcp_tool")` 假（课件骨架断言逐条对齐） |
| `McpToolAdapterTest.contractMapsFromMcpToolSpec` | name/description/inputSchema 直映射（含 schema JSON 序列化） |
| `McpToolAdapterTest.executeForwardsArgsAndWrapsResult` | ArgumentCaptor：`CallToolRequest.name()` 与 `arguments()` 与输入 Map **逐字相等**；`CallToolResult(TextContent, false)` → `success` 且内容含文本 |
| `McpToolAdapterTest.mcpErrorWrapsAsRetryableFailure` | `isError=true` → `success=false` + `retryable=true` + 消息含回复文本 |

**全程 mock `McpSyncClient`（非 final 可 mock），不真起子进程、不碰网**；`McpSchema.Tool.builder().name().description().inputSchema(McJsonDefaults.getMapper(), "{}").build()` 构造夹具已核实。
