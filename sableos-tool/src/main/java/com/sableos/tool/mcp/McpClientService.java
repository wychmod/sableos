package com.sableos.tool.mcp;

import com.sableos.tool.ToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按配置连接 MCP server，把 tools/list 返回的工具包装注册进 {@link ToolRegistry}（技术方案 §6.4）。
 *
 * <p>核心契约：失联不拖垮启动——连接 / listTools 的任何 RuntimeException 只 WARN 后跳过该 server，{@code connectAll}
 * 永不抛出。核心阶段仅支持 stdio，其余 transport 如实 WARN 跳过。
 */
public class McpClientService {

  private static final Logger LOG = LoggerFactory.getLogger(McpClientService.class);

  private static final String TRANSPORT_STDIO = "stdio";

  private final McpConfigLoader configLoader;
  private final Function<McpServerConfig, McpSyncClient> clientFactory;

  /** 生产装配：默认 stdio 子进程工厂。 */
  public McpClientService(McpConfigLoader configLoader) {
    this(configLoader, McpClientService::defaultClient);
  }

  /** 测试装配：注入 mock 工厂，不真起子进程、不碰网。 */
  public McpClientService(
      McpConfigLoader configLoader, Function<McpServerConfig, McpSyncClient> clientFactory) {
    this.configLoader = configLoader;
    this.clientFactory = clientFactory;
  }

  /** 连接所有已配置 server 并注册其工具；单个 server 失败只 WARN 跳过，不影响其余与启动。 */
  public void connectAll(ToolRegistry registry) {
    for (McpServerConfig config : configLoader.load()) {
      registerServer(config, registry);
    }
  }

  private void registerServer(McpServerConfig config, ToolRegistry registry) {
    if (!TRANSPORT_STDIO.equals(config.transport())) {
      LOG.warn(
          "MCP server {} transport 非 stdio，核心阶段跳过: {}",
          safe(config.name()),
          safe(config.transport()));
      return;
    }
    try {
      McpSyncClient client = clientFactory.apply(config);
      client.initialize();
      List<McpSchema.Tool> tools = client.listTools().tools();
      for (McpSchema.Tool tool : tools) {
        registry.register(new McpToolAdapter(client, tool));
      }
      LOG.info("MCP server {} 已注册 {} 个工具", safe(config.name()), tools.size());
    } catch (RuntimeException e) {
      LOG.warn("MCP server {} 连接失败，跳过它的工具: {}", safe(config.name()), safe(e.getMessage()));
    }
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法，勿改用 Pattern/Matcher 或双反斜杠）。 */
  private static String safe(String value) {
    return value == null ? "" : value.replaceAll("[\r\n]", "_");
  }

  /** 默认工厂：stdio 子进程（command 按空白拆分：首段可执行文件、其余为 args）。 */
  private static McpSyncClient defaultClient(McpServerConfig config) {
    List<String> parts = List.of(config.command().split("\\s+"));
    ServerParameters parameters =
        ServerParameters.builder(parts.get(0))
            .args(parts.subList(1, parts.size()))
            .env(config.env())
            .build();
    return McpClient.sync(new StdioClientTransport(parameters, McpJsonDefaults.getMapper()))
        .build();
  }
}
