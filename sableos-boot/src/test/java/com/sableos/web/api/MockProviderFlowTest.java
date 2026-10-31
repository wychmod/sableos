package com.sableos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sableos.core.agent.AgentLoader;
import com.sableos.core.agent.AgentService;
import com.sableos.core.context.ContextLoader;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.prompt.PromptBuilder;
import com.sableos.core.provider.LlmCallAuditor;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.provider.Usage;
import com.sableos.core.react.ReActLoop;
import com.sableos.core.session.MessageRole;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.core.tool.ToolExecutor;
import com.sableos.core.tool.ToolInvocationAuditor;
import com.sableos.memory.MemoryServiceImpl;
import com.sableos.memory.store.MarkdownMemoryStore;
import com.sableos.memory.tool.MemoryTools;
import com.sableos.provider.MockChatModel;
import com.sableos.provider.ProvidersProperties;
import com.sableos.provider.SpringAiProviderServiceImpl;
import com.sableos.provider.ToolSchemaAdapter;
import com.sableos.tool.ToolRegistry;
import com.sableos.web.GlobalExceptionHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 无 key 全链路验收（27 节）：手工装配 ReActLoop + mock provider + 真实工具 / 记忆 / 会话。
 *
 * <p>与 {@link com.sableos.boot.MockAgentE2ETest} 的分工：这里不启动 Spring，几秒内跑完，断言"ReAct 恰两轮、save_memory
 * 恰一次、MEMORY.md 真写入、会话历史完整、审计各一条"，再用同一批服务起 {@code /sessions}、{@code /memory}、{@code /tools}
 * 三个端点，确认管理台读得到这条链路留下的数据。
 */
class MockProviderFlowTest {

  private static final String PROFILE = "mock-flow-agent";
  private static final String SESSION_ID = "web:anonymous:" + PROFILE;
  private static final String FACT = "我喜欢喝美式咖啡";

