package com.sableos.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sableos.core.profile.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * 上下文来源加载验收：全段注入（角色设定 + Agent 正文 + Bootstrap + Skill 说明）、无缓存现读、缺失分档处理（正文/Bootstrap 记 WARN，Skill
 * 绑定缺失报错点名）。
 */
class ContextLoaderTest {

  @TempDir Path agentsDir;

  @TempDir Path bootstrapDir;

  @TempDir Path skillsRoot;

  private ContextLoader loader() {
    return new ContextLoader(agentsDir, bootstrapDir, skillsRoot);
  }

  private static Profile profile(String name, String identityPrompt, List<String> bootstrap) {
    return new Profile(
        name,
        "测试 Agent",
        new Profile.Identity(name, identityPrompt),
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        bootstrap,
        new Profile.Settings(null, null));
  }

  private Path writeAgent(String name, String markdown) throws IOException {
    Path dir = agentsDir.resolve(name);
    Files.createDirectories(dir);
    Path file = dir.resolve("AGENT.md");
    Files.writeString(file, markdown, StandardCharsets.UTF_8);
    return file;
  }

  private Path linkSkill(String agentName, String skillName, Path target) throws IOException {
    Path link = agentsDir.resolve(agentName).resolve("skills").resolve(skillName);
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(link, target);
    return link;
  }

  @Test
  @DisplayName("组装 system prompt：角色设定 + Agent 正文 + Bootstrap + Skill 说明（frontmatter 与 Skill 正文不注入）")
  void systemPrompt_loadsBodyIdentityBootstrapAndSkills() throws IOException {
    writeAgent(
        "ops-agent",
        """
        ---
        name: ops-agent
        description: 运维助手
        ---

        你是负责线上巡检的运维助手。
        """);
    Files.writeString(bootstrapDir.resolve("base.md"), "启动信息：优先保障可用性。", StandardCharsets.UTF_8);
    Path skillDir = skillsRoot.resolve("memory-skill");
    Files.createDirectories(skillDir);
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        """
        ---
        name: memory-skill
        description: 记忆读写技能
        ---

        Skill 正文（不应被预载）。
        """,
        StandardCharsets.UTF_8);
    linkSkill("ops-agent", "memory-skill", skillDir);

    String prompt = loader().systemPrompt(profile("ops-agent", "你是运维总控。", List.of("base.md")));

    assertThat(prompt).contains("你是运维总控。");
    assertThat(prompt).contains("你是负责线上巡检的运维助手。");
    assertThat(prompt).contains("启动信息：优先保障可用性。");
    assertThat(prompt).contains("## Skills");
    assertThat(prompt).contains("memory-skill: 记忆读写技能");
    assertThat(prompt).contains(skillDir.toRealPath().toString());
    assertThat(prompt).doesNotContain("description: 运维助手");
    assertThat(prompt).doesNotContain("Skill 正文（不应被预载）。");
  }

  @Test
  @DisplayName("改文件后下一次组装立即读到新内容（无缓存）")
  void systemPrompt_fileChanged_reflectsNewContentImmediately() throws IOException {
    Path agentFile = writeAgent("ops-agent", "第一版正文。");

    String first = loader().systemPrompt(profile("ops-agent", null, List.of()));
    assertThat(first).contains("第一版正文。");

    Files.writeString(agentFile, "第二版正文。", StandardCharsets.UTF_8);
    String second = loader().systemPrompt(profile("ops-agent", null, List.of()));

    assertThat(second).contains("第二版正文。");
    assertThat(second).doesNotContain("第一版正文。");
  }

  @Test
  @DisplayName("Skill 引用缺失报错并点名")
  void systemPrompt_missingSkillBinding_throwsNamed() throws IOException {
    writeAgent("ops-agent", "正文。");
    linkSkill("ops-agent", "ghost-skill", skillsRoot.resolve("ghost-skill"));

    assertThatThrownBy(() -> loader().systemPrompt(profile("ops-agent", null, List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ghost-skill");
  }

  @Test
  @DisplayName("Bootstrap 缺失至少 WARN（不静默跳过）")
  void systemPrompt_missingBootstrap_logsWarn() {
    Logger logger = (Logger) LoggerFactory.getLogger(ContextLoader.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      String prompt = loader().systemPrompt(profile("ops-agent", null, List.of("NO_SUCH.md")));

      assertThat(prompt).isEmpty();
      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("NO_SUCH.md");
              });
    } finally {
      logger.detachAppender(appender);
    }
  }
}
