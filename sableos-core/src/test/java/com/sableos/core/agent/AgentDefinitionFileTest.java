package com.sableos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sableos.core.profile.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agent 定义文件名的大小写口径（第 30 节补丁）：{@code AGENT.md} / {@code agent.md} / {@code Agent.MD} 等变体在**所有**
 * 读取路径上等价。
 *
 * <p><b>为什么单列一个 harness</b>：这类缺陷在 Windows / macOS 上被大小写不敏感的文件系统掩盖——{@code dir.resolve("AGENT.md")}
 * 一样能打开 {@code agent.md}。只有 Linux（CI 与多数服务器）会露出来，本地开发机上永远复现不了， 所以必须有明确用例把「按 UTF-8
 * 语义比较文件名」这件事钉死，而不是依赖某台机器的默认行为。用例本身在任何平台都成立（大小写敏感 时是真两个文件，不敏感时是同一个文件），因此可以随 {@code mvn verify} 在三个
 * OS 上跑。
 */
class AgentDefinitionFileTest {

  private static final Set<String> KNOWN_PROVIDERS = Set.of("deepseek");

  /** 工作区根：{@code AgentStore} 期望 {@code <root>/agents/<name>}，故这里给根、再派生 agents 目录。 */
  @TempDir Path root;

  private Path agentsDir() {
    return root.resolve("agents");
  }

  private static String markdown(String name, String description) {
    return "---\nname: "
        + name
        + "\ndescription: "
        + description
        + "\nprovider:\n  name: deepseek\n  model: deepseek-chat\n---\n\n正文\n";
  }

  private Path writeAgent(String dirName, String fileName, String agentName) throws IOException {
    Path dir = Files.createDirectories(agentsDir().resolve(dirName));
    Files.writeString(dir.resolve(fileName), markdown(agentName, "大小写用例"), StandardCharsets.UTF_8);
    return dir;
  }

  private AgentLoader loader() {
    return new AgentLoader(agentsDir(), KNOWN_PROVIDERS);
  }

  private AgentStore store() {
    return new AgentStore(root);
  }

  /**
   * 本机文件系统是否区分大小写。
   *
   * <p>Windows / macOS 默认不区分：写 {@code agent.md} 后 {@code AGENT.md} 也存在（同一个文件）。此时「磁盘上只有一个变体」
   * 这类断言没有意义——它本来就是同一个文件。用例必须按平台调整期望，否则同一份代码在 Linux CI 与本地之间结果不一致，而这正是 本类要防的那类「只在某平台上暴露」的坑。
   */
  private static boolean caseSensitiveFileSystem(Path dir) throws IOException {
    Files.createDirectories(dir);
    Path lower = dir.resolve("case-probe.md");
    Files.writeString(lower, "x", StandardCharsets.UTF_8);
    return !Files.exists(dir.resolve("CASE-PROBE.md"));
  }

  // ---------------------------------------------------------------- 纯判定

  @Test
  @DisplayName("文件名判定：任意大小写都算定义文件，其它文件不算")
  void matches_isCaseInsensitive() {
    assertThat(AgentDefinitionFile.matches("AGENT.md")).isTrue();
    assertThat(AgentDefinitionFile.matches("agent.md")).isTrue();
    assertThat(AgentDefinitionFile.matches("Agent.MD")).isTrue();
    assertThat(AgentDefinitionFile.matches("aGeNt.Md")).isTrue();

    assertThat(AgentDefinitionFile.matches("AGENT.md.bak")).isFalse();
    assertThat(AgentDefinitionFile.matches("AGENTS.md")).isFalse();
    assertThat(AgentDefinitionFile.matches("CLAUDE.md")).isFalse();
    assertThat(AgentDefinitionFile.matches("REFERENCE.md")).isFalse();
    assertThat(AgentDefinitionFile.matches(null)).isFalse();
  }

  @Test
  @DisplayName("路径判定：只取末段名，目录名不参与")
  void matchesPath_usesFileNameOnly() {
    assertThat(AgentDefinitionFile.matchesPath(Path.of("/w/agents/demo/agent.md"))).isTrue();
    assertThat(AgentDefinitionFile.matchesPath(Path.of("/w/agents/AGENT.md/demo/REFERENCE.md")))
        .isFalse();
  }

  // ---------------------------------------------------------------- 定位与派生

