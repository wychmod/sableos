package com.sableos.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sableos.core.profile.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 课件《第29节》验收 harness：ProgressiveDisclosureTest——一个 Agent 内部的渐进式披露守点： 正文进 system
 * prompt；参考/子指令/脚本不预载（靠底座 read_file/shell 按需取）；改正文即时生效（无缓存）。
 */
class ProgressiveDisclosureTest {

  private static final String BODY = "你是每日订单对账助手。";
  private static final String REFERENCE = "参考资料：跨天入账属正常。";
  private static final String SCRIPT = "print('reconcile')";

  @TempDir Path root;

  private Path agentsDir;
  private Path skillsRoot;
  private Path agentDir;

  @BeforeEach
  void setUp() throws IOException {
    agentsDir = root.resolve("agents");
    skillsRoot = root.resolve("skills");
    agentDir = agentsDir.resolve("daily-reconcile");
    Files.createDirectories(agentDir.resolve("scripts"));
    Files.createDirectories(skillsRoot);

    Files.writeString(agentDir.resolve("REFERENCE.md"), REFERENCE, StandardCharsets.UTF_8);
    Files.writeString(
        agentDir.resolve("scripts").resolve("reconcile.py"), SCRIPT, StandardCharsets.UTF_8);
    writeBody(BODY);
  }

  private void writeBody(String body) throws IOException {
    Files.writeString(
        agentDir.resolve("AGENT.md"), "---\nname: daily-reconcile\n---\n\n" + body + "\n");
  }

  private static Profile profile() {
    return new Profile(
        "daily-reconcile",
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of("shell", "read_file"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(null, null));
  }

  private ContextLoader loader() {
    return new ContextLoader(agentsDir, root, skillsRoot);
  }

  @Test
  @DisplayName("正文进 system prompt，参考与脚本都不预载")
  void systemPrompt_containsBodyButNotReferencesOrScripts() {
    String prompt = loader().systemPrompt(profile());

    assertThat(prompt).contains(BODY);
    assertThat(prompt).as("REFERENCE.md 必须留在目录里等 read_file 按需读").doesNotContain(REFERENCE);
    assertThat(prompt).as("脚本代码不得进上下文").doesNotContain(SCRIPT);
  }

  @Test
  @DisplayName("改正文后不重启，下一次组装立即读到新内容")
  void systemPrompt_bodyEditTakesEffectImmediately() throws IOException {
    ContextLoader loader = loader();
    assertThat(loader.systemPrompt(profile())).contains(BODY);

    writeBody("你是升级后的对账助手，先看差异分级。");

    assertThat(loader.systemPrompt(profile())).contains("你是升级后的对账助手，先看差异分级。").doesNotContain(BODY);
  }

  @Test
  @DisplayName("子指令经 skills/ 软连接绑定：只注入元数据与路径，正文不预载")
  void systemPrompt_skillBindingInjectsMetadataOnly() throws IOException {
    Path skillDir = Files.createDirectories(skillsRoot.resolve("report-format"));
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        "---\nname: report-format\ndescription: 对账差异报告的分级规则\n---\n\n# 完整分级细则\nP0 立即处理。",
        StandardCharsets.UTF_8);
    Path link = agentDir.resolve("skills").resolve("report-format");
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(link, skillDir);

    String prompt = loader().systemPrompt(profile());

    assertThat(prompt).contains("report-format").contains("对账差异报告的分级规则");
    assertThat(prompt).as("子指令正文要等 read_file 才进上下文").doesNotContain("P0 立即处理。");
  }

  @Test
  @DisplayName("skills/ 下放普通 .md 会被判越界——示例必须用软连接形态")
  void systemPrompt_plainMarkdownUnderSkills_isRejected() throws IOException {
    Files.createDirectories(agentDir.resolve("skills"));
    Files.writeString(
        agentDir.resolve("skills").resolve("report-format.md"), "# 报告规范", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> loader().systemPrompt(profile()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Skill 绑定越界");
  }

  @Test
  @DisplayName("示例 Agent 目录：正文进 prompt，参考/脚本不进，子指令只进元数据")
  void sampleAgent_followsProgressiveDisclosure() throws IOException {
    Path sampleRoot = copySampleIntoRoot();
    bindSampleSkill(sampleRoot);

    String prompt =
        new ContextLoader(sampleRoot.resolve("agents"), sampleRoot, sampleRoot.resolve("skills"))
            .systemPrompt(sampleProfile());

    assertThat(prompt).contains("你是每日订单对账助手");
    // 子指令走软连接绑定：只注入名称、描述与路径，正文要等 read_file。
    assertThat(prompt).contains("report-format").contains("对账差异报告的结构");
    // 正文里出现「已知可接受差异」四个字是引导语（指向 REFERENCE.md），不算预载；
    // 真正判定预载的是参考文件里的独有内容。
    assertThat(prompt).doesNotContain("跨天入账：22:00 后订单清算次日入账");
    assertThat(prompt).doesNotContain("RECON_ORDERS_CSV");
    assertThat(prompt).as("子指令正文不得预载").doesNotContain("可能资损，立即升级");
  }

  private static Profile sampleProfile() {
    return new Profile(
        "daily-reconcile",
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of("shell", "read_file", "notify", "save_memory"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(null, null));
  }

  /** 把 src/test/resources 下的示例 Agent 与公共 Skill 复制到临时根，模拟真实工作区布局。 */
  private Path copySampleIntoRoot() throws IOException {
    Path temp = root.resolve("sample");
    copyTree(resourceRoot().resolve("agents"), temp.resolve("agents"));
    copyTree(resourceRoot().resolve("skills"), temp.resolve("skills"));
    return temp;
  }

  /**
   * 示例夹具不含 {@code skills/}（git 存不了空目录，且 core.symlinks=false 会把入库软连接还原成普通文件），
   * 所以按真实工作区的做法在复制后建链——这条路径与 quickstart §4 的人工步骤一致。
   */
  private static void bindSampleSkill(Path sampleRoot) throws IOException {
    Path target = sampleRoot.resolve("skills").resolve("report-format");
    Path link =
        sampleRoot
            .resolve("agents")
            .resolve("daily-reconcile")
            .resolve("skills")
            .resolve("report-format");
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(link, target);
  }

  private static Path resourceRoot() {
    try {
      return Path.of(
              ProgressiveDisclosureTest.class
                  .getResource("/agents/daily-reconcile/AGENT.md")
                  .toURI())
          .getParent()
          .getParent()
          .getParent();
    } catch (Exception e) {
      throw new IllegalStateException("测试资源缺失：/agents/daily-reconcile/AGENT.md", e);
    }
  }

  private static void copyTree(Path source, Path target) throws IOException {
    List<Path> entries;
    try (Stream<Path> stream = Files.walk(source)) {
      entries = stream.sorted(Comparator.naturalOrder()).toList();
    }
    for (Path path : entries) {
      Path destination = target.resolve(source.relativize(path).toString());
      if (Files.isDirectory(path)) {
        Files.createDirectories(destination);
      } else {
        Files.createDirectories(destination.getParent());
        Files.copy(path, destination);
      }
    }
  }
}
