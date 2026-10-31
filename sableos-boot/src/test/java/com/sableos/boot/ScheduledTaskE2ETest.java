package com.sableos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.cli.SableOsRuntime;
import com.sableos.core.scheduler.AgentScheduler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * 定时任务子系统整机验收（第 28 节主 harness）：mock provider、无 key、gate 内。
 *
 * <p>从"启动登记"到"立即执行"到"停用"走一遍 v2 管理端点，再查记忆与执行历史，确认状态真的落在 SQLite。
 */
@SpringBootTest(
    classes = SableOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduledTaskE2ETest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path WORKSPACE = tempWorkspace();
  private static final String PROFILE = "demo-agent";
  private static final String SCHEDULE_KEY = "daily-demo";
  private static final String FACT = "定时任务已执行";

  static {
    writeAgent(WORKSPACE.resolve("agents").resolve(PROFILE).resolve("AGENT.md"));
  }

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("workspace.root", WORKSPACE::toString);
    registry.add("sableos.providers[0].name", () -> "mock");
    registry.add("sableos.providers[0].type", () -> "mock");
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private AgentScheduler scheduler;

  @BeforeAll
  void registerSchedules() {
    // serve / gateway 启动后显式调用；测试里手动模拟启动注册。
    scheduler.registerAll();
  }

  @Test
  @DisplayName("定时子系统_启动登记_立即执行_持久化_停用全流程")
  void fullFlow_registersRunsPersistsAndDisables() throws Exception {
    JsonNode tasks = get("/api/v2/schedules").path("data");
    assertThat(tasks).hasSize(1);
    JsonNode task = tasks.get(0);
    assertThat(task.path("profileName").asText()).isEqualTo(PROFILE);
    assertThat(task.path("scheduleKey").asText()).isEqualTo(SCHEDULE_KEY);
    assertThat(task.path("runCount").asInt()).isZero();
    assertThat(task.path("enabled").asBoolean()).isTrue();
    assertThat(task.path("nextRunAt").asText()).isNotBlank();
    long scheduleId = task.path("scheduleId").asLong();

    JsonNode run = post("/api/v2/schedules/" + scheduleId + "/run", Map.of()).path("data");
    assertThat(run.path("success").asBoolean()).isTrue();
    assertThat(run.path("sessionId").asText()).isEqualTo("scheduler:scheduler:" + PROFILE);

    JsonNode afterRun = get("/api/v2/schedules").path("data").get(0);
    assertThat(afterRun.path("runCount").asInt()).isEqualTo(1);
    assertThat(afterRun.path("lastStatus").asText()).isEqualTo("success");

    JsonNode executions = get("/api/v2/schedules/" + scheduleId + "/executions").path("data");
    assertThat(executions).hasSize(1);
    assertThat(executions.get(0).path("success").asBoolean()).isTrue();

    JsonNode memory = get("/api/v1/memory?profile=" + PROFILE).path("data");
    assertThat(memory.path("content").asText()).contains(FACT);

    JsonNode disabled =
        put("/api/v2/schedules/" + scheduleId, Map.of("enabled", false)).path("data");
    assertThat(disabled.path("enabled").asBoolean()).isFalse();

    JsonNode legacy = get("/api/v1/schedules").path("data");
    assertThat(legacy).hasSize(1);
    assertThat(legacy.get(0).path("scheduleKey").asText()).isEqualTo(SCHEDULE_KEY);
  }

  private JsonNode get(String path) throws IOException {
    ResponseEntity<String> response = rest.getForEntity(path, String.class);
    assertThat(response.getStatusCode().is2xxSuccessful())
        .as("GET %s 应成功，实际 %s，body=%s", path, response.getStatusCode(), response.getBody())
        .isTrue();
    return MAPPER.readTree(response.getBody());
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

  private JsonNode put(String path, Object body) throws IOException {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    assertThat(response.getStatusCode().is2xxSuccessful())
        .as("PUT %s 应成功，实际 %s，body=%s", path, response.getStatusCode(), response.getBody())
        .isTrue();
    return MAPPER.readTree(response.getBody());
  }

  private static Path tempWorkspace() {
    try {
      Path path = Files.createTempDirectory("sableos-schedule-e2e-");
      path.toFile().deleteOnExit();
      return path;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeAgent(Path agentFile) {
    String markdown =
        "---\n"
            + "name: "
            + PROFILE
            + "\n"
            + "description: 28 节定时任务整机验收 Agent\n"
            + "provider:\n"
            + "  name: mock\n"
            + "  model: mock-model\n"
            + "tools:\n"
            + "  - save_memory\n"
            + "schedules:\n"
            + "  - id: "
            + SCHEDULE_KEY
            + "\n"
            + "    cron: \"0 0 8 * * *\"\n"
            + "    zone: Asia/Shanghai\n"
            + "    message: \"记住："
            + FACT
            + "\"\n"
            + "settings:\n"
            + "  max_iterations: 5\n"
            + "  max_history_turns: 20\n"
            + "---\n\n"
            + "你是 demo-agent，只用于验证定时任务子系统。\n";
    try {
      Files.createDirectories(agentFile.getParent());
      Files.writeString(agentFile, markdown, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
