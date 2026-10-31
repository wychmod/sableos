package com.sableos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.cli.SableOsRuntime;
import com.sableos.core.scheduler.AgentScheduler;
import com.sableos.storage.LlmCall;
import com.sableos.storage.LlmCallRepository;
import com.sableos.storage.ToolInvocation;
import com.sableos.storage.ToolInvocationRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 定时链路真集成验收（第 28 节，{@code @Tag("integration")}、真 key）：查天气 → notify → 本地 webhook。
 *
 * <p>覆盖：定时会话复用、模型/工具审计逐条对账、webhook 真收到、白名单失败隔离。无 {@code DEEPSEEK_API_KEY} 时跳过， 不算失败。命令见 quickstart。
 */
@SpringBootTest(
    classes = SableOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulerFlowIT {

  private static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path WORKSPACE = tempWorkspace();
  private static final HttpServer WEATHER_SERVER = startWeatherServer();
  private static final int WEATHER_PORT = WEATHER_SERVER.getAddress().getPort();
  private static final BlockingQueue<String> WEBHOOK_BODIES = new LinkedBlockingQueue<>();
  private static final HttpServer WEBHOOK_SERVER = startWebhookServer();
  private static final int WEBHOOK_PORT = WEBHOOK_SERVER.getAddress().getPort();
  private static final String PROFILE = "weather-agent";
  private static final String SCHEDULE_KEY = "daily-weather";
  private static final String CHANNEL = "test-webhook";
  private static final String SCHEDULER_SESSION = "scheduler:scheduler:" + PROFILE;

  static {
    writeAgent(WORKSPACE.resolve("agents").resolve(PROFILE).resolve("AGENT.md"));
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
    registry.add("http.allowed_domains[0]", () -> "127.0.0.1");
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private AgentScheduler agentScheduler;
  @Autowired private LlmCallRepository llmCallRepository;
  @Autowired private ToolInvocationRepository toolInvocationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeAll
  void registerScheduleAndChannel() throws Exception {
    agentScheduler.registerAll();
    post(
        "/api/v1/notify-channels",
        Map.of(
            "name",
            CHANNEL,
            "type",
            "webhook",
            "url",
            "http://127.0.0.1:" + WEBHOOK_PORT + "/hook",
            "description",
            "IT 本地接收端"));
  }

  @AfterAll
  void stopServers() {
    WEATHER_SERVER.stop(0);
    WEBHOOK_SERVER.stop(0);
  }

  @Test
  @DisplayName("定时真跑_查天气推送_会话复用_逐表对账_白名单失败隔离")
  void scheduledFlow_pushesWeatherAndIsolatesWhitelistFailure() throws Exception {
    assumeTrue(API_KEY != null && !API_KEY.isBlank(), "未设置 DEEPSEEK_API_KEY，跳过真集成验收");

    long scheduleId = scheduleId();
    run(scheduleId);
    String firstBody = WEBHOOK_BODIES.poll(20, TimeUnit.SECONDS);
    assertThat(firstBody).as("本地 webhook 应收到推送").isNotNull();
    assertThat(firstBody).contains("content");

    assertThat(schedulerSessionCount()).isEqualTo(1);
    List<LlmCall> llmCalls = llmCalls();
    assertThat(llmCalls).hasSize(2);
    assertThat(llmCalls).allMatch(LlmCall::isSuccess);
    List<ToolInvocation> toolCalls = toolCalls();
    assertThat(toolCalls).hasSize(2);
    assertThat(toolCalls)
        .extracting(ToolInvocation::getToolName)
        .containsExactlyInAnyOrder("http_get", "notify");
    assertThat(toolCalls).allMatch(ToolInvocation::isSuccess);

    run(scheduleId);
    assertThat(WEBHOOK_BODIES.poll(20, TimeUnit.SECONDS)).as("第二次推送也应到达").isNotNull();
    assertThat(schedulerSessionCount()).isEqualTo(1);
    assertThat(llmCalls()).hasSize(4);
    assertThat(toolCalls()).hasSize(4);

    updateChannel("http://evil.example.com/hook");
    run(scheduleId);
    assertThat(toolCalls())
        .anyMatch(call -> "notify".equals(call.getToolName()) && !call.isSuccess());

    updateChannel("http://127.0.0.1:" + WEBHOOK_PORT + "/hook");
    run(scheduleId);
    assertThat(WEBHOOK_BODIES.poll(20, TimeUnit.SECONDS)).as("恢复渠道后应能再次推送").isNotNull();
  }

  private long scheduleId() throws Exception {
    JsonNode data = get("/api/v2/schedules").path("data");
    assertThat(data).hasSize(1);
    return data.get(0).path("scheduleId").asLong();
  }

  private void run(long scheduleId) throws Exception {
    JsonNode response = post("/api/v2/schedules/" + scheduleId + "/run", null);
    assertThat(response.path("code").asInt()).isZero();
  }

  private void updateChannel(String url) throws Exception {
    JsonNode response =
        exchange(
            "/api/v1/notify-channels/" + CHANNEL,
            HttpMethod.PUT,
            Map.of("name", CHANNEL, "type", "webhook", "url", url));
    assertThat(response.path("code").asInt()).isZero();
  }

  private int schedulerSessionCount() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sessions WHERE channel = ? AND user_id = ? AND profile_name = ?",
            Integer.class,
            "scheduler",
            "scheduler",
            PROFILE);
    return count == null ? 0 : count;
  }

  private List<LlmCall> llmCalls() {
    return llmCallRepository.findAll().stream()
        .filter(call -> SCHEDULER_SESSION.equals(call.getSessionId()))
        .toList();
  }

  private List<ToolInvocation> toolCalls() {
    return toolInvocationRepository.findAll().stream()
        .filter(call -> SCHEDULER_SESSION.equals(call.getSessionId()))
        .toList();
  }

  private JsonNode get(String path) throws IOException {
    return MAPPER.readTree(rest.getForEntity(path, String.class).getBody());
  }

  private JsonNode post(String path, Object body) throws IOException {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return MAPPER.readTree(
        rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class)
            .getBody());
  }

  private JsonNode exchange(String path, HttpMethod method, Object body) throws IOException {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return MAPPER.readTree(
        rest.exchange(path, method, new HttpEntity<>(body, headers), String.class).getBody());
  }

  private static HttpServer startWeatherServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/weather",
          exchange -> {
            byte[] body =
                "{\"city\":\"北京\",\"temperature\":18,\"condition\":\"晴\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static HttpServer startWebhookServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/hook",
          exchange -> {
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            WEBHOOK_BODIES.add(body);
            exchange.sendResponseHeaders(200, -1);
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
      Path path = Files.createTempDirectory("sableos-scheduler-flow-");
      path.toFile().deleteOnExit();
      return path;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeAgent(Path agentFile) {
    String message =
        "请先调用 http_get 访问 http://127.0.0.1:"
            + WEATHER_PORT
            + "/weather 获取北京天气，再调用 notify 向渠道 "
            + CHANNEL
            + " 推送一条穿衣建议。";
    String markdown =
        "---\n"
            + "name: "
            + PROFILE
            + "\n"
            + "description: 28 节定时真链路验收 Agent\n"
            + "provider:\n"
            + "  name: deepseek\n"
            + "  model: deepseek-chat\n"
            + "tools:\n"
            + "  - http_get\n"
            + "  - notify\n"
            + "schedules:\n"
            + "  - id: "
            + SCHEDULE_KEY
            + "\n"
            + "    cron: \"0 0 8 * * *\"\n"
            + "    zone: Asia/Shanghai\n"
            + "    message: \""
            + message
            + "\"\n"
            + "settings:\n"
            + "  max_iterations: 6\n"
            + "  max_history_turns: 20\n"
            + "---\n\n"
            + "你是天气助手。推送时 channel 使用 "
            + CHANNEL
            + "。\n";
    try {
      Files.createDirectories(agentFile.getParent());
      Files.writeString(agentFile, markdown, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
