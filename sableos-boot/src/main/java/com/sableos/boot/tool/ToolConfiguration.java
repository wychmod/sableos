package com.sableos.boot.tool;

import com.sableos.memory.tool.MemoryTools;
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
import com.sableos.tool.sandbox.FileSandboxProperties;
import com.sableos.tool.sandbox.HttpSandboxProperties;
import com.sableos.tool.sandbox.Sandbox;
import com.sableos.tool.sandbox.ShellSandboxProperties;
import com.sableos.tool.web.DuckDuckGoSearchProvider;
import com.sableos.tool.web.SearchProvider;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * <p>{@link Sandbox} 由组件扫描注入 {@code WhitelistSandbox}（24 节唯一生产实现）；三个白名单属性记录在此显式启用。 {@code
 * UserInteraction} 按运行模式装配：{@code chat}（{@code WebApplicationType.NONE}）读控制台；{@code
 * serve/gateway}（Servlet web）无人值守，ask_user 立刻报不支持、绝不堵在服务端 {@code System.in} 上。
 */
@Configuration
@EnableConfigurationProperties({
  FileSandboxProperties.class,
  ShellSandboxProperties.class,
  HttpSandboxProperties.class
})
public class ToolConfiguration {

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
  public McpClientService mcpClientService(
      @Value("${workspace.root:.sableos}") String workspaceRoot) {
    // 27 节：MCP 配置跟随工作区根（默认 .sableos），供 sableos.root 覆盖整机工作区。
    return new McpClientService(new McpConfigLoader(Path.of(workspaceRoot, "mcp_servers.yaml")));
  }

  @Bean
  public ToolRegistry toolRegistry(
      Sandbox sandbox,
      RestClient restClient,
      UserInteraction userInteraction,
      SearchProvider searchProvider,
      McpClientService mcpClientService,
      MemoryTools memoryTools) {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new FileTools(sandbox));
    registry.registerAnnotated(new ShellTools(sandbox));
    registry.registerAnnotated(new HttpTools(sandbox, restClient));
    registry.registerAnnotated(new InteractionTools(userInteraction));
    registry.registerAnnotated(new WebSearchTools(searchProvider));
    // 空渠道表：调用即失败点名「无已注册渠道」、零请求（渠道注册表本体归后续节，届时不改 NotifyTools）
    registry.register(new NotifyTools(sandbox, new WebhookNotifyAdapter(restClient), Map.of()));
    registry.registerAnnotated(memoryTools);
    mcpClientService.connectAll(registry);
    return registry;
  }
}
