package com.sableos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 课件《第29节》验收 harness：AgentScanRegisterTest——扫 N 个目录得 N 个 Agent、不产生别的东西。 */
class AgentScanRegisterTest {

  private static final Set<String> KNOWN_PROVIDERS = Set.of("deepseek");

  @TempDir Path agentsDir;

  private void writeAgent(String name, boolean withSchedule) throws IOException {
    Path dir = Files.createDirectories(agentsDir.resolve(name));
    StringBuilder frontmatter = new StringBuilder();
    frontmatter.append("name: ").append(name).append('\n');
    frontmatter.append("provider:\n  name: deepseek\n  model: deepseek-chat\n");
    if (withSchedule) {
      frontmatter.append(
          "schedules:\n"
              + "  - id: morning\n"
              + "    cron: \"0 0 9 * * *\"\n"
              + "    zone: Asia/Shanghai\n"
              + "    message: 到点\n");
    }
    Files.writeString(
        dir.resolve("AGENT.md"), "---\n" + frontmatter + "---\n\n正文\n", StandardCharsets.UTF_8);
  }

  private AgentLoader loader() {
    return new AgentLoader(agentsDir, KNOWN_PROVIDERS);
  }

  @Test
  @DisplayName("扫 N 个 Agent 目录 → 注册表出现 N 个、不产生别的东西")
  void scanNDirs_yieldsExactlyNAgents() throws IOException {
    writeAgent("alpha", false);
    writeAgent("beta", true);
    writeAgent("gamma", false);

    ProfileRegistry registry = loader().loadAll();

    assertThat(registry.all()).hasSize(3);
    List<String> names = registry.all().stream().map(Profile::name).sorted().toList();
    assertThat(names).containsExactly("alpha", "beta", "gamma");
  }

  @Test
  @DisplayName("带 schedules 的 Agent 其定时原样留在派生 Profile 里")
  void scan_withSchedule_keepsScheduleOnDerivedProfile() throws IOException {
    writeAgent("beta", true);

    ProfileRegistry registry = loader().loadAll();

    assertThat(registry.get("beta").schedules()).hasSize(1);
    assertThat(registry.get("beta").schedules().get(0).id()).isEqualTo("morning");
  }

  @Test
  @DisplayName("Agent 根目录不存在 → 空注册表，不抛异常")
  void scan_missingRoot_returnsEmptyRegistry() {
    AgentLoader missing = new AgentLoader(agentsDir.resolve("does-not-exist"), KNOWN_PROVIDERS);

    assertThat(missing.loadAll().all()).isEmpty();
  }

  @Test
  @DisplayName("根目录下的普通文件被忽略，只处理目录")
  void scan_plainFileIsIgnored() throws IOException {
    writeAgent("alpha", false);
    Files.writeString(agentsDir.resolve("README.md"), "不是 Agent", StandardCharsets.UTF_8);

    assertThat(loader().loadAll().all()).hasSize(1);
  }

  @Test
  @DisplayName("3 个合法 + 2 个非法 → 注册表恰 3 个，扫描不抛异常")
  void scan_mixedLegalAndIllegal_registersExactlyLegalOnes() throws IOException {
    writeAgent("alpha", false);
    writeAgent("beta", true);
    writeAgent("gamma", false);
    // 非法一：缺 AGENT.md
    Files.createDirectories(agentsDir.resolve("no-file"));
    // 非法二：name 与目录名不一致
    Path mismatch = Files.createDirectories(agentsDir.resolve("mismatch"));
    Files.writeString(
        mismatch.resolve("AGENT.md"),
        "---\nname: other\nprovider:\n  name: deepseek\n  model: deepseek-chat\n---\n正文\n",
        StandardCharsets.UTF_8);

    ProfileRegistry registry = loader().loadAll();

    assertThat(registry.all()).hasSize(3);
    assertThat(registry.all().stream().map(Profile::name).sorted().toList())
        .containsExactly("alpha", "beta", "gamma");
  }
}
