package com.sableos.core.agent;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Agent 加载器（技术方案 §8.2）：扫 {@code <agentsDir>/<name>/AGENT.md}，把 frontmatter 派生成 {@link Profile} 注册进
 * {@link ProfileRegistry}。
 *
 * <p>坏目录（无 AGENT.md / YAML 破损 / name 与目录名不符 / provider 未登记 / model 为空）只记错误日志并跳过， 不阻断其余
 * Agent，也不阻断启动（spec FR-001 / SC-004）。
 */
public class AgentLoader {

  private static final Logger LOG = LoggerFactory.getLogger(AgentLoader.class);

  private static final String AGENT_FILE_NAME = "AGENT.md";
  private static final String FRONTMATTER_FENCE = "---";
  private static final String CRLF = "\r\n";
  private static final String LINE_BREAK = "\n";

  private final Path agentsDir;
  private final Set<String> knownProviders;
  private final Set<String> knownTools;

  /**
   * @param agentsDir Agent 目录根（其下每个子目录一个 Agent）
   * @param knownProviders 实例清单中已登记的 provider 名集合（来自 sableos.providers）
   */
  public AgentLoader(Path agentsDir, Set<String> knownProviders) {
    this(agentsDir, knownProviders, Set.of());
  }

  /**
   * @param agentsDir Agent 目录根（其下每个子目录一个 Agent）
   * @param knownProviders 实例清单中已登记的 provider 名集合（来自 sableos.providers）
   * @param knownTools 已注册的工具名集合（来自 {@code ToolRegistry}）；仅用于告警，未命中不拒绝该 Agent
   */
  public AgentLoader(Path agentsDir, Set<String> knownProviders, Set<String> knownTools) {
    this.agentsDir = agentsDir;
    this.knownProviders = Set.copyOf(knownProviders);
    this.knownTools = Set.copyOf(knownTools);
  }

  /**
   * 全量扫描并派生出 Profile 注册表。
   *
   * @return 装载完成的注册表；目录不存在或不可扫描时为空注册表（不抛错）
   */
  public ProfileRegistry loadAll() {
    ProfileRegistry registry = new ProfileRegistry();
    if (!Files.isDirectory(agentsDir)) {
      LOG.info("Agent 目录不存在，跳过加载：dir={}", safe(String.valueOf(agentsDir)));
      return registry;
    }
    for (Path agentDir : listAgentDirs()) {
      try {
        Profile profile = loadAgent(agentDir);
        registry.register(profile);
        LOG.info("Agent 加载成功：name={}", safe(profile.name()));
      } catch (IOException e) {
        LOG.error(
            "Agent 目录读取失败，已跳过：dir={} reason={}", safe(dirName(agentDir)), safe(e.getMessage()));
      } catch (RuntimeException e) {
        LOG.error("Agent 定义非法，已跳过：dir={} reason={}", safe(dirName(agentDir)), safe(e.getMessage()));
      }
    }
    return registry;
  }

  private List<Path> listAgentDirs() {
    try (Stream<Path> stream = Files.list(agentsDir)) {
      return stream.filter(Files::isDirectory).sorted().toList();
    } catch (IOException e) {
      LOG.error(
          "Agent 目录扫描失败：dir={} reason={}", safe(String.valueOf(agentsDir)), safe(e.getMessage()));
      return List.of();
    }
  }

  private Profile loadAgent(Path agentDir) throws IOException {
    return deriveProfile(agentDir);
  }

  /**
   * 按目录派生 Profile：读 {@code <agentDir>/AGENT.md}，以目录名参与 name 一致性校验。
   *
   * <p>运行时登记（第 30 节 API / 文件监听）必须走本入口，才能与启动扫描抛出同一异常与同一消息。
   *
   * @param agentDir Agent 目录（其名即 Agent 名）
   * @return 派生出的运行配置
   * @throws IOException 主定义文件缺失或不可读
   * @throws IllegalArgumentException 结构或引用违规（消息点名原因）
   */
  public Profile deriveProfile(Path agentDir) throws IOException {
    Path agentFile = agentDir.resolve(AGENT_FILE_NAME);
    if (!Files.isRegularFile(agentFile)) {
      throw new IllegalArgumentException("目录下缺少 " + AGENT_FILE_NAME);
    }
    String markdown = Files.readString(agentFile, StandardCharsets.UTF_8);
    Profile profile = deriveProfile(dirName(agentDir), markdown);
    warnUnknownTools(profile);
    return profile;
  }

  /** 未注册工具只告警、不拒绝该 Agent：工具可能由 MCP 在 Agent 加载之后才注册， 丢弃整个 Agent 会造成「工具到齐了但 Agent 没了」的误伤（FR-005）。 */
  private void warnUnknownTools(Profile profile) {
    if (knownTools.isEmpty()) {
      return;
    }
    for (String tool : profile.tools()) {
      if (!knownTools.contains(tool)) {
        LOG.warn("Agent 引用了未注册的能力，仍完成登记：name={} tool={}", safe(profile.name()), safe(tool));
      }
    }
  }

