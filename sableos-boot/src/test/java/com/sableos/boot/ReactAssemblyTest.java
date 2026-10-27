package com.sableos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sableos.cli.SableOsRuntime;
import com.sableos.core.agent.AgentService;
import com.sableos.core.context.ContextLoader;
import com.sableos.core.memory.MemoryProvider;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.prompt.PromptBuilder;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.provider.ToolCallRequest;
import com.sableos.core.react.ReActLoop;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionStore;
import com.sableos.core.tool.ToolCatalog;
import com.sableos.core.tool.ToolExecutor;
import com.sableos.core.tool.ToolInvocationAuditor;
import com.sableos.memory.MemoryScope;
import com.sableos.memory.MemoryService;
import com.sableos.memory.MemoryServiceImpl;
import com.sableos.storage.JpaSessionManager;
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
 * ReAct 装配层验收（零 key）：空 providers 清单下上下文可升起、九个运行时 bean 齐备、内置工具注册表接通、长期记忆经 PromptBuilder 进入本轮上下文（22
 * 节），并以脚本化 Provider 驱动一轮端到端（真循环 + 真审计落库）。数据源与记忆工作区均指向临时目录，避免污染仓库（真 Flyway 建表随之验证 V2 与 V4）。
 */
@SpringBootTest(classes = SableOsRuntime.class)
class ReactAssemblyTest {

  private static final String DB_URL = "jdbc:sqlite:" + tempDbPath();
  private static final String WORKSPACE_DIR = tempWorkspacePath();

  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DB_URL);
    registry.add("workspace.root", () -> WORKSPACE_DIR);
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

  private static String tempWorkspacePath() {
    try {
      Path path = Files.createTempDirectory("sableos-react-workspace-");
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
  @Autowired private MemoryService memoryService;
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
    assertThat(sessionStore).isInstanceOf(JpaSessionManager.class);
    assertThat(toolCatalog).isNotNull();
    assertThat(memoryProvider).isNotNull();
    assertThat(contextLoader).isNotNull();
    assertThat(promptBuilder).isNotNull();
    assertThat(toolExecutor).isNotNull();
    assertThat(reActLoop).isNotNull();
    assertThat(agentService).isNotNull();
  }

  @Test
  @DisplayName("装配_工具注册表接通且记忆Provider落在MemoryService")
  void toolRegistryWired_memoryProviderWiredToService() {
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

    // 20/22 节：注册表已接通，内置工具（含长期记忆工具）查得到；空 tools 声明的 Profile 过滤后仍为空
    assertThat(toolCatalog.find("http_get")).isNotNull();
    assertThat(toolCatalog.find("save_memory")).isNotNull();
    assertThat(toolCatalog.find("recall_memory")).isNotNull();
    assertThat(toolCatalog.toolsFor(profile)).isEmpty();
    // 22 节：PromptBuilder 的 MemoryProvider 必须是 MemoryServiceImpl（16 节的 null 占位已删）
    assertThat(memoryProvider).isInstanceOf(MemoryServiceImpl.class);
    assertThat(memoryProvider.memorySection(profile)).isBlank();
  }

  @Test
  @DisplayName("装配_长期记忆经PromptBuilder进入本轮上下文")
  void memoryReachesPromptBuilder() {
    Profile profile =
        new Profile(
            "ops-memory",
            null,
            null,
            new Profile.ProviderRef("deepseek", "deepseek-chat", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(5, 20));
    memoryService.remember("用户偏好 Java（装配断言）", MemoryScope.CORE, profile);
    Session session = new Session("s-mem", "ops-memory", "cli", "u-1");

    ProviderRequest request = promptBuilder.build(session, profile);

    assertThat(request.content()).contains("用户偏好 Java（装配断言）");
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
            new ProviderResponse(
                "", List.of(new ToolCallRequest("c1", "weather_query", "{}")), null))
        .thenReturn(new ProviderResponse("完成", List.of(), null));

    Session session = new Session("s-1", "ops", "cli", "u-1");
    String reply = agentService.process(session, "看看今天天气");

    assertThat(reply).isEqualTo("完成");
    List<ToolInvocation> rows = invocationsOfSession("s-1");
    assertThat(rows).hasSize(1);
    ToolInvocation row = rows.get(0);
    assertThat(row.getSessionId()).isEqualTo("s-1");
    assertThat(row.getToolName()).isEqualTo("weather_query");
    assertThat(row.isSuccess()).isFalse();
    assertThat(row.getErrorMessage()).contains("未注册的工具：weather_query");
    assertThat(row.getTraceId()).isNotBlank();
  }

  @Test
  @DisplayName("装配_零key驱动注册工具成功调用并落成功审计")
  void process_registeredToolSuccess_landsSuccessAudit() throws IOException {
    Path tempFile = Files.createTempFile("sableos-tool-ok-", ".txt");
    tempFile.toFile().deleteOnExit();
    Files.writeString(tempFile, "hello-tool");
    // Windows 反斜杠会破坏 JSON，统一转正斜杠（JDK 在 Windows 也接受）
    String path = tempFile.toAbsolutePath().toString().replace('\\', '/');

    Profile profile =
        new Profile(
            "ops-reader",
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
            new ProviderResponse(
                "",
                List.of(new ToolCallRequest("c1", "read_file", "{\"path\":\"" + path + "\"}")),
                null))
        .thenReturn(new ProviderResponse("读到了", List.of(), null));

    Session session = new Session("s-2", "ops-reader", "cli", "u-1");
    String reply = agentService.process(session, "读这个文件");

    assertThat(reply).isEqualTo("读到了");
    List<ToolInvocation> rows = invocationsOfSession("s-2");
    assertThat(rows).hasSize(1);
    ToolInvocation row = rows.get(0);
    assertThat(row.getToolName()).isEqualTo("read_file");
    assertThat(row.isSuccess()).isTrue();
    assertThat(row.getTraceId()).isNotBlank();
  }

  private List<ToolInvocation> invocationsOfSession(String sessionId) {
    return toolInvocationRepository.findAll().stream()
        .filter(row -> sessionId.equals(row.getSessionId()))
        .toList();
  }
}