  @Test
  @DisplayName("只有 agent.md 的目录：能被定位并派生出 Profile（Linux 上是真实缺口）")
  void lowercaseOnly_directoryDerives() throws IOException {
    Path dir = writeAgent("lower", "agent.md", "lower");

    Path located = AgentDefinitionFile.locate(dir).orElseThrow();
    // 大小写敏感时定位到的就是写下去的那个名字；不敏感时它同时也是 AGENT.md（同一个文件）
    assertThat(located.getFileName().toString()).isEqualToIgnoringCase("agent.md");

    Profile profile = loader().deriveProfile(dir);
    assertThat(profile.name()).isEqualTo("lower");
  }

  @Test
  @DisplayName("只有 Agent.MD 的目录：同样能被派生")
  void mixedCaseOnly_directoryDerives() throws IOException {
    Path dir = writeAgent("mixed", "Agent.MD", "mixed");

    Profile profile = loader().deriveProfile(dir);
    assertThat(profile.name()).isEqualTo("mixed");
  }

  @Test
  @DisplayName("目录下没有任何大小写变体：定位为空、派生给出可读错误")
  void missing_definitionIsReported() throws IOException {
    Path dir = Files.createDirectories(agentsDir().resolve("empty"));
    Files.writeString(dir.resolve("REFERENCE.md"), "只有参考文件", StandardCharsets.UTF_8);

    assertThat(AgentDefinitionFile.locate(dir)).isEmpty();
    assertThat(AgentDefinitionFile.existsIn(dir)).isFalse();
    assertThatThrownBy(() -> loader().deriveProfile(dir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("AGENT.md");
  }

  @Test
  @DisplayName("规范名与变体并存：优先精确名，其余不参与")
  void exactNameWinsWhenBothPresent() throws IOException {
    Path dir = writeAgent("both", "AGENT.md", "both");
    Files.writeString(dir.resolve("agent.md"), markdown("both", "变体"), StandardCharsets.UTF_8);

    assertThat(AgentDefinitionFile.locate(dir).orElseThrow().getFileName().toString())
        .isEqualTo("AGENT.md");
  }

  // ---------------------------------------------------------------- 读写链路

  @Test
  @DisplayName("AgentStore#read：目录里只有 agent.md 也能读到内容")
  void storeRead_resolvesVariant() throws IOException {
    writeAgent("readable", "agent.md", "readable");

    assertThat(store().read("readable")).contains("name: readable");
  }

  @Test
  @DisplayName("AgentStore#writeAll：目录里已有大小写变体时按变体写，不再多出一份定义")
  void storeWrite_keepsExistingVariant() throws IOException {
    Path dir = writeAgent("rewrite", "Agent.MD", "rewrite");
    boolean caseSensitive = caseSensitiveFileSystem(dir.resolve("probe"));
    Path existing = dir.resolve("Agent.MD");

    Map<String, String> files = new LinkedHashMap<>();
    files.put("AGENT.md", markdown("rewrite", "改过的描述"));
    files.put("scripts/a.py", "print(1)");
    store().writeAll("rewrite", files);

    // 内容必须落到原来那份文件上
    assertThat(Files.readString(existing, StandardCharsets.UTF_8)).contains("改过的描述");
    Path located = AgentDefinitionFile.locate(dir).orElseThrow();
    if (caseSensitive) {
      // 只有大小写敏感的文件系统才可能「多出一份」；此时定位到的必须仍是原来那个变体名
      assertThat(located.getFileName().toString()).isEqualTo("Agent.MD");
      assertThat(Files.exists(dir.resolve("AGENT.md"))).isFalse();
    } else {
      // 不敏感的机器上 AGENT.md / Agent.MD 本就是同一个文件，比名字没意义，比身份
      assertThat(Files.isSameFile(located, existing)).isTrue();
    }
  }

  @Test
  @DisplayName("新建 Agent 写规范名；之后用变体名保存仍只有一份定义")
  void storeWrite_newAgentUsesCanonicalName() throws IOException {
    store().writeAll("fresh", Map.of("AGENT.md", markdown("fresh", "新的")));

    Path dir = agentsDir().resolve("fresh");
    assertThat(Files.exists(dir.resolve("AGENT.md"))).isTrue();
    boolean caseSensitive = caseSensitiveFileSystem(dir.resolve("probe"));

    store().writeAll("fresh", Map.of("agent.md", markdown("fresh", "再改")));
    assertThat(Files.readString(dir.resolve("AGENT.md"), StandardCharsets.UTF_8)).contains("再改");
    Path located = AgentDefinitionFile.locate(dir).orElseThrow();
    if (caseSensitive) {
      assertThat(located.getFileName().toString()).isEqualTo("AGENT.md");
      assertThat(Files.exists(dir.resolve("agent.md"))).isFalse();
    } else {
      assertThat(Files.isSameFile(located, dir.resolve("AGENT.md"))).isTrue();
    }
  }
}
