package com.sableos.core.context;

import com.sableos.core.profile.Profile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 上下文来源加载（技术方案 §8.3）：把 Agent 定义正文、启动信息（Bootstrap）与绑定的 Skill 说明拼成 system prompt。
 *
 * <p><b>每次调用现读文件、不缓存</b>（FR-013）：改文件后下一次组装立即读到新内容。缺失处理分两档——Agent 正文与 Bootstrap 文件缺失记 WARN
 * 后跳过（不静默），显式绑定的 Skill 软连接断裂或缺 {@code SKILL.md} 则报错点名（FR-014）。
 *
 * <p>本类只剥离 {@code AGENT.md} 的 {@code ---} frontmatter 围栏（不解析 YAML、不调 {@code AgentLoader}
 * 私有方法，research D-11）；{@code SKILL.md} 需读其 frontmatter 的 {@code name}/{@code description}，故对该文件做
 * YAML 解析。
 */
public final class ContextLoader {

  private static final Logger LOG = LoggerFactory.getLogger(ContextLoader.class);

  private static final String AGENT_FILE_NAME = "AGENT.md";
  private static final String SKILL_FILE_NAME = "SKILL.md";
  private static final String SKILLS_DIR_NAME = "skills";
  private static final String FRONTMATTER_FENCE = "---";
  private static final String LINE_BREAK = "\n";
  private static final String CRLF = "\r\n";
  private static final String SECTION_SEPARATOR = "\n\n";
  private static final String SKILLS_HEADING = "## Skills";

  private final Path agentsDir;
  private final Path bootstrapDir;
  private final Path skillsRoot;

  /**
   * @param agentsDir Agent 目录根（其下 {@code <name>/AGENT.md}）
   * @param bootstrapDir Bootstrap 文件的定位根（{@code Profile.bootstrap()} 的相对路径基准）
   * @param skillsRoot 公共 Skill 根（软连接目标必须落其内，防越界）
   */
  public ContextLoader(Path agentsDir, Path bootstrapDir, Path skillsRoot) {
    this.agentsDir = agentsDir;
    this.bootstrapDir = bootstrapDir;
    this.skillsRoot = skillsRoot;
  }

  /**
   * 组装本次 Profile 的 system prompt（角色设定 + Agent 正文 + Bootstrap + Skill 说明）；每次调用重新读文件。
   *
   * @param profile 本次处理的 Agent 运行配置
   * @return system prompt 文本（各段以空行分隔；无内容段整段省略）
   */
  public String systemPrompt(Profile profile) {
    StringBuilder prompt = new StringBuilder();
    appendSection(prompt, identityPrompt(profile));
    appendSection(prompt, agentBody(profile.name()));
    for (String bootstrapFile : profile.bootstrap()) {
      appendSection(prompt, bootstrapContent(bootstrapFile));
    }
    appendSkills(prompt, profile.name());
    return prompt.toString().strip();
  }

  private static String identityPrompt(Profile profile) {
    Profile.Identity identity = profile.identity();
    return (identity == null) ? null : identity.prompt();
  }

  private String agentBody(String agentName) {
    Path agentFile = agentsDir.resolve(agentName).resolve(AGENT_FILE_NAME);
    if (!Files.isRegularFile(agentFile)) {
      LOG.warn("Agent 定义正文缺失，已跳过：name={} file={}", safe(agentName), safe(agentFile.toString()));
      return null;
    }
    return stripFrontmatter(readFile(agentFile));
  }

  private String bootstrapContent(String bootstrapFile) {
    Path file = bootstrapDir.resolve(bootstrapFile);
    if (!Files.isRegularFile(file)) {
      LOG.warn("Bootstrap 文件缺失，已跳过：file={}", safe(file.toString()));
      return null;
    }
    return readFile(file);
  }

  private void appendSkills(StringBuilder prompt, String agentName) {
    Path skillsDir = agentsDir.resolve(agentName).resolve(SKILLS_DIR_NAME);
    if (!Files.isDirectory(skillsDir)) {
      return;
    }
    List<Path> entries = listSorted(skillsDir);
    if (entries.isEmpty()) {
      return;
    }
    StringBuilder block = new StringBuilder(SKILLS_HEADING);
    for (Path entry : entries) {
      block.append(LINE_BREAK).append(renderSkill(entry));
    }
    prompt.append(SECTION_SEPARATOR).append(block);
  }

