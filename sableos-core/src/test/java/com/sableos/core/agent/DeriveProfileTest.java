package com.sableos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sableos.core.profile.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 课件《第29节》验收 harness：DeriveProfileTest——frontmatter 各字段正确映射到 Profile， 且 schedules 原样带进派生的
 * Profile（定时来自 Agent 的直接证据）。
 */
class DeriveProfileTest {

  private static final Set<String> KNOWN_PROVIDERS = Set.of("deepseek", "qwen");

  @TempDir Path agentsDir;

  private Profile derive(String name, String markdown) throws IOException {
    Path dir = agentsDir.resolve(name);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("AGENT.md"), markdown, StandardCharsets.UTF_8);
    return new AgentLoader(agentsDir, KNOWN_PROVIDERS).deriveProfile(dir);
  }

  @Test
  @DisplayName("frontmatter 各字段正确映射到 Profile")
  void deriveProfile_mapsEveryDeclaredField() throws IOException {
    Profile profile =
        derive(
            "daily-reconcile",
            """
            ---
            name: daily-reconcile
            description: 每天核对交易库与清算库
            identity:
              agent_name: 对账小欧
              prompt: 你是一个严谨的对账助手。
            provider:
              name: deepseek
              model: deepseek-chat
              temperature: 0.2
            tools: [shell, read_file, notify, save_memory]
            mcp_servers: [news]
            channels: [web]
            bootstrap: [BOOT.md]
            settings:
              max_iterations: 8
              max_history_turns: 20
            ---

            你是每日订单对账助手。
            """);

    assertThat(profile.name()).isEqualTo("daily-reconcile");
    assertThat(profile.description()).isEqualTo("每天核对交易库与清算库");
    assertThat(profile.identity().agentName()).isEqualTo("对账小欧");
    assertThat(profile.identity().prompt()).isEqualTo("你是一个严谨的对账助手。");
    assertThat(profile.provider().name()).isEqualTo("deepseek");
    assertThat(profile.provider().model()).isEqualTo("deepseek-chat");
    assertThat(profile.provider().temperature()).isEqualTo(0.2);
    assertThat(profile.tools()).containsExactly("shell", "read_file", "notify", "save_memory");
    assertThat(profile.mcpServers()).containsExactly("news");
    assertThat(profile.channels()).containsExactly("web");
    assertThat(profile.bootstrap()).containsExactly("BOOT.md");
    assertThat(profile.settings().maxIterations()).isEqualTo(8);
    assertThat(profile.settings().maxHistoryTurns()).isEqualTo(20);
  }

  @Test
  @DisplayName("schedules 原样带进派生 Profile：id/cron/zone/message 逐字相等")
  void deriveProfile_carriesSchedulesVerbatim() throws IOException {
    Profile profile =
        derive(
            "ops",
            """
            ---
            name: ops
            provider:
              name: deepseek
              model: deepseek-chat
            schedules:
              - id: reconcile-morning
                cron: "0 0 9 * * *"
                zone: Asia/Shanghai
                message: 到点了，核对昨天的订单对账。
              - id: reconcile-noon
                cron: "0 0 13 * * *"
                zone: UTC
                message: 中午再核对一次。
            ---

            正文
            """);

    assertThat(profile.schedules()).hasSize(2);
    Profile.ScheduleConfig first = profile.schedules().get(0);
    assertThat(first.id()).isEqualTo("reconcile-morning");
    assertThat(first.cron()).isEqualTo("0 0 9 * * *");
    assertThat(first.zone()).isEqualTo("Asia/Shanghai");
    assertThat(first.message()).isEqualTo("到点了，核对昨天的订单对账。");
    Profile.ScheduleConfig second = profile.schedules().get(1);
    assertThat(second.id()).isEqualTo("reconcile-noon");
    assertThat(second.cron()).isEqualTo("0 0 13 * * *");
    assertThat(second.zone()).isEqualTo("UTC");
  }

  @Test
  @DisplayName("未声明 schedules 的 Agent 派生为空列表，不抛异常")
  void deriveProfile_withoutSchedules_yieldsEmptyList() throws IOException {
    Profile profile =
        derive(
            "bare",
            """
            ---
            name: bare
            provider:
              name: deepseek
              model: deepseek-chat
            ---

            正文
            """);

    assertThat(profile.schedules()).isEqualTo(List.of());
  }

  @Test
  @DisplayName("notify_channels 不进 frontmatter：声明了也不会出现在 Profile 的任何字段里")
  void deriveProfile_notifyChannelsIsNotAProfileField() throws IOException {
    Profile profile =
        derive(
            "ch",
            """
            ---
            name: ch
            provider:
              name: deepseek
              model: deepseek-chat
            notify_channels:
              - {type: webhook, url: "https://example.com/hook"}
            ---

            正文按渠道名引用 ops-webhook。
            """);

    // Profile 没有 notify_channels 成员；该键被安全忽略，不影响其余字段派生
    assertThat(profile.name()).isEqualTo("ch");
    assertThat(profile.provider().model()).isEqualTo("deepseek-chat");
  }
}
