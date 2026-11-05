package com.sableos.channel.cli;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.cli.SableOsRuntime;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.provider.ProviderFailureException;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.provider.ToolCallRequest;
import com.sableos.core.tool.ToolExecutor;
import com.sableos.core.tool.ToolResult;
import com.sableos.storage.LlmCall;
import com.sableos.storage.LlmCallRepository;
import com.sableos.storage.ToolInvocation;
import com.sableos.storage.ToolInvocationRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 人推链路真集成验收（27 节，{@code @Tag("integration")}）：真 key、真 HTTP、真 SQLite。
 *
 * <p>三根支柱（对话答得出 / 记忆查得到 / 工具查得到）+ 三类失败记账 + 三面同源。CI 与日常构建默认跳过，手动跑： {@code DEEPSEEK_API_KEY=xxx mvn
 * -pl sableos-boot -am test -DexcludedGroups= -Dgroups=integration -Dtest=HumanTriggerFlowIT}。
 *
 * <p>放在 {@code com.sableos.channel.cli} 包：三面同源需要直接驱动 {@link CliChannel} 的脚本化 {@code
 * run(profile,user,in,out)}（包内可见），以证明 CLI 与 REST 写读的是同一份 Session / 审计。
 */
@SpringBootTest(
    classes = SableOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class HumanTriggerFlowIT {

  private static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path WORKSPACE = tempWorkspace();
  private static final HttpServer WEATHER_SERVER = startWeatherServer();
  private static final int WEATHER_PORT = WEATHER_SERVER.getAddress().getPort();

  static {
    writeAgent(
        WORKSPACE.resolve("agents").resolve("weather-agent").resolve("AGENT.md"),
        "weather-agent",
        "deepseek",
        "deepseek-chat",
        "http_get");
    writeAgent(
        WORKSPACE.resolve("agents").resolve("memory-agent").resolve("AGENT.md"),
        "memory-agent",
        "deepseek",
        "deepseek-chat",
        "save_memory");
    writeAgent(
        WORKSPACE.resolve("agents").resolve("mock-agent").resolve("AGENT.md"),
        "mock-agent",
        "mock",
        "mock-model",
        "save_memory");
  }

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("workspace.root", WORKSPACE::toString);
    registry.add("sableos.providers[0].name", () -> "deepseek");
    registry.add("sableos.providers[0].type", () -> "openai");
    registry.add("sableos.providers[0].base-url", () -> "https://api.deepseek.com");
    registry.add(
        "sableos.providers[0].api-key",
        () -> (API_KEY == null || API_KEY.isBlank()) ? "placeholder-key" : API_KEY);
    registry.add("sableos.providers[1].name", () -> "mock");
    registry.add("sableos.providers[1].type", () -> "mock");
    registry.add("sableos.providers[2].name", () -> "broken");
    registry.add("sableos.providers[2].type", () -> "openai");
    registry.add("sableos.providers[2].base-url", () -> "http://127.0.0.1:1");
    registry.add("sableos.providers[2].api-key", () -> "test-key");
    registry.add("http.allowed_domains[0]", () -> "127.0.0.1");
  }

  @AfterAll
  static void stopWeatherServer() {
    WEATHER_SERVER.stop(0);
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private CliChannel cliChannel;
  @Autowired private ToolExecutor toolExecutor;
  @Autowired private ProviderService providerService;
  @Autowired private ProfileRegistry profileRegistry;
  @Autowired private LlmCallRepository llmCallRepository;
  @Autowired private ToolInvocationRepository toolInvocationRepository;

  @Test
  @DisplayName("支柱一_天气对话走完想查答记账_REST与审计不多不少")
  void pillarOne_weatherConversation_endToEnd() throws Exception {
    assumeTrue(hasKey(), "未设置 DEEPSEEK_API_KEY，跳过真集成验收");

    JsonNode created = post("/api/v1/sessions", Map.of("profile", "weather-agent"));
    String sessionId = created.path("data").path("id").asText();
    assertThat(sessionId).isEqualTo("web:anonymous:weather-agent");

    String prompt =
        "请先调用 http_get 工具访问 http://127.0.0.1:" + WEATHER_PORT + "/weather 获取北京天气，再根据结果给出一句穿衣建议。";
    JsonNode reply = post("/api/v1/sessions/" + sessionId + "/messages", Map.of("content", prompt));
    assertThat(reply.path("data").path("reply").asText()).isNotBlank();

    JsonNode messages = get("/api/v1/sessions/" + sessionId).path("data").path("messages");
    assertThat(messages.size()).isEqualTo(4);
    assertThat(messages.get(0).path("role").asText()).isEqualTo("USER");
    assertThat(messages.get(1).path("role").asText()).isEqualTo("ASSISTANT");
    assertThat(messages.get(2).path("role").asText()).isEqualTo("TOOL");
    assertThat(messages.get(2).path("toolName").asText()).isEqualTo("http_get");
    assertThat(messages.get(2).path("success").asBoolean()).isTrue();
    assertThat(messages.get(3).path("role").asText()).isEqualTo("ASSISTANT");
    assertThat(messages.get(3).path("content").asText()).isNotBlank();

    JsonNode list = get("/api/v1/sessions").path("data");
    assertThat(findById(list, sessionId)).isPresent();

    List<LlmCall> llmCalls = llmCallsOf(sessionId);
    assertThat(llmCalls).hasSize(2);
    assertThat(llmCalls).allMatch(LlmCall::isSuccess);
    List<ToolInvocation> toolCalls = toolCallsOf(sessionId);
    assertThat(toolCalls).hasSize(1);
    assertThat(toolCalls.get(0).getToolName()).isEqualTo("http_get");
    assertThat(toolCalls.get(0).isSuccess()).isTrue();
  }

  @Test
  @DisplayName("支柱二_记忆写得进读得出且跨会话可用")
  void pillarTwo_memoryCanBeWrittenAndRecalled() throws Exception {
    assumeTrue(hasKey(), "未设置 DEEPSEEK_API_KEY，跳过真集成验收");

    String firstSession = "web:anonymous:memory-agent";
    post("/api/v1/sessions", Map.of("profile", "memory-agent"));
    post(
        "/api/v1/sessions/" + firstSession + "/messages",
        Map.of("content", "请调用 save_memory 工具，把“我在北京，怕冷”写入长期记忆，然后回复已记住。"));

    JsonNode memory = get("/api/v1/agents/memory-agent/memory").path("data");
    assertThat(memory.path("content").asText()).contains("北京");

    JsonNode second =
        post("/api/v1/sessions", Map.of("profile", "memory-agent", "userId", "second"));
    String secondSession = second.path("data").path("id").asText();
    JsonNode reply =
        post("/api/v1/sessions/" + secondSession + "/messages", Map.of("content", "我在哪个城市？穿什么合适？"));
    assertThat(reply.path("data").path("reply").asText()).contains("北京");
  }

  @Test
  @DisplayName("支柱三_工具清单对外可见且含关键工具")
  void pillarThree_toolsAreListed() throws Exception {
    JsonNode tools = get("/api/v1/tools").path("data");
    assertThat(tools.size()).isGreaterThan(0);
    assertThat(toolNames(tools)).contains("http_get", "save_memory", "recall_memory");
  }

  @Test
  @DisplayName("支柱三_对外工具清单覆盖每个Profile声明的工具集")
  void pillarThree_registryCoversEveryProfileToolset() throws Exception {
    List<String> listed = toolNames(get("/api/v1/tools").path("data"));
    for (String profileName : List.of("weather-agent", "memory-agent", "mock-agent")) {
      assertThat(profileRegistry.exists(profileName)).as("Profile %s 应已加载", profileName).isTrue();
      Profile profile = requireNonNull(profileRegistry.get(profileName));
      assertThat(listed).as("Profile %s 声明的工具都应在对外清单里", profileName).containsAll(profile.tools());
    }
  }

  @Test
  @DisplayName("失败路径_沙箱拦截工具异常与Provider故障都留success=false且系统不崩")
  void failurePaths_allAuditedWithoutCrashing() throws Exception {
    ToolResult sandboxBlocked =
        toolExecutor.execute(
            "fail-sandbox",
            new ToolCallRequest("c1", "http_get", "{\"url\":\"http://blocked.example/\"}"));
    assertThat(sandboxBlocked.success()).isFalse();
    assertThat(toolCallsOf("fail-sandbox"))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getToolName()).isEqualTo("http_get");
              assertThat(row.isSuccess()).isFalse();
            });

    ToolResult toolThrew =
        toolExecutor.execute(
            "fail-tool",
            new ToolCallRequest("c2", "http_get", "{\"url\":\"http://127.0.0.1:1/\"}"));
    assertThat(toolThrew.success()).isFalse();
    assertThat(toolCallsOf("fail-tool"))
        .singleElement()
        .satisfies(row -> assertThat(row.isSuccess()).isFalse());

    Profile broken =
        new Profile(
            "broken-agent",
            null,
            null,
            new Profile.ProviderRef("broken", "broken-model", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(1, 20));
    assertThatThrownBy(
            () ->
                providerService.chat("fail-provider", broken, new ProviderRequest("你好", List.of())))
        .isInstanceOf(ProviderFailureException.class);
    assertThat(llmCallsOf("fail-provider"))
        .singleElement()
        .satisfies(row -> assertThat(row.isSuccess()).isFalse());
  }

  @Test
  @DisplayName("三面同源_CLI写入的会话从REST列表与详情读到同一份数据")
  void threeFace_cliAndRestSeeSameSession() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    BufferedReader input = new BufferedReader(new StringReader("记住：我喜欢喝美式咖啡\n/quit\n"));
    cliChannel.run(
        "mock-agent", "tester", input, new PrintStream(output, true, StandardCharsets.UTF_8));

    String cliSessionId = "cli:tester:mock-agent";
    JsonNode messages = get("/api/v1/sessions/" + cliSessionId).path("data").path("messages");
    assertThat(messages.size()).isEqualTo(4);
    assertThat(messages.get(2).path("toolName").asText()).isEqualTo("save_memory");
    assertThat(messages.get(3).path("role").asText()).isEqualTo("ASSISTANT");

    JsonNode list = get("/api/v1/sessions").path("data");
    assertThat(findById(list, cliSessionId)).isPresent();
    assertThat(get("/api/v1/agents/mock-agent/memory").path("data").path("content").asText())
        .contains("美式咖啡");
  }

  private static boolean hasKey() {
    return API_KEY != null && !API_KEY.isBlank();
  }

  private JsonNode post(String path, Object body) throws IOException {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    assertThat(response.getStatusCode().is2xxSuccessful())
        .as("POST %s 应成功，实际 %s，body=%s", path, response.getStatusCode(), response.getBody())
        .isTrue();
    return MAPPER.readTree(response.getBody());
  }

  private JsonNode get(String path) throws IOException {
    ResponseEntity<String> response = rest.getForEntity(path, String.class);
    assertThat(response.getStatusCode().is2xxSuccessful())
        .as("GET %s 应成功，实际 %s，body=%s", path, response.getStatusCode(), response.getBody())
        .isTrue();
    return MAPPER.readTree(response.getBody());
  }

  private List<LlmCall> llmCallsOf(String sessionId) {
    return llmCallRepository.findAll().stream()
        .filter(row -> sessionId.equals(row.getSessionId()))
        .toList();
  }

  private List<ToolInvocation> toolCallsOf(String sessionId) {
    return toolInvocationRepository.findAll().stream()
        .filter(row -> sessionId.equals(row.getSessionId()))
        .toList();
  }

  private static Optional<JsonNode> findById(JsonNode array, String id) {
    for (JsonNode node : array) {
      if (id.equals(node.path("id").asText())) {
        return Optional.of(node);
      }
    }
    return Optional.empty();
  }

  private static List<String> toolNames(JsonNode tools) {
    return StreamSupport.stream(tools.spliterator(), false)
        .map(node -> node.path("name").asText())
        .toList();
  }

  private static HttpServer startWeatherServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/weather",
          exchange -> {
            byte[] body =
                "{\"city\":\"北京\",\"weather\":\"晴\",\"temperature\":8}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path tempWorkspace() {
    try {
      Path path = Files.createTempDirectory("sableos-human-trigger-");
      path.toFile().deleteOnExit();
      return path;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeAgent(
      Path agentFile, String name, String provider, String model, String tool) {
    String markdown =
        "---\n"
            + "name: "
            + name
            + "\n"
            + "description: 27 节人推链路集成验收 Agent\n"
            + "provider:\n"
            + "  name: "
            + provider
            + "\n"
            + "  model: "
            + model
            + "\n"
            + "tools:\n"
            + "  - "
            + tool
            + "\n"
            + "settings:\n"
            + "  max_iterations: 5\n"
            + "  max_history_turns: 20\n"
            + "---\n\n"
            + "你是 "
            + name
            + "，按用户指令使用工具并给出简洁答复。\n";
    try {
      Files.createDirectories(agentFile.getParent());
      Files.writeString(agentFile, markdown, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