  private static String dirName(Path dir) {
    Path fileName = dir.getFileName();
    return (fileName == null) ? String.valueOf(dir) : fileName.toString();
  }

  /** 把 AGENT.md 的 frontmatter 派生成 Profile；任何结构/引用违规都抛 IllegalArgumentException（点名原因）。 */
  Profile deriveProfile(String directoryName, String agentMarkdown) {
    Map<String, Object> frontmatter = parseFrontmatter(agentMarkdown);

    String name = asText(frontmatter.get("name"));
    if (name == null) {
      throw new IllegalArgumentException("frontmatter 缺少 name");
    }
    if (!directoryName.equals(name)) {
      throw new IllegalArgumentException("name(" + name + ") 与目录名(" + directoryName + ") 不一致");
    }

    Map<String, Object> providerMap = asMap(frontmatter.get("provider"));
    if (providerMap == null) {
      throw new IllegalArgumentException("Agent " + name + " 缺少 provider 段");
    }
    String providerName = asText(providerMap.get("name"));
    if (providerName == null) {
      throw new IllegalArgumentException("Agent " + name + " 的 provider.name 为空");
    }
    if (!knownProviders.contains(providerName)) {
      throw new IllegalArgumentException(
          "Agent " + name + " 引用的 provider 未在 sableos.providers 清单登记：" + providerName);
    }
    String model = asText(providerMap.get("model"));
    if (model == null) {
      throw new IllegalArgumentException(
          "Agent " + name + " 的 provider.model 为空（provider=" + providerName + "）");
    }

    Map<String, Object> identityMap = asMap(frontmatter.get("identity"));
    Profile.Identity identity =
        (identityMap == null)
            ? null
            : new Profile.Identity(
                asText(identityMap.get("agent_name")), asText(identityMap.get("prompt")));

    Map<String, Object> settingsMap = asMap(frontmatter.get("settings"));
    Profile.Settings settings =
        (settingsMap == null)
            ? new Profile.Settings(null, null)
            : new Profile.Settings(
                intValue(settingsMap.get("max_iterations")),
                intValue(settingsMap.get("max_history_turns")));

    return new Profile(
        name,
        asText(frontmatter.get("description")),
        identity,
        new Profile.ProviderRef(providerName, model, doubleValue(providerMap.get("temperature"))),
        asTextList(frontmatter.get("tools")),
        asTextList(frontmatter.get("mcp_servers")),
        asTextList(frontmatter.get("channels")),
        parseSchedules(frontmatter.get("schedules")),
        asTextList(frontmatter.get("bootstrap")),
        settings);
  }

  private static Map<String, Object> parseFrontmatter(String markdown) {
    String normalized = markdown.replace(CRLF, LINE_BREAK);
    if (!normalized.startsWith(FRONTMATTER_FENCE + LINE_BREAK)) {
      throw new IllegalArgumentException("缺少 frontmatter（--- 围栏）");
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
      throw new IllegalArgumentException("frontmatter 围栏未闭合");
    }
    String yamlText = normalized.substring(FRONTMATTER_FENCE.length() + 1, closingStart);
    try {
      Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
      Map<String, Object> map = asMap(loaded);
      if (map == null) {
        throw new IllegalArgumentException("frontmatter 不是 YAML 映射");
      }
      return map;
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("frontmatter YAML 解析失败：" + e.getMessage(), e);
    }
  }

  private static List<Profile.ScheduleConfig> parseSchedules(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<Profile.ScheduleConfig> schedules = new ArrayList<>(list.size());
    for (Object item : list) {
      Map<String, Object> scheduleMap = asMap(item);
      if (scheduleMap == null) {
        continue;
      }
      schedules.add(
          new Profile.ScheduleConfig(
              asText(scheduleMap.get("id")),
              asText(scheduleMap.get("cron")),
              asText(scheduleMap.get("zone")),
              asText(scheduleMap.get("message"))));
    }
    return List.copyOf(schedules);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (value instanceof Map) ? (Map<String, Object>) value : null;
  }

  private static String asText(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private static List<String> asTextList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> result = new ArrayList<>(list.size());
    for (Object item : list) {
      String text = asText(item);
      if (text != null) {
        result.add(text);
      }
    }
    return List.copyOf(result);
  }

  private static Integer intValue(Object value) {
    return (value instanceof Number number) ? number.intValue() : null;
  }

  private static Double doubleValue(Object value) {
    return (value instanceof Number number) ? number.doubleValue() : null;
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法）。 */
  private static String safe(String value) {
    return (value == null) ? null : value.replaceAll("[\r\n]", "_");
  }
}