  private String renderSkill(Path entry) {
    Path resolved = resolveSkill(entry);
    Path skillFile = resolved.resolve(SKILL_FILE_NAME);
    if (!Files.isRegularFile(skillFile)) {
      throw new IllegalArgumentException(
          "Skill 绑定缺失 " + SKILL_FILE_NAME + "：" + safe(skillName(entry)));
    }
    Map<String, Object> frontmatter = parseFrontmatter(readFile(skillFile));
    String name = asText(frontmatter.get("name"));
    String description = asText(frontmatter.get("description"));
    return "- "
        + (name == null ? skillName(entry) : name)
        + ": "
        + (description == null ? "" : description)
        + " (path: "
        + resolved
        + ")";
  }

  /** 解析软连接目标并校验其落 {@code skillsRoot} 内；断裂或越界一律报错点名。 */
  private Path resolveSkill(Path entry) {
    Path resolved;
    try {
      resolved = entry.toRealPath();
    } catch (IOException e) {
      throw new IllegalArgumentException("Skill 软连接断裂或不可读：" + safe(skillName(entry)), e);
    }
    Path root;
    try {
      root = skillsRoot.toRealPath();
    } catch (IOException e) {
      throw new IllegalStateException("Skill 根目录不可读：" + safe(skillsRoot.toString()), e);
    }
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("Skill 绑定越界（目标不在 Skill 根目录内）：" + safe(skillName(entry)));
    }
    return resolved;
  }

  private static String skillName(Path entry) {
    Path fileName = entry.getFileName();
    return (fileName == null) ? String.valueOf(entry) : fileName.toString();
  }

  private static List<Path> listSorted(Path dir) {
    try (Stream<Path> stream = Files.list(dir)) {
      return stream.sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException("目录扫描失败：" + safe(dir.toString()), e);
    }
  }

  private static void appendSection(StringBuilder prompt, String section) {
    if (section == null || section.isBlank()) {
      return;
    }
    if (prompt.length() > 0) {
      prompt.append(SECTION_SEPARATOR);
    }
    prompt.append(section.strip());
  }

  /** 剥离 {@code ---} frontmatter 围栏，返回正文；无围栏或围栏未闭合时按原文返回（不解析 YAML）。 */
  static String stripFrontmatter(String markdown) {
    String normalized = markdown.replace(CRLF, LINE_BREAK);
    if (!normalized.startsWith(FRONTMATTER_FENCE + LINE_BREAK)) {
      return normalized.strip();
    }
    int cursor = FRONTMATTER_FENCE.length() + 1;
    while (cursor < normalized.length()) {
      int lineEnd = normalized.indexOf('\n', cursor);
      int end = (lineEnd < 0) ? normalized.length() : lineEnd;
      if (FRONTMATTER_FENCE.equals(normalized.substring(cursor, end).trim())) {
        return normalized.substring(end).strip();
      }
      cursor = end + 1;
    }
    return normalized.strip();
  }

  private static Map<String, Object> parseFrontmatter(String markdown) {
    String normalized = markdown.replace(CRLF, LINE_BREAK);
    if (!normalized.startsWith(FRONTMATTER_FENCE + LINE_BREAK)) {
      return Map.of();
    }
    int closingStart = -1;
    int cursor = FRONTMATTER_FENCE.length() + 1;
    while (cursor < normalized.length()) {
      int lineEnd = normalized.indexOf('\n', cursor);
      int end = (lineEnd < 0) ? normalized.length() : lineEnd;
      if (FRONTMATTER_FENCE.equals(normalized.substring(cursor, end).trim())) {
        closingStart = cursor;
        break;
      }
      cursor = end + 1;
    }
    if (closingStart < 0) {
      return Map.of();
    }
    String yamlText = normalized.substring(FRONTMATTER_FENCE.length() + 1, closingStart);
    Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
    if (!(loaded instanceof Map<?, ?> map)) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  private static String readFile(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("文件读取失败：" + safe(file.toString()), e);
    }
  }

  private static String asText(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).strip();
    return text.isEmpty() ? null : text;
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法）。 */
  private static String safe(String value) {
    return (value == null) ? null : value.replaceAll("[\r\n]", "_");
  }
}
