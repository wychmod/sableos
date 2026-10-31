package com.sableos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.cli.SableOsRuntime;
import com.sableos.core.scheduler.AgentScheduler;
import com.sableos.storage.LlmCallRepository;
import com.sableos.storage.SessionRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 重启恢复验收（第 28 节，{@code @Tag("integration")}）：同一工作区先后启动两个上下文，第二个读回第一个写入的数据。
 *
 * <p>不用子进程 kill：关闭上下文再新建上下文已足以证明状态不在第一个上下文的内存里；进程级 kill + serve 的步骤见 quickstart 人工项。 使用 mock
 * provider，无 key 也可运行。
 */
@Tag("integration")
class RestartRecoveryIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path WORKSPACE = tempWorkspace();
  private static final String PROFILE = "restart-agent";
  private static final String SCHEDULE_KEY = "daily-restart";
  private static final String CONVERSATION_FACT = "重启前写入";
  private static final String SCHEDULE_FACT = "重启后仍在";

  static {
    writeAgent(WORKSPACE.resolve("agents").resolve(PROFILE).resolve("AGENT.md"));
  }

  @Test
  @DisplayName("重启后_会话记忆定时状态与历史审计全部原样可查")
  void restart_restoresAllPersistedState() throws Exception {
    String sessionId;
    long scheduleId;
    try (ConfigurableApplicationContext first = start()) {
      first.getBean(AgentScheduler.class).registerAll();
      String base = baseUrl(first);
      TestRestTemplate rest = new TestRestTemplate();

      JsonNode created =
          MAPPER.readTree(
              rest.postForEntity(
                      base + "/api/v1/sessions", Map.of("profile", PROFILE), String.class)
                  .getBody());
      sessionId = created.path("data").path("id").asText();
      assertThat(sessionId).isEqualTo("web:anonymous:" + PROFILE);

      rest.postForEntity(
          base + "/api/v1/sessions/" + sessionId + "/messages",
          Map.of("content", "记住：" + CONVERSATION_FACT),
          String.class);

      JsonNode tasks =
          MAPPER
              .readTree(rest.getForEntity(base + "/api/v2/schedules", String.class).getBody())
              .path("data");
      assertThat(tasks).hasSize(1);
      scheduleId = tasks.get(0).path("scheduleId").asLong();
      rest.postForEntity(base + "/api/v2/schedules/" + scheduleId + "/run", null, String.class);
    }

    try (ConfigurableApplicationContext second = start()) {
      second.getBean(AgentScheduler.class).registerAll();
      String base = baseUrl(second);
      TestRestTemplate rest = new TestRestTemplate();

      JsonNode session =
          MAPPER
              .readTree(
                  rest.getForEntity(base + "/api/v1/sessions/" + sessionId, String.class).getBody())
              .path("data");
      assertThat(session.path("messages").size()).isEqualTo(4);
      assertThat(session.path("messages").get(0).path("content").asText())
          .contains(CONVERSATION_FACT);

      JsonNode memory =
          MAPPER
              .readTree(
                  rest.getForEntity(base + "/api/v1/memory?profile=" + PROFILE, String.class)
                      .getBody())
              .path("data");
      assertThat(memory.path("content").asText()).contains(CONVERSATION_FACT, SCHEDULE_FACT);

      JsonNode tasks =
          MAPPER
              .readTree(rest.getForEntity(base + "/api/v2/schedules", String.class).getBody())
              .path("data");
      assertThat(tasks).hasSize(1);
      assertThat(tasks.get(0).path("scheduleId").asLong()).isEqualTo(scheduleId);
      assertThat(tasks.get(0).path("runCount").asInt()).isEqualTo(1);
      assertThat(tasks.get(0).path("lastStatus").asText()).isEqualTo("success");
      assertThat(tasks.get(0).path("nextRunAt").asText()).isNotBlank();

      JsonNode executions =
          MAPPER
              .readTree(
                  rest.getForEntity(
                          base + "/api/v2/schedules/" + scheduleId + "/executions", String.class)
                      .getBody())
              .path("data");
      assertThat(executions).hasSize(1);
      assertThat(executions.get(0).path("success").asBoolean()).isTrue();

      LlmCallRepository llmCalls = second.getBean(LlmCallRepository.class);
      assertThat(llmCalls.findAll()).hasSize(4);
      SessionRepository sessions = second.getBean(SessionRepository.class);
      assertThat(sessions.findById(sessionId)).isPresent();
      assertThat(sessions.findById("scheduler:scheduler:" + PROFILE)).isPresent();
    }
  }

  private static ConfigurableApplicationContext start() {
    // 用命令行参数而不是 SpringApplicationBuilder.properties：后者是 default properties，
    // 优先级低于 application.yaml，会被 workspace.root 的默认值覆盖。
    return new SpringApplicationBuilder(SableOsRuntime.class)
        .web(WebApplicationType.SERVLET)
        .run(
            "--workspace.root=" + WORKSPACE,
            "--server.port=0",
            "--sableos.providers[0].name=mock",
            "--sableos.providers[0].type=mock");
  }

  private static String baseUrl(ConfigurableApplicationContext context) {
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return "http://localhost:" + port;
  }

  private static Path tempWorkspace() {
    try {
      Path path = Files.createTempDirectory("sableos-restart-it-");
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
            + "description: 28 节重启恢复验收 Agent\n"
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
            + SCHEDULE_FACT
            + "\"\n"
            + "settings:\n"
            + "  max_iterations: 5\n"
            + "  max_history_turns: 20\n"
            + "---\n\n"
            + "你是 restart-agent。\n";
    try {
      Files.createDirectories(agentFile.getParent());
      Files.writeString(agentFile, markdown, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
