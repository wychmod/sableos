package com.sableos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.sableos.cli.SableOsRuntime;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.prompt.PromptBuilder;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.scheduler.AgentScheduler;
import com.sableos.core.scheduler.ScheduledTaskStore;
import com.sableos.core.scheduler.ScheduledTaskView;
import com.sableos.core.scheduler.TaskExecutionView;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.core.tool.SableTool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 多 Agent 并存验收（第 28 节，mock、gate 内）：工具 / 会话 / 定时三条隔离边界。
 *
 * <p>A 只声明文件工具且 provider 不可达；B 声明 HTTP 工具 + save_memory 且 provider 为 mock。A 的定时任务失败后，B 的下一次触发
 * 仍必须正常执行——失败隔离是多 Agent 场景的关键回归点。
 */
@SpringBootTest(
    classes = SableOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiAgentIsolationTest {

  private static final Path WORKSPACE = tempWorkspace();
  private static final String FILE_AGENT = "file-agent";
  private static final String HTTP_AGENT = "http-agent";

  static {
    writeAgent(
        WORKSPACE.resolve("agents").resolve(FILE_AGENT).resolve("AGENT.md"),
        FILE_AGENT,
        "broken",
        "read_file",
        "write_file");
    writeAgent(
        WORKSPACE.resolve("agents").resolve(HTTP_AGENT).resolve("AGENT.md"),
        HTTP_AGENT,
        "mock",
        "http_get",
        "save_memory");
  }

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("workspace.root", WORKSPACE::toString);
    registry.add("sableos.providers[0].name", () -> "broken");
    registry.add("sableos.providers[0].type", () -> "openai");
    registry.add("sableos.providers[0].base-url", () -> "http://127.0.0.1:1");
    registry.add("sableos.providers[0].api-key", () -> "test-key");
    registry.add("sableos.providers[1].name", () -> "mock");
    registry.add("sableos.providers[1].type", () -> "mock");
  }

  @Autowired private PromptBuilder promptBuilder;
  @Autowired private ProfileRegistry profileRegistry;
  @Autowired private SessionManager sessionManager;
  @Autowired private AgentScheduler scheduler;
  @Autowired private ScheduledTaskStore scheduledTaskStore;

  @BeforeAll
  void registerSchedules() {
    scheduler.registerAll();
  }

  @Test
  @DisplayName("工具隔离_各自可用工具严格等于声明集合")
  void toolIsolation_availableToolsMatchDeclaredSet() {
    Profile fileAgent = profileRegistry.get(FILE_AGENT);
    Profile httpAgent = profileRegistry.get(HTTP_AGENT);
    assertThat(fileAgent).isNotNull();
    assertThat(httpAgent).isNotNull();

    ProviderRequest fileRequest =
        promptBuilder.build(
            new Session("web:alice:" + FILE_AGENT, FILE_AGENT, "web", "alice"), fileAgent);
    ProviderRequest httpRequest =
        promptBuilder.build(
            new Session("web:alice:" + HTTP_AGENT, HTTP_AGENT, "web", "alice"), httpAgent);

    assertThat(fileRequest.availableTools())
        .extracting(SableTool::getName)
        .containsExactly("read_file", "write_file");
    assertThat(httpRequest.availableTools())
        .extracting(SableTool::getName)
        .containsExactly("http_get", "save_memory");
  }

  @Test
  @DisplayName("会话隔离_两个Agent的会话标识与归属互不覆盖")
  void sessionIsolation_idsAreDistinct() {
    Session fileSession = sessionManager.getOrCreate("web", "alice", FILE_AGENT);
    Session httpSession = sessionManager.getOrCreate("web", "alice", HTTP_AGENT);

    assertThat(fileSession.id()).isNotEqualTo(httpSession.id());
    assertThat(fileSession.profileName()).isEqualTo(FILE_AGENT);
    assertThat(httpSession.profileName()).isEqualTo(HTTP_AGENT);
  }

  @Test
  @DisplayName("定时隔离_A失败不拖累B的下一次触发")
  void scheduleIsolation_aFailureDoesNotBlockB() {
    long fileTaskId = taskId(FILE_AGENT, "a-task");
    long httpTaskId = taskId(HTTP_AGENT, "b-task");

    TaskExecutionView failed = scheduler.runNow(fileTaskId).orElseThrow();
    assertThat(failed.success()).isFalse();
    assertThat(failed.errorMessage()).isNotBlank();

    TaskExecutionView succeeded = scheduler.runNow(httpTaskId).orElseThrow();
    assertThat(succeeded.success()).isTrue();
    assertThat(scheduledTaskStore.find(httpTaskId))
        .get()
        .extracting(ScheduledTaskView::runCount)
        .isEqualTo(1L);
  }

  private long taskId(String profileName, String key) {
    return scheduledTaskStore.list().stream()
        .filter(view -> view.profileName().equals(profileName) && view.scheduleKey().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("定时任务未登记: " + profileName + "/" + key))
        .scheduleId();
  }

  private static Path tempWorkspace() {
    try {
      Path path = Files.createTempDirectory("sableos-multi-agent-");
      path.toFile().deleteOnExit();
      return path;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeAgent(Path agentFile, String name, String provider, String... tools) {
    StringBuilder markdown =
        new StringBuilder("---\n")
            .append("name: ")
            .append(name)
            .append('\n')
            .append("description: 28 节多 Agent 隔离验收\n")
            .append("provider:\n")
            .append("  name: ")
            .append(provider)
            .append('\n')
            .append("  model: mock-model\n")
            .append("tools:\n");
    for (String tool : tools) {
      markdown.append("  - ").append(tool).append('\n');
    }
    markdown
        .append("schedules:\n")
        .append("  - id: ")
        .append("file-agent".equals(name) ? "a-task" : "b-task")
        .append('\n')
        .append("    cron: \"0 0 9 * * *\"\n")
        .append("    zone: Asia/Shanghai\n")
        .append("    message: \"记住：多 Agent 定时任务\"\n")
        .append("settings:\n")
        .append("  max_iterations: 5\n")
        .append("  max_history_turns: 20\n")
        .append("---\n\n")
        .append("你是 ")
        .append(name)
        .append("。\n");
    try {
      Files.createDirectories(agentFile.getParent());
      Files.writeString(agentFile, markdown.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
