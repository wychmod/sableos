package com.sableos.boot.react;

import com.sableos.core.agent.AgentService;
import com.sableos.core.context.ContextLoader;
import com.sableos.core.memory.MemoryProvider;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.prompt.PromptBuilder;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.react.ReActLoop;
import com.sableos.core.session.SessionStore;
import com.sableos.core.tool.SableTool;
import com.sableos.core.tool.ToolCatalog;
import com.sableos.core.tool.ToolExecutor;
import com.sableos.core.tool.ToolInvocationAuditor;
import com.sableos.storage.InMemorySessionStore;
import com.sableos.storage.JpaToolInvocationAuditor;
import com.sableos.storage.ToolInvocationRepository;
import java.nio.file.Path;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ReAct 运行时装配：把 core 的循环组件与本节前向契约接成一条可运行的处理链。
 *
 * <p>前向契约的本节实现：{@link ToolCatalog} 为空实现（无工具环境下模型要求调工具按「未注册」失败，真实现归 20 节）、 {@link MemoryProvider} 返回
 * null（长期记忆注入归 22 节）、{@link SessionStore} 用 {@link InMemorySessionStore} （跨重启持久化归 18 节）。审计落 {@link
 * JpaToolInvocationAuditor}（原则 VI）。
 *
 * <p><b>不新增第二个 {@code TaskDecorator}</b>：全容器只允许 {@code ObservabilityConfig} 那一个（AGENTS.md §5.2）。
 */
@Configuration
public class ReactConfiguration {

  private static final Path AGENTS_DIR = Path.of(".sableos", "agents");
  private static final Path BOOTSTRAP_DIR = Path.of(".sableos");
  private static final Path SKILLS_ROOT = Path.of(".sableos", "skills");

  @Bean
  public ToolInvocationAuditor toolInvocationAuditor(
      ToolInvocationRepository toolInvocationRepository) {
    return new JpaToolInvocationAuditor(toolInvocationRepository);
  }

  @Bean
  public SessionStore sessionStore() {
    return new InMemorySessionStore();
  }

  @Bean
  public ToolCatalog toolCatalog() {
    return new EmptyToolCatalog();
  }

  @Bean
  public MemoryProvider memoryProvider() {
    return profile -> null;
  }

  @Bean
  public ContextLoader contextLoader() {
    return new ContextLoader(AGENTS_DIR, BOOTSTRAP_DIR, SKILLS_ROOT);
  }

  @Bean
  public PromptBuilder promptBuilder(
      ContextLoader contextLoader, ToolCatalog toolCatalog, MemoryProvider memoryProvider) {
    return new PromptBuilder(contextLoader, toolCatalog, memoryProvider);
  }

  @Bean
  public ToolExecutor toolExecutor(
      ToolCatalog toolCatalog, ToolInvocationAuditor toolInvocationAuditor) {
    return new ToolExecutor(toolCatalog, toolInvocationAuditor);
  }

  @Bean
  public ReActLoop reActLoop(
      ProviderService providerService, PromptBuilder promptBuilder, ToolExecutor toolExecutor) {
    return new ReActLoop(providerService, promptBuilder, toolExecutor);
  }

  @Bean
  public AgentService agentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionStore sessionStore) {
    return new AgentService(profileRegistry, reActLoop, sessionStore);
  }

  /** 空工具目录（包内私有）：本节无工具登记，模型要求调的工具一律按「未注册」失败处理。 */
  static final class EmptyToolCatalog implements ToolCatalog {

    @Override
    public List<SableTool> toolsFor(Profile profile) {
      return List.of();
    }

    @Override
    public SableTool find(String name) {
      return null;
    }
  }
}
