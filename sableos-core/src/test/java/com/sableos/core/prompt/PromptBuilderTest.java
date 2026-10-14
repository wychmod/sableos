package com.sableos.core.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sableos.core.context.ContextLoader;
import com.sableos.core.memory.MemoryProvider;
import com.sableos.core.profile.Profile;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.session.Session;
import com.sableos.core.tool.SableTool;
import com.sableos.core.tool.ToolCatalog;
import com.sableos.core.tool.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 上下文组装验收：固定顺序（system → 记忆 → 历史）、system prompt 末尾附日期时间、无记忆跳过、历史超 N 条截断、工具走原生 schema。 */
class PromptBuilderTest {

  private static final String BODY_MARKER = "正文标记BODY";
  private static final String MEMORY_MARKER = "记忆段内容MARK";
  private static final String TIMESTAMP_PATTERN = "当前时间：\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}";

  private static final ToolCatalog EMPTY_CATALOG =
      new ToolCatalog() {
        @Override
        public List<SableTool> toolsFor(Profile profile) {
          return List.of();
        }

        @Override
        public SableTool find(String name) {
          return null;
        }
      };

  private static final SableTool STUB_TOOL =
      new SableTool() {
        @Override
        public String getName() {
          return "http_get";
        }

        @Override
        public String getDescription() {
          return "发起 GET 请求";
        }

        @Override
        public String getInputSchema() {
          return "{\"type\":\"object\"}";
        }

        @Override
        public ToolResult execute(JsonNode input) {
          return ToolResult.success("ok");
        }
      };

  @TempDir Path agentsDir;

  @TempDir Path bootstrapDir;

  @TempDir Path skillsRoot;

  private ContextLoader contextLoader;

  @BeforeEach
  void setUp() throws IOException {
    Path dir = agentsDir.resolve("ops-agent");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("AGENT.md"), BODY_MARKER, StandardCharsets.UTF_8);
    contextLoader = new ContextLoader(agentsDir, bootstrapDir, skillsRoot);
  }

  private static Profile profile(Integer maxIterations, Integer maxHistoryTurns) {
    return new Profile(
        "ops-agent",
        "测试 Agent",
        new Profile.Identity("ops-agent", "你是运维助手。"),
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(maxIterations, maxHistoryTurns));
  }

  private PromptBuilder builder(MemoryProvider memoryProvider) {
    return new PromptBuilder(contextLoader, EMPTY_CATALOG, memoryProvider);
  }

  private static Session sessionWithUserMessage(String content) {
    Session session = new Session("s-1", "ops-agent", "cli", "u-1");
    session.appendUserMessage(content);
    return session;
  }

  private static String withoutTimestamp(String content) {
    return content.replaceAll(TIMESTAMP_PATTERN, "<ts>");
  }

  @Test
  @DisplayName("组装顺序：system prompt → 长期记忆 → 会话历史")
  void build_ordersSystemMemoryHistory() {
    Session session = sessionWithUserMessage("你好");

    String content =
        builder(profile -> MEMORY_MARKER).build(session, profile(null, null)).content();

    int systemIdx = content.indexOf(BODY_MARKER);
    int memoryIdx = content.indexOf(MEMORY_MARKER);
    int historyIdx = content.indexOf("User: 你好");

    assertThat(systemIdx).isNotNegative();
    assertThat(memoryIdx).isGreaterThan(systemIdx);
    assertThat(historyIdx).isGreaterThan(memoryIdx);
  }

  @Test
  @DisplayName("system prompt 末尾含当前日期时间")
  void build_systemPromptEndsWithCurrentDateTime() {
    Session session = new Session("s-1", "ops-agent", "cli", "u-1");

    String content = builder(null).build(session, profile(null, null)).content();

    assertThat(content).containsPattern(TIMESTAMP_PATTERN + "$");
  }

  @Test
  @DisplayName("无长期记忆（空白）时整段省略，与无记忆装配完全一致")
  void build_memoryAbsent_skipsSection() {
    String blankMemory =
        builder(profile -> "   ")
            .build(sessionWithUserMessage("你好"), profile(null, null))
            .content();
    String noMemory =
        builder(null).build(sessionWithUserMessage("你好"), profile(null, null)).content();

    assertThat(withoutTimestamp(blankMemory)).isEqualTo(withoutTimestamp(noMemory));
  }

  @Test
  @DisplayName("历史超 N 轮被截断：默认 20 只留最近 20 条，system prompt 不参与截断")
  void build_historyExceedsLimit_keepsOnlyLatestNMessages() {
    Session session = new Session("s-1", "ops-agent", "cli", "u-1");
    for (int i = 0; i < 25; i++) {
      session.appendUserMessage("MSG" + i + "END");
    }

    String content = builder(null).build(session, profile(null, null)).content();

    assertThat(content).contains(BODY_MARKER);
    assertThat(content).contains("User: MSG24END");
    assertThat(content).contains("User: MSG5END");
    assertThat(content).doesNotContain("User: MSG4END");
    assertThat(content).doesNotContain("User: MSG0END");
  }

  @Test
  @DisplayName("Profile 覆盖历史轮数：maxHistoryTurns=3 只留最近 3 条")
  void build_profileOverridesHistoryLimit_keepsDeclaredCount() {
    Session session = new Session("s-1", "ops-agent", "cli", "u-1");
    for (int i = 0; i < 5; i++) {
      session.appendUserMessage("MSG" + i + "END");
    }

    String content = builder(null).build(session, profile(null, 3)).content();

    assertThat(content).contains("User: MSG4END");
    assertThat(content).contains("User: MSG2END");
    assertThat(content).doesNotContain("User: MSG1END");
    assertThat(content).doesNotContain("User: MSG0END");
  }

  @Test
  @DisplayName("可用工具取自工具目录，走原生 schema 不进 content")
  void build_availableToolsComeFromCatalog() {
    ToolCatalog catalog =
        new ToolCatalog() {
          @Override
          public List<SableTool> toolsFor(Profile profile) {
            return List.of(STUB_TOOL);
          }

          @Override
          public SableTool find(String name) {
            return STUB_TOOL;
          }
        };
    PromptBuilder builder = new PromptBuilder(contextLoader, catalog, null);

    ProviderRequest request = builder.build(sessionWithUserMessage("你好"), profile(null, null));

    assertThat(request.availableTools()).containsExactly(STUB_TOOL);
    assertThat(request.content()).doesNotContain(STUB_TOOL.getName());
  }
}
