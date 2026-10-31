package com.sableos.core.agent;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.scheduler.AgentScheduler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Agent 生命周期编排（第 30 节）：三条录入路径（API 创建 / 目录监听 / 启动扫描）都汇到同一个 {@link #register(Path)}。
 *
 * <p><b>职责边界</b>：本类只做编排——脚手架、回滚、注销定序、生成草稿；文件读写归 {@link AgentStore}，派生归 {@link AgentLoader}， 定时归
 * {@link AgentScheduler}。本类不产生 HTTP 语义：{@link #get} 返回 {@link Optional}，404 由 web 层决定（core 不反向依赖
 * web）。
 *
 * <p><b>异常口径</b>：定义非法与参数非法一律 {@link IllegalArgumentException}（web 全局处理器映射 400）；生成用的模型未配置时抛 {@link
 * com.sableos.core.provider.ProviderNotConfiguredException}（web 映射 503）。
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "确认误报：协作者均为 Spring 注入的共享单例（AgentLoader / ProfileRegistry / AgentScheduler / AgentStore /"
            + " ProviderService），构造注入共享同一引用正是意图；本类不返回它们，也不暴露其内部集合。"
            + "撤销条件：一旦新增暴露上述字段的 getter 或 public 字段，须重新评估。")
public class AgentLifecycleService {

  private static final Logger LOG = LoggerFactory.getLogger(AgentLifecycleService.class);

  private static final String AGENT_FILE = "AGENT.md";
  private static final String REFERENCE_FILE = "REFERENCE.md";
  private static final String SCRIPT_PATH = "scripts/example.py";
  private static final String AUTHOR_PROFILE_NAME = "agent-author";
  private static final String AUTHOR_SESSION_PREFIX = "agent-author:";
  private static final String FILE_MARKER_PREFIX = "===FILE:";
  private static final String CODE_FENCE = "```";
  private static final String NEWLINE = "\n";
  private static final String PARENT_SEGMENT = "..";
  private static final String SKILLS_NAMESPACE = "skills";
  private static final int MAX_DESCRIPTION_CHARS = 2000;

  /** 脚手架写进 provider.model 的占位符：model 非空才能通过派生校验，真名由用户或生成流程填入。 */
  private static final String SCAFFOLD_MODEL_PLACEHOLDER = "请在此填写模型名";

  /** 脚手架正文：与 29 节示例 Agent 的口径一致（按需读参考 / 跑脚本，产出不预载）。 */
  private static final String AGENT_MD_BODY =
      """
      在这里写这个 Agent 的任务指令（正文）。被触发时它会照做。

      - 需要搜索 / 查最新 / 联网核实时：先调用 web_search，结果为空再用 http_get，不要只凭训练知识编造；
      - 参考资料放 REFERENCE.md，拿不准时用 read_file 读；
      - 脚本放 scripts/，正文让 shell 跑它，产出进上下文、代码不进；
      - 需要长期记住的事用 save_memory；回忆时用 recall_memory。
      """;

  private static final String SCRIPT_TEMPLATE =
      """
      #!/usr/bin/env python3
      \"\"\"示例脚本：Agent 用 shell 跑它拿确定性数据；stdout 进上下文，代码本身不进。\"\"\"
      import json
      import sys

      json.dump({"hello": "world"}, sys.stdout, ensure_ascii=False)
      """;

  private static final String REFERENCE_TEMPLATE =
      """
      # 参考资料

      把字段字典、已知边界、阈值、联系人放这里。Agent 拿不准某个细节时，用 read_file 读它。
      """;

  /** 「用大模型生成 Agent」的系统说明：约束模型只吐可解析的 AGENT.md（必要时附脚本），不加解释、不加代码围栏。 */
  private static final String AGENT_AUTHOR_PROMPT =
      """
      你是 SableOS 的 Agent 作者。根据末尾的「需求」，产出一个**可直接运行**的 Agent。

      硬性规则：
      1. AGENT.md 必须以 YAML frontmatter 开头与结尾（--- 与 ---），frontmatter 必须含 name、description、\
      identity(agent_name/prompt)、provider(name/model)、settings(max_iterations/max_history_turns)；有定时需求再加 schedules。
      2. name 必须是「{name}」；provider 必须原样使用下面这段（不要改写）：
      provider:
        name: {provider}
        model: {model}
      3. tools 只能从下面【可用工具】里按需挑选，**绝不允许编造清单以外的工具名**。
      4. schedules 每条必须包含：id（Agent 内唯一的配置键）、cron（Spring 6 段 cron，如 "0 0 9 * * *"）、\
      zone（时区，如 Asia/Shanghai）、message（到点发给 Agent 的触发语）。
      5. 你可以按需额外产出 Agent 自有文件——脚本放 scripts/<名>、参考资料放 REFERENCE.md。绝不产出 skills/**。
      6. 输出格式：多个文件时，每个文件前**单独一行**写分隔符 `===FILE: <相对路径>===`（第一个必须是 AGENT.md）；\
      若只需要 AGENT.md 可不用分隔符直接输出它。不要用 Markdown 代码围栏（```）包整个输出，也不要任何额外解释。

      【可用工具】（tools 只能从这里选，禁止编造）
      {tools}

      需求：
      """;

  /** 生成草稿里允许的相对路径：拒绝绝对路径、`..` 段与 skills/ 前缀（skills 只许软链，29 节约定）。 */
  private static final String TOOL_LIST_EMPTY = "（当前无可用工具）";

  private final AgentLoader agentLoader;
  private final ProfileRegistry profileRegistry;
  private final AgentScheduler agentScheduler;
  private final AgentStore agentStore;
  private final ProviderService providerService;
  private final String defaultProvider;
  private final String authorProvider;
  private final String authorModel;
  private final List<String> knownToolNames;

  public AgentLifecycleService(
      AgentLoader agentLoader,
      ProfileRegistry profileRegistry,
      AgentScheduler agentScheduler,
      AgentStore agentStore,
      ProviderService providerService,
      String defaultProvider,
      String authorProvider,
      String authorModel,
      List<String> knownToolNames) {
    this.agentLoader = agentLoader;
    this.profileRegistry = profileRegistry;
    this.agentScheduler = agentScheduler;
    this.agentStore = agentStore;
    this.providerService = providerService;
    this.defaultProvider = defaultProvider;
    this.authorProvider = authorProvider;
    this.authorModel = authorModel;
    this.knownToolNames = (knownToolNames == null) ? List.of() : List.copyOf(knownToolNames);
  }

  // ---------------------------------------------------------------- 查询

  /** 按名取；未注册返回空（404 由 web 层判定）。 */
  public Optional<Profile> get(String name) {
    return Optional.ofNullable(name == null ? null : profileRegistry.get(name));
  }

  /** 全部已注册 Agent（按名排序，便于列表页稳定展示）。 */
  public List<Profile> list() {
    return profileRegistry.all().stream().sorted(Comparator.comparing(Profile::name)).toList();
  }

  // ---------------------------------------------------------------- 创建

  /**
   * 创建 Agent：按模板脚手架出一组文件并注册，全程不依赖模型。
   *
   * <p>同名先拒（不写任何文件）；写出后任一环节失败都回滚已建目录，不留半个 Agent。
   *
   * @param name Agent 名（同时决定目录名）
   * @param description 描述
   * @return 新注册的 Profile
   */
  public Profile create(String name, String description) {
    requireValidName(name);
    if (profileRegistry.exists(name)) {
      throw new IllegalArgumentException("Agent 已存在：" + name);
    }
    String provider = resolveScaffoldProvider();
    if (provider == null || provider.isBlank()) {
      // 脚手架必须写出一个已登记的 provider，否则派生必然失败、回滚后只留一条「provider.name 为空」的难懂错误
      throw new IllegalArgumentException(
          "尚未配置任何 provider：请先在 sableos.providers 登记至少一个 provider 再创建 Agent");
    }
    Path agentDir = null;
    try {
      agentDir = agentStore.writeAll(name, scaffold(name, description, provider));
      return register(agentDir);
    } catch (RuntimeException e) {
      rollbackCreated(agentDir);
      throw e;
    }
  }

  private void rollbackCreated(Path agentDir) {
    try {
      agentStore.delete(agentDir);
    } catch (RuntimeException cleanup) {
      LOG.error(
          "创建失败后的目录回滚也失败了，需人工清理：dir={} reason={}",
          safe(String.valueOf(agentDir)),
          safe(cleanup.getMessage()),
          cleanup);
    }
  }

  /**
   * 唯一登记入口（第 30 节）：API 创建、目录监听、启动扫描全部走这里，异常类型与消息才可能真正一致。
   *
   * @param agentDir Agent 目录（其名即 Agent 名）
   * @return 登记完成的 Profile
   */
  public Profile register(Path agentDir) {
    Profile profile = derive(agentDir);
    registerInto(profile);
    return profile;
  }

  /**
   * 刷新一个已存在或被改动的 Agent 目录：先注销该 Agent 名下全部旧定时，再按目录重注册。
   *
   * <p>先注销是必须的：{@code registerProfile} 只会取消「同一 scheduleId」的旧句柄，配置里已删除的那条任务不会被它停掉。
   *
   * @param agentDir Agent 目录
   * @return 重新登记后的 Profile
   */
  public Profile refresh(Path agentDir) {
    String name = dirName(agentDir);
    Profile existing = profileRegistry.get(name);
    if (existing != null) {
      agentScheduler.unregisterProfile(existing);
    }
    return register(agentDir);
  }

  /** 目录已被手工删除：注销定时 + 移出索引（不归档，归档只走显式删除）。 */
  public void unregisterByDir(Path agentDir) {
    String name = dirName(agentDir);
    Profile existing = profileRegistry.get(name);
    if (existing == null) {
      return;
    }
    agentScheduler.unregisterProfile(existing);
    profileRegistry.remove(name);
    LOG.info("Agent 目录已消失，已注销：name={}", safe(name));
  }

  // ---------------------------------------------------------------- 更新 / 保存

  /** 覆写整份 AGENT.md：先纯内存校验（非法不写坏目录），再写入并重注册。 */
  public Profile update(String name, String agentMarkdown) {
    return saveFiles(name, Map.of(AGENT_FILE, agentMarkdown));
  }

  /**
   * 保存一组 Agent 文件并即时生效：先校验 AGENT.md 可解析（非法 400、不写坏目录）→ 写入 → 重注册。
   *
   * @param name Agent 名
   * @param files 相对路径 → 内容（必须含 AGENT.md）
   * @return 更新后的 Profile
   */
  public Profile saveFiles(String name, Map<String, String> files) {
    requireValidName(name);
    String agentMarkdown = files == null ? null : files.get(AGENT_FILE);
    if (agentMarkdown == null || agentMarkdown.isBlank()) {
      throw new IllegalArgumentException("缺少 AGENT.md 内容");
    }
    agentLoader.parse(agentMarkdown, name); // 先校验再落盘：非法定义不写进目录
    Path agentDir = agentStore.writeAll(name, files);
    return refresh(agentDir);
  }

  // ---------------------------------------------------------------- 删除

  /**
   * 删除：注销定时 → 移出索引 → 目录归档。
   *
   * <p>顺序不能反：先停定时可以避免窗口期内 cron 触发到已注销的 Agent；归档放最后，保证前两步失败时磁盘仍完整。
   *
   * @param name Agent 名
   */
  public void delete(String name) {
    Profile profile = profileRegistry.get(name);
    if (profile == null) {
      return; // 幂等；404 由 web 层在调用前判定
    }
    agentScheduler.unregisterProfile(profile);
    profileRegistry.remove(name);
    agentStore.archive(name);
    LOG.info("Agent 已删除并归档：name={}", safe(name));
  }

  // ---------------------------------------------------------------- 生成

  /** 一段需求 → 一份合并的多文件文本（供前端预览）。不落盘、不注册。 */
  public String generate(String name, String description) {
    return generateText(name, description);
  }

  /**
   * 一段需求 → 一组文件草稿（`相对路径 → 内容`）。不落盘、不注册。
   *
   * <p>AGENT.md 走与落盘同一套 {@link AgentLoader#parse} 校验，保证「草稿能过」等于「写盘能过」。
   *
   * @param name 目标 Agent 名
   * @param description 需求描述
   * @return 相对路径 → 内容
   */
  public Map<String, String> generateFiles(String name, String description) {
    Map<String, String> files = parseGeneratedFiles(generateText(name, description));
    for (String path : files.keySet()) {
      if (isIllegalGeneratedPath(Path.of(path).normalize())) {
        throw new IllegalArgumentException("生成结果含非法或保留路径：" + path);
      }
    }
    String agentMarkdown = files.get(AGENT_FILE);
    if (agentMarkdown == null || agentMarkdown.isBlank()) {
      throw new IllegalArgumentException("生成结果缺少 " + AGENT_FILE);
    }
    agentLoader.parse(agentMarkdown, name); // 校验：解析不成合法定义就抛（→400）
    return files;
  }

  /** 生成草稿里不允许的路径：绝对路径、`..` 开头、以及 `skills/` 前缀（skills 只许软链，29 节约定）。 */
  private static boolean isIllegalGeneratedPath(Path relative) {
    if (relative.isAbsolute() || relative.startsWith(PARENT_SEGMENT)) {
      return true;
    }
    if (relative.getNameCount() == 0) {
      return false;
    }
    return SKILLS_NAMESPACE.equals(relative.getName(0).toString());
  }

  private String generateText(String name, String description) {
    requireValidName(name);
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("需求描述不能为空");
    }
    if (description.length() > MAX_DESCRIPTION_CHARS) {
      throw new IllegalArgumentException("需求描述超过 " + MAX_DESCRIPTION_CHARS + " 字符上限");
    }
    if (authorModel == null || authorModel.isBlank()) {
      throw new com.sableos.core.provider.ProviderNotConfiguredException(
          "未配置生成用模型（sableos.author.model），无法用大模型生成 Agent");
    }
    String genProvider =
        (authorProvider != null && !authorProvider.isBlank()) ? authorProvider : defaultProvider;
    if (genProvider == null || genProvider.isBlank()) {
      throw new com.sableos.core.provider.ProviderNotConfiguredException(
          "未配置 sableos.providers 或 sableos.author.provider，无法用大模型生成 Agent");
    }
    // 已存在的 Agent 沿用其 provider，保证生成结果仍可跑（换 provider 可能是重命名/迁移，不在生成里做）
    String outputProvider =
        profileRegistry.get(name) == null
            ? genProvider
            : profileRegistry.get(name).provider().name();
    String modelHint =
        profileRegistry.get(name) == null
            ? authorModel
            : profileRegistry.get(name).provider().model();
    Profile authorProfile =
        new Profile(
            AUTHOR_PROFILE_NAME,
            null,
            null,
            new Profile.ProviderRef(genProvider, authorModel, null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(1, 1));
    String prompt =
        AGENT_AUTHOR_PROMPT
                .replace("{name}", name)
                .replace("{provider}", outputProvider)
                .replace("{model}", modelHint == null ? authorModel : modelHint)
                .replace("{tools}", describeTools())
            + description;
    ProviderResponse response =
        providerService.chat(
            AUTHOR_SESSION_PREFIX + name, authorProfile, new ProviderRequest(prompt, List.of()));
    String text = response.content();
    if (text == null || text.isBlank()) {
      throw new com.sableos.core.provider.ProviderNotConfiguredException("模型未返回可用内容，请稍后重试");
    }
    return text;
  }

  private String describeTools() {
    if (knownToolNames.isEmpty()) {
      return TOOL_LIST_EMPTY;
    }
    return knownToolNames.stream()
        .sorted()
        .map(name -> "- " + name)
        .reduce((left, right) -> left + NEWLINE + right)
        .orElse(TOOL_LIST_EMPTY);
  }

  /** 剥掉模型可能多吐的 Markdown 代码围栏（```lang ... ```），只保留里面的内容。 */
  private static String stripCodeFences(String text) {
    String trimmed = text.strip();
    if (!trimmed.startsWith(CODE_FENCE)) {
      return trimmed;
    }
    int firstNewline = trimmed.indexOf('\n');
    String body = firstNewline < 0 ? "" : trimmed.substring(firstNewline + 1);
    int lastFence = body.lastIndexOf(CODE_FENCE);
    return (lastFence < 0 ? body : body.substring(0, lastFence)).strip();
  }

  /** 按 {@code ===FILE: <相对路径>===} 切多文件；无分隔符时整段作为 AGENT.md（单文件输出）。 */
  private static Map<String, String> parseGeneratedFiles(String text) {
    Map<String, String> files = new LinkedHashMap<>();
    // 先剥整体围栏：模型常把整段输出包在 ``` 里，那样「末尾那一行 ```」会被当成最后一个文件的内容
    String stripped = stripCodeFences(text.strip());
    if (!stripped.contains(FILE_MARKER_PREFIX)) {
      files.put(AGENT_FILE, stripped);
      return files;
    }
    String current = null;
    StringBuilder buffer = new StringBuilder();
    for (String line : stripped.split(NEWLINE, -1)) {
      String candidate = line.strip();
      if (candidate.startsWith(FILE_MARKER_PREFIX) && candidate.endsWith("===")) {
        if (current != null) {
          files.put(current, stripCodeFences(buffer.toString()));
        }
        String path =
            candidate
                .substring(FILE_MARKER_PREFIX.length(), candidate.length() - "===".length())
                .trim();
        current = path;
        buffer.setLength(0);
      } else if (current != null) {
        buffer.append(line).append('\n');
      }
      // 首个分隔符之前的前言忽略
    }
    if (current != null) {
      files.put(current, stripCodeFences(buffer.toString()));
    }
    return files;
  }

  // ---------------------------------------------------------------- 脚手架

  /** 脚手架文件集：AGENT.md（frontmatter 由 SnakeYAML dump，防用户输入拆行注入键）+ 示例脚本 + 参考资料。 */
  private Map<String, String> scaffold(String name, String description, String provider) {
    Map<String, String> files = new LinkedHashMap<>();
    files.put(
        AGENT_FILE,
        assembleMarkdown(scaffoldFrontmatter(name, description, provider), AGENT_MD_BODY));
    files.put(SCRIPT_PATH, SCRIPT_TEMPLATE);
    files.put(REFERENCE_FILE, REFERENCE_TEMPLATE);
    return files;
  }

  /**
   * 脚手架 frontmatter 用 Map 再 dump，不用字符串拼接。description 来自管理台输入，直接拼进 YAML 会让换行变成新键（例如注入 {@code
   * schedules}）。
   */
  private Map<String, Object> scaffoldFrontmatter(
      String name, String description, String providerName) {
    String effectiveDescription =
        (description == null || description.isBlank()) ? "描述这个 Agent 做什么" : description.strip();
    Map<String, Object> frontmatter = new LinkedHashMap<>();
    frontmatter.put("name", name);
    frontmatter.put("description", effectiveDescription);
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("agent_name", name);
    identity.put("prompt", "你是一个乐于助人的助手。");
    frontmatter.put("identity", identity);
    Map<String, Object> provider = new LinkedHashMap<>();
    provider.put("name", providerName);
    // model 必须非空才能通过派生校验；给一个显式占位符，用户/生成流程后续改成真模型名
    provider.put("model", SCAFFOLD_MODEL_PLACEHOLDER);
    frontmatter.put("provider", provider);
    frontmatter.put("tools", List.of());
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("max_iterations", Profile.Settings.DEFAULT_MAX_ITERATIONS);
    settings.put("max_history_turns", Profile.Settings.DEFAULT_MAX_HISTORY_TURNS);
    frontmatter.put("settings", settings);
    return frontmatter;
  }

  /** 脚手架 provider 缺省取已登记清单按大小写无关的最小项：ASCII 排序会先排大写，让「大写开头的本地 provider」顶掉真正的默认项。 */
  private String resolveScaffoldProvider() {
    if (defaultProvider != null && !defaultProvider.isBlank()) {
      return defaultProvider;
    }
    return profileRegistry.all().stream()
        .map(profile -> profile.provider().name())
        .filter(name -> name != null && !name.isBlank())
        .min(String::compareToIgnoreCase)
        .orElse("");
  }

  private static String assembleMarkdown(Map<String, Object> frontmatter, String body) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    String yaml = new Yaml(options).dump(frontmatter);
    return "---\n" + yaml + "---\n\n" + body + "\n";
  }

  // ---------------------------------------------------------------- 内部

  private Profile derive(Path agentDir) {
    try {
      return agentLoader.deriveProfile(agentDir);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 Agent 目录失败：" + safe(String.valueOf(agentDir)), e);
    }
  }

  private void registerInto(Profile profile) {
    profileRegistry.register(profile);
    if (!profile.schedules().isEmpty()) {
      agentScheduler.registerProfile(profile);
    }
    LOG.info("Agent 已登记：name={}", safe(profile.name()));
  }

  private static void requireValidName(String name) {
    if (!AgentStore.isValidName(name)) {
      throw new IllegalArgumentException(
          "非法的 Agent 名：" + name + "（只允许字母、数字、_ . -，首字符须为字母或数字，总长不超过 64，且不得为 ..）");
    }
  }

  private static String dirName(Path dir) {
    Path fileName = dir.getFileName();
    return (fileName == null) ? String.valueOf(dir) : fileName.toString();
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法）。 */
  private static String safe(String value) {
    return (value == null) ? null : value.replaceAll("[\r\n]", "_");
  }
}
