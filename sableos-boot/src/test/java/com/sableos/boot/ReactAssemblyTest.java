package com.sableos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sableos.core.agent.AgentService;
import com.sableos.core.context.ContextLoader;
import com.sableos.core.memory.MemoryProvider;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.prompt.PromptBuilder;
import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.provider.ToolCallRequest;
import com.sableos.core.react.ReActLoop;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionStore;
import com.sableos.core.tool.ToolCatalog;
import com.sableos.core.tool.ToolExecutor;
import com.sableos.core.tool.ToolInvocationAuditor;
import com.sableos.storage.InMemorySessionStore;
import com.sableos.storage.JpaToolInvocationAuditor;
import com.sableos.storage.ToolInvocation;
import com.sableos.storage.ToolInvocationRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * ReAct 装配层验收（零 key）：空 providers 清单下上下文可升起、九个运行时 bean 齐备、空工具目录与无记忆的前向实现生效， 并以脚本化 Provider
 * 驱动一轮端到端（真循环 + 真审计落库）。数据源指向临时 SQLite，避免污染仓库（真 Flyway 建表随之验证 V2）。
 */
@SpringBootTest
class ReactAssemblyTest {

  private static final String DB_URL = "jdbc:sqlite:" + tempDbPath();

  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DB_URL);
  }

  private static String tempDbPath() {
    try {
      Path path = Files.createTempFile("sableos-react-assembly-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private ToolInvocationAuditor toolInvocationAuditor;
  @Autowired private SessionStore sessionStore;
  @Autowired private ToolCatalog toolCatalog;
  @Autowired private MemoryProvider memoryProvider;
  @Autowired private ContextLoader contextLoader;
  @Autowired private PromptBuilder promptBuilder;
  @Autowired private ToolExecutor toolExecutor;
  @Autowired private ReActLoop reActLoop;
  @Autowired private AgentService agentService;
  @Autowired private ProfileRegistry profileRegistry;
  @Autowired private ToolInvocationRepository toolInvocationRepository;

  /** 替换真 Provider（零 key 下无可用 ChatModel），以脚本化响应驱动一轮端到端。 */
  @MockitoBean private ProviderService providerService;

  @Test
  @DisplayName("装配_零key可升起且九个bean齐备")
  void contextLoads_withReActBeans() {
    assertThat(toolInvocationAuditor).isInstanceOf(JpaToolInvocationAuditor.class);
    assertThat(sessionStore).isInstanceOf(InMemorySessionStore.class);
    assertThat(toolCatalog).isNotNull();
    assertThat(memoryProvider).isNotNull();
    assertThat(contextLoader).isNotNull();
    assertThat(promptBuilder).isNotNull();
    assertThat(toolExecutor).isNotNull();
    assertThat(reActLoop).isNotNull();
    assertThat(agentService).isNotNull();
  }

  @Test
  @DisplayName("装配_空工具目录与无记忆的前向实现生效")
  void forwardContracts_areInert() {
    Profile profile =
        new Profile(
            "ops",
            null,
            null,
            new Profile.ProviderRef("deepseek", "deepseek-chat", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(null, null));

    assertThat(toolCatalog.toolsFor(profile)).isEmpty();
    assertThat(toolCatalog.find("http_get")).isNull();
    assertThat(memoryProvider.memorySection(profile)).isNull();
  }

  @Test
  @DisplayName("装配_零key驱动一轮并落一条失败工具审计")
  void process_oneRound_landsFailedToolAudit() {
    Profile profile =
        new Profile(
            "ops",
            null,
            null,
            new Profile.ProviderRef("deepseek", "deepseek-chat", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(5, 20));
    profileRegistry.register(profile);
    when(providerService.chat(anyString(), any(), any()))
        .thenReturn(
            new ProviderResponse("", List.of(new ToolCallRequest("c1", "http_get", "{}")), null))
        .thenReturn(new ProviderResponse("完成", List.of(), null));

    Session session = new Session("s-1", "ops", "cli", "u-1");
    String reply = agentService.process(session, "看看今天天气");

    assertThat(reply).isEqualTo("完成");
    List<ToolInvocation> rows = toolInvocationRepository.findAll();
    assertThat(rows).hasSize(1);
    ToolInvocation row = rows.get(0);
    assertThat(row.getSessionId()).isEqualTo("s-1");
    assertThat(row.getToolName()).isEqualTo("http_get");
    assertThat(row.isSuccess()).isFalse();
    assertThat(row.getErrorMessage()).contains("未注册的工具：http_get");
    assertThat(row.getTraceId()).isNotBlank();
  }
}