  @Test
  @DisplayName("mock全链路_两轮ReAct_记忆落盘_会话完整_审计与查询端点对账")
  void mockFlow_twoRounds_leavesConsistentTraces() throws Exception {
    Path root = tempWorkspace();
    writeAgent(root.resolve("agents").resolve(PROFILE).resolve("AGENT.md"));

    ProfileRegistry profileRegistry =
        new AgentLoader(root.resolve("agents"), Set.of("mock")).loadAll();
    assertThat(profileRegistry.exists(PROFILE)).isTrue();

    InMemorySessionManager sessionManager = new InMemorySessionManager();
    MemoryServiceImpl memoryService = new MemoryServiceImpl(new MarkdownMemoryStore(root));
    ToolRegistry toolRegistry = new ToolRegistry();
    toolRegistry.registerAnnotated(new MemoryTools(memoryService));

    RecordingLlmAuditor llmAuditor = new RecordingLlmAuditor();
    RecordingToolAuditor toolAuditor = new RecordingToolAuditor();
    ProviderService providerService =
        new SpringAiProviderServiceImpl(
            Map.of("mock", new MockChatModel()),
            new ProvidersProperties(
                List.of(new ProvidersProperties.Provider("mock", "mock", null, null))),
            new ToolSchemaAdapter(),
            llmAuditor);
    PromptBuilder promptBuilder =
        new PromptBuilder(
            new ContextLoader(root.resolve("agents"), root, root.resolve("skills")),
            toolRegistry,
            memoryService);
    ReActLoop reActLoop =
        new ReActLoop(providerService, promptBuilder, new ToolExecutor(toolRegistry, toolAuditor));
    AgentService agentService = new AgentService(profileRegistry, reActLoop, sessionManager);

    AsyncTaskExecutor directExecutor = Runnable::run;
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new SessionApiController(
                    sessionManager,
                    profileRegistry,
                    new AgentCallExecutor(agentService, directExecutor)),
                new MemoryApiController(profileRegistry, memoryService),
                new ToolApiController(toolRegistry))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    mvc.perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":\"" + PROFILE + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(SESSION_ID));

    mvc.perform(
            post("/api/v1/sessions/" + SESSION_ID + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"记住：" + FACT + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reply").isNotEmpty());

    mvc.perform(get("/api/v1/sessions/" + SESSION_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.messages.length()").value(4))
        .andExpect(jsonPath("$.data.messages[0].role").value("USER"))
        .andExpect(jsonPath("$.data.messages[1].role").value("ASSISTANT"))
        .andExpect(jsonPath("$.data.messages[2].role").value("TOOL"))
        .andExpect(jsonPath("$.data.messages[2].toolName").value("save_memory"))
        .andExpect(jsonPath("$.data.messages[2].success").value(true))
        .andExpect(jsonPath("$.data.messages[3].role").value("ASSISTANT"));

    mvc.perform(get("/api/v1/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value(SESSION_ID))
        .andExpect(jsonPath("$.data[0].messageCount").value(4));

    mvc.perform(get("/api/v1/memory").param("profile", PROFILE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").value(containsString("美式咖啡")));

    mvc.perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[?(@.name == 'save_memory')]").exists());

    // ReAct 恰两轮、工具恰一次；MEMORY.md 真写入、会话历史完整。
    assertThat(llmAuditor.records).hasSize(2);
    assertThat(llmAuditor.records).allMatch(RecordingLlmAuditor.Record::success);
    assertThat(toolAuditor.records).hasSize(1);
    assertThat(toolAuditor.records.get(0).toolName()).isEqualTo("save_memory");
    assertThat(toolAuditor.records.get(0).success()).isTrue();

    String memoryFile =
        Files.readString(root.resolve("memory").resolve(PROFILE).resolve("MEMORY.md"));
    assertThat(memoryFile).contains(FACT);

    Session session = sessionManager.get(SESSION_ID).orElseThrow();
    assertThat(session.messages()).hasSize(4);
    assertThat(session.messages().get(0).role()).isEqualTo(MessageRole.USER);
  }

  private static Path tempWorkspace() {
    try {
      Path path = Files.createTempDirectory("sableos-mock-flow-");
      path.toFile().deleteOnExit();
      return path;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeAgent(Path agentFile) {
    String markdown =
        "---\n"
            + "name: mock-flow-agent\n"
            + "description: 27 节手工装配全链路测试 Agent\n"
            + "provider:\n"
            + "  name: mock\n"
            + "  model: mock-model\n"
            + "tools:\n"
            + "  - save_memory\n"
            + "settings:\n"
            + "  max_iterations: 5\n"
            + "  max_history_turns: 20\n"
            + "---\n\n"
            + "你是 mock-flow-agent，只用于验证 SableOS 全链路。\n";
    try {
      Files.createDirectories(agentFile.getParent());
      Files.writeString(agentFile, markdown, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 进程内会话存储：手工装配不引入 JPA，但接口语义与 {@code JpaSessionManager} 一致。 */
  private static final class InMemorySessionManager implements SessionManager {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public Session getOrCreate(String channel, String userId, String profileName) {
      String id = SessionManager.sessionId(channel, userId, profileName);
      return sessions.computeIfAbsent(id, key -> new Session(key, profileName, channel, userId));
    }

    @Override
    public Optional<Session> get(String sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public boolean archive(String sessionId) {
      return sessions.containsKey(sessionId);
    }

    @Override
    public List<Session> listRecent(int limit, String status) {
      if (limit <= 0) {
        return List.of();
      }
      return sessions.values().stream()
          .filter(session -> status == null || status.isBlank() || status.equals(session.status()))
          .sorted(Comparator.comparing(Session::lastActiveAt).reversed())
          .limit(limit)
          .toList();
    }

    @Override
    public void save(Session session) {
      sessions.put(session.id(), session);
    }
  }

  private static final class RecordingLlmAuditor implements LlmCallAuditor {

    private final List<Record> records = new CopyOnWriteArrayList<>();

    @Override
    public void record(
        String sessionId,
        String provider,
        String model,
        Usage usage,
        boolean success,
        String errorMessage,
        long durationMs) {
      records.add(new Record(provider, model, success));
    }

    private record Record(String provider, String model, boolean success) {}
  }

  private static final class RecordingToolAuditor implements ToolInvocationAuditor {

    private final List<Record> records = new CopyOnWriteArrayList<>();

    @Override
    public void record(
        String sessionId,
        String toolName,
        String inputJson,
        String resultJson,
        boolean success,
        String errorMessage,
        long durationMs) {
      records.add(new Record(toolName, success));
    }

    private record Record(String toolName, boolean success) {}
  }
}
