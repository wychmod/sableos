package com.sableos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.cli.SableOsRuntime;
import com.sableos.storage.LlmCall;
import com.sableos.storage.LlmCallRepository;
import com.sableos.storage.ToolInvocation;
import com.sableos.storage.ToolInvocationRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
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
 * 整机无 key 全链路验收（27 节）：真 HTTP 端口 + 真 SQLite，provider 覆盖成 {@code mock}。
 *
 * <p>从测试里按人手动操作的顺序走一遍：建会话 → 发"记住…" → 查会话 / 列表 / 记忆 / 工具 → 查库对账。为让测试 hermetic，工作区根指向临时目录（{@code
 * workspace.root}，生产可用系统属性 {@code sableos.root} 同样覆盖）。
 */
@SpringBootTest(
    classes = SableOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockAgentE2ETest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path WORKSPACE = tempWorkspace();
  private static final String PROFILE = "mock-agent";
  private static final String SESSION_ID = "web:anonymous:" + PROFILE;
  private static final String FACT = "我喜欢喝美式咖啡";

  static {
    writeMockAgent(WORKSPACE.resolve("agents").resolve(PROFILE).resolve("AGENT.md"));
  }

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("workspace.root", WORKSPACE::toString);
    registry.add("sableos.providers[0].name", () -> "mock");
    registry.add("sableos.providers[0].type", () -> "mock");
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private LlmCallRepository llmCallRepository;
  @Autowired private ToolInvocationRepository toolInvocationRepository;

  @Test
  @DisplayName("整机mock链路_建会话发消息_会话列表记忆工具审计全部对账")
  void fullFlow_overHttp_leavesExactlyExpectedTraces() throws Exception {
    JsonNode created = post("/api/v1/sessions", Map.of("profile", PROFILE));
    assertThat(created.path("data").path("id").asText()).isEqualTo(SESSION_ID);

    JsonNode reply = post("/api/v1/sessions/" + SESSION_ID + "/messages", Map.of("content", FACT));
    assertThat(reply.path("data").path("reply").asText()).isNotBlank();

    JsonNode session = get("/api/v1/sessions/" + SESSION_ID).path("data");
    JsonNode messages = session.path("messages");
    assertThat(messages.size()).isEqualTo(4);
    assertThat(messages.get(0).path("role").asText()).isEqualTo("USER");
    assertThat(messages.get(0).path("content").asText()).contains(FACT);
    assertThat(messages.get(1).path("role").asText()).isEqualTo("ASSISTANT");
    assertThat(messages.get(2).path("role").asText()).isEqualTo("TOOL");
    assertThat(messages.get(2).path("toolName").asText()).isEqualTo("save_memory");
    assertThat(messages.get(2).path("success").asBoolean()).isTrue();
    assertThat(messages.get(3).path("role").asText()).isEqualTo("ASSISTANT");

    JsonNode list = get("/api/v1/sessions").path("data");
    JsonNode summary =
        findById(list, SESSION_ID).orElseThrow(() -> new AssertionError("会话未出现在列表里"));
    assertThat(summary.path("messageCount").asInt()).isEqualTo(4);
    assertThat(summary.has("messages")).isFalse();

    JsonNode memory = get("/api/v1/agents/" + PROFILE + "/memory").path("data");
    assertThat(memory.path("content").asText()).contains("美式咖啡");

    JsonNode tools = get("/api/v1/tools").path("data");
    assertThat(tools.size()).isGreaterThan(0);
    assertThat(toolNames(tools)).contains("save_memory", "recall_memory", "http_get");

    List<LlmCall> llmCalls = llmCallsOf(SESSION_ID);
    assertThat(llmCalls).hasSize(2);
    assertThat(llmCalls).allMatch(LlmCall::isSuccess);
    List<ToolInvocation> toolCalls = toolCallsOf(SESSION_ID);
    assertThat(toolCalls).hasSize(1);
    assertThat(toolCalls.get(0).getToolName()).isEqualTo("save_memory");
    assertThat(toolCalls.get(0).isSuccess()).isTrue();
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

  private static Path tempWorkspace() {
    try {
      Path path = Files.createTempDirectory("sableos-mock-e2e-");
      path.toFile().deleteOnExit();
      return path;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeMockAgent(Path agentFile) {
    String markdown =
        "---\n"
            + "name: mock-agent\n"
            + "description: 27 节无 key 全链路自测 Agent\n"
            + "provider:\n"
            + "  name: mock\n"
            + "  model: mock-model\n"
            + "tools:\n"
            + "  - save_memory\n"
            + "settings:\n"
            + "  max_iterations: 5\n"
            + "  max_history_turns: 20\n"
            + "---\n\n"
            + "你是 mock-agent，只用于验证 SableOS 全链路。\n";
    try {
      Files.createDirectories(agentFile.getParent());
      Files.writeString(agentFile, markdown, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
