package com.sableos.boot.tool;

import com.sableos.tool.ToolRegistry;
import com.sableos.tool.builtin.FileTools;
import com.sableos.tool.builtin.HttpTools;
import com.sableos.tool.builtin.InteractionTools;
import com.sableos.tool.builtin.NotifyTools;
import com.sableos.tool.builtin.ShellTools;
import com.sableos.tool.builtin.WebSearchTools;
import com.sableos.tool.interaction.ConsoleUserInteraction;
import com.sableos.tool.interaction.UnsupportedUserInteraction;
import com.sableos.tool.interaction.UserInteraction;
import com.sableos.tool.mcp.McpClientService;
import com.sableos.tool.mcp.McpConfigLoader;
import com.sableos.tool.notify.WebhookNotifyAdapter;
import com.sableos.tool.sandbox.PermissiveSandbox;
import com.sableos.tool.sandbox.Sandbox;
import com.sableos.tool.web.DuckDuckGoSearchProvider;
import com.sableos.tool.web.SearchProvider;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tool 装配（第 20 节）：把统一注册表接进运行时，替换 17 节的空工具目录（FR-007）。
 *
 * <p>{@link ToolRegistry} 同时作为 {@code ToolCatalog} bean 供 {@code PromptBuilder}/{@code
 * ToolExecutor} 注入（其嵌套 {@code EmptyToolCatalog} 已从 {@code ReactConfiguration} 删除）。三来源统一注册：五个内置工具走
 * {@code @Tool} 注解管道、 {@code NotifyTools} 直接实现（本节空渠道表）、MCP 走 {@code connectAll}（缺 {@code
 * mcp_servers.yaml} → 零 server、零副作用）。
 *
 * <p>{@link Sandbox} 为 {@link PermissiveSandbox}（临时放行、必 WARN 留痕），应用层白名单实现归 23/24 节，届时只换本 bean。
 * {@code UserInteraction} 按运行模式装配：{@code chat}（{@code WebApplicationType.NONE}）读控制台；{@code
 * serve/gateway}（Servlet web）无人值守，ask_user 立刻报不支持、绝不堵在服务端 {@code System.in} 上。
 */
@Configuration
public class ToolConfiguration {

  private static final Path MCP_CONFIG = Path.of(".sableos", "mcp_servers.yaml");

  @Bean
  public Sandbox sandbox() {
    return new PermissiveSandbox();
  }

  @Bean
  public RestClient restClient() {
    return RestClient.builder().build();
  }

  /**
   * chat（{@code WebApplicationType.NONE}）读控制台；serve/gateway（Servlet web）无人值守，ask_user
   * 必须立刻报不支持，绝不能堵在服务端 {@code System.in} 上。
   */
  @Bean
  public UserInteraction userInteraction(ApplicationContext applicationContext) {
    return resolveUserInteraction(applicationContext instanceof WebApplicationContext);
  }

  /** 可见给单测：Servlet web → 不支持交互；否则 → 控制台。 */
  static UserInteraction resolveUserInteraction(boolean servletWeb) {
    if (servletWeb) {
      return new UnsupportedUserInteraction();
    }
    return new ConsoleUserInteraction();
  }

  @Bean
  public SearchProvider searchProvider(Sandbox sandbox, RestClient restClient) {
    return new DuckDuckGoSearchProvider(sandbox, restClient);
  }

  @Bean
  public McpClientService mcpClientService() {
    return new McpClientService(new McpConfigLoader(MCP_CONFIG));
  }

  @Bean
  public ToolRegistry toolRegistry(
      Sandbox sandbox,
      RestClient restClient,
      UserInteraction userInteraction,
      SearchProvider searchProvider,
      McpClientService mcpClientService) {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new FileTools(sandbox));
    registry.registerAnnotated(new ShellTools(sandbox));
    registry.registerAnnotated(new HttpTools(sandbox, restClient));
    registry.registerAnnotated(new InteractionTools(userInteraction));
    registry.registerAnnotated(new WebSearchTools(searchProvider));
    // 空渠道表：调用即失败点名「无已注册渠道」、零请求（渠道注册表本体归后续节，届时不改 NotifyTools）
    registry.register(new NotifyTools(sandbox, new WebhookNotifyAdapter(restClient), Map.of()));
    mcpClientService.connectAll(registry);
    return registry;
  }
}
