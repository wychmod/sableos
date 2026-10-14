package com.sableos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agent 加载验收（课件 ProfileLoaderTest 按 AgentLoader 口径改名）：全字段解析、多 Agent 并存、 坏文件/未登记 provider
 * 不阻断其余、目录缺失返回空注册表。
 */
class AgentLoaderTest {

  private static final Set<String> KNOWN_PROVIDERS = Set.of("deepseek", "qwen");

  @TempDir Path agentsDir;

  private AgentLoader loader() {
    return new AgentLoader(agentsDir, KNOWN_PROVIDERS);
  }

  private void writeAgent(String dirName, String markdown) throws IOException {
    Path dir = agentsDir.resolve(dirName);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("AGENT.md"), markdown, StandardCharsets.UTF_8);
  }

  private static String agentMarkdown(String name, String providerName, String model) {
    return """
        ---
        name: %s
        description: 测试 Agent
        provider:
          name: %s
          model: %s
        ---

        正文（本节不解析）。
        """
        .formatted(name, providerName, model);
  }

  @Test
  @DisplayName("解析_合法frontmatter全字段进Profile")
  void deriveProfile_parsesAllFields() {
    String markdown =
        """
        ---
        name: ops-agent
        description: 运维助手
        identity:
          agent_name: 运维小助手
          prompt: 你是负责服务器巡检的运维专家
        provider:
          name: deepseek
          model: deepseek-chat
          temperature: 0.7
        tools: [read_file, shell]
        mcp_servers: [weather]
        channels: [cli]
        schedules:
          - id: daily
            cron: "0 0 8 * * ?"
            zone: Asia/Shanghai
            message: 巡检
        bootstrap: [SOUL.md, USER.md]
        settings:
          max_iterations: 5
          max_history_turns: 7
        ---

        正文（本节不解析）。
        """;

    Profile profile = loader().deriveProfile("ops-agent", markdown);

    assertThat(profile.name()).isEqualTo("ops-agent");
    assertThat(profile.description()).isEqualTo("运维助手");
    assertThat(profile.identity().agentName()).isEqualTo("运维小助手");
    assertThat(profile.identity().prompt()).contains("运维专家");
    assertThat(profile.provider().name()).isEqualTo("deepseek");
    assertThat(profile.provider().model()).isEqualTo("deepseek-chat");
    assertThat(profile.provider().temperature()).isEqualTo(0.7);
    assertThat(profile.tools()).containsExactly("read_file", "shell");
    assertThat(profile.mcpServers()).containsExactly("weather");
    assertThat(profile.channels()).containsExactly("cli");
    assertThat(profile.schedules()).hasSize(1);
    assertThat(profile.schedules().get(0).id()).isEqualTo("daily");
    assertThat(profile.schedules().get(0).cron()).isEqualTo("0 0 8 * * ?");
    assertThat(profile.schedules().get(0).zone()).isEqualTo("Asia/Shanghai");
    assertThat(profile.bootstrap()).containsExactly("SOUL.md", "USER.md");
    assertThat(profile.settings().maxIterations()).isEqualTo(5);
    assertThat(profile.settings().maxHistoryTurns()).isEqualTo(7);
  }

  @Test
  @DisplayName("解析_缺省字段取默认值且列表为空表")
  void deriveProfile_missingFields_appliesDefaults() {
    Profile profile =
        loader().deriveProfile("minimal", agentMarkdown("minimal", "qwen", "qwen-max"));

    assertThat(profile.identity()).isNull();
    assertThat(profile.tools()).isEmpty();
    assertThat(profile.mcpServers()).isEmpty();
    assertThat(profile.channels()).isEmpty();
    assertThat(profile.schedules()).isEmpty();
    assertThat(profile.bootstrap()).isEmpty();
    assertThat(profile.provider().temperature()).isNull();
    assertThat(profile.settings().maxIterations()).isEqualTo(10);
    assertThat(profile.settings().maxHistoryTurns()).isEqualTo(20);
  }

  @Test
  @DisplayName("加载_多个Agent并存按名索引")
  void loadAll_loadsMultipleAgentsByIndex() throws IOException {
    writeAgent("agent-a", agentMarkdown("agent-a", "deepseek", "deepseek-chat"));
    writeAgent("agent-b", agentMarkdown("agent-b", "qwen", "qwen-max"));

    ProfileRegistry registry = loader().loadAll();

    assertThat(registry.all()).hasSize(2);
    assertThat(registry.exists("agent-a")).isTrue();
    assertThat(registry.get("agent-b").provider().name()).isEqualTo("qwen");
    assertThat(registry.get("nope")).isNull();
  }

  @Test
  @DisplayName("加载_坏文件与未登记provider不阻断其余Agent")
  void loadAll_badAgentsDoNotBlockOthers() throws IOException {
    writeAgent("good-agent", agentMarkdown("good-agent", "deepseek", "deepseek-chat"));
    // 缺 AGENT.md 的目录
    Files.createDirectories(agentsDir.resolve("empty-dir"));
    // YAML 破损
    writeAgent(
        "broken-yaml",
        """
        ---
        name: [未闭合
        provider: {
        ---

        正文
        """);
    // name 与目录名不一致
    writeAgent("mismatched", agentMarkdown("another-name", "deepseek", "deepseek-chat"));
    // provider 未登记
    writeAgent("unknown-provider", agentMarkdown("unknown-provider", "kimi", "kimi-k2"));
    // provider.model 为空
    writeAgent(
        "blank-model",
        """
        ---
        name: blank-model
        provider:
          name: deepseek
        ---

        正文
        """);

    ProfileRegistry registry = loader().loadAll();

    assertThat(registry.all()).hasSize(1);
    assertThat(registry.exists("good-agent")).isTrue();
  }

  @Test
  @DisplayName("解析_未登记provider报错点名provider与Agent")
  void deriveProfile_unknownProvider_reportsProviderAndAgent() {
    String markdown = agentMarkdown("ops-agent", "kimi", "kimi-k2");

    assertThatThrownBy(() -> loader().deriveProfile("ops-agent", markdown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kimi")
        .hasMessageContaining("ops-agent");
  }

  @Test
  @DisplayName("解析_name与目录名不一致时报错")
  void deriveProfile_nameMismatch_throws() {
    String markdown = agentMarkdown("another-name", "deepseek", "deepseek-chat");

    assertThatThrownBy(() -> loader().deriveProfile("ops-agent", markdown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("another-name")
        .hasMessageContaining("ops-agent");
  }

  @Test
  @DisplayName("加载_目录缺失返回空注册表不抛错")
  void loadAll_missingDir_returnsEmptyRegistry() {
    AgentLoader loader = new AgentLoader(agentsDir.resolve("does-not-exist"), KNOWN_PROVIDERS);

    ProfileRegistry registry = loader.loadAll();

    assertThat(registry.all()).isEmpty();
  }
}
