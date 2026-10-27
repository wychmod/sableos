package com.sableos.boot.react;

import com.sableos.core.agent.AgentService;
import com.sableos.core.context.ContextLoader;
import com.sableos.core.memory.MemoryProvider;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.prompt.PromptBuilder;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.react.ReActLoop;
import com.sableos.core.session.SessionManager;
import com.sableos.core.session.SessionStore;
import com.sableos.core.tool.ToolCatalog;
import com.sableos.core.tool.ToolExecutor;
import com.sableos.core.tool.ToolInvocationAuditor;
import com.sableos.storage.JpaSessionManager;
import com.sableos.storage.JpaToolInvocationAuditor;
import com.sableos.storage.SessionRepository;
import com.sableos.storage.ToolInvocationRepository;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ReAct 运行时装配：把 core 的循环组件与本节前向契约接成一条可运行的处理链。
 *
 * <p>{@link ToolCatalog} 由第 20 节的 {@code ToolConfiguration} 提供（{@code ToolRegistry}），本配置只消费不定义；
 * {@link MemoryProvider} 由第 22 节的 {@code MemoryConfiguration} 提供（{@code
 * MemoryServiceImpl}），本配置同样只消费不定义 —— 全容器只允许这一个 Provider 定义，在此再定义会因按名解析静默改变 {@code PromptBuilder}
 * 的注入来源。会话保存由 18 节的 {@link SessionManager}（{@code JpaSessionManager}）落地—— {@code SessionManager}
 * is-a {@link SessionStore}， 故 {@code agentService} 仍按 {@link SessionStore} 注入。审计落 {@link
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
  public SessionManager sessionManager(SessionRepository sessionRepository) {
    return new JpaSessionManager(sessionRepository);
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
}
