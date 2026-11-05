package com.sableos.core.agent;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Agent 主定义文件名的唯一真相源（第 30 节）：「一个目录 = 一个 Agent」里的那份 {@code AGENT.md}。
 *
 * <p><b>为什么要收敛到一处</b>：本类之前，监听侧（{@link WorkspaceWatcher}）与 web 侧（{@code WorkspaceApiController}）
 * 按大小写不敏感匹配文件名，而读取侧（{@link AgentLoader} / {@link AgentStore} / {@code ContextLoader}）用 {@code
 * dir.resolve("AGENT.md")} 精确匹配。两侧规则不一致的后果在**大小写敏感文件系统**（Linux，即 CI 与多数服务器）上暴露为静默 故障：用户写的是 {@code
 * agent.md}，监听器命中并调 {@code refresh()}，随后读取侧却因找不到 {@code AGENT.md} 抛「目录下缺少
 * AGENT.md」——文件明明就在那里。Windows / macOS 上该差异被文件系统掩盖，问题只在 Linux 复现，最难查。
 *
 * <p><b>规则</b>：大小写不敏感比较（ASCII 文件名，统一 {@link Locale#ROOT} 小写后比对），与 {@code AGENT.md} 视为同一份定义。
 * 同一目录下同时存在多个大小写变体（如 {@code AGENT.md} 与 {@code agent.md}）时优先精确名，其余按名字序取第一个——Linux 上
 * 它们确实是两个文件，此时需要确定性的选择而不是随机命中。
 *
 * <p>本类不碰 frontmatter 解析（那是 {@link AgentLoader} 的职责），只管「哪份文件算定义」。
 */
public final class AgentDefinitionFile {

  /** 规范名：新建 Agent 时写出的文件名，也是优先匹配的目标。 */
  public static final String FILE_NAME = "AGENT.md";

  /** 小写形态：比较的两边都必须是小写，拿大写常量去比小写化后的文件名恒为 false。 */
  private static final String FILE_NAME_LOWER = "agent.md";

  private AgentDefinitionFile() {}

  /**
   * 文件名是否为 Agent 主定义（大小写不敏感）。
   *
   * <p>用 {@link Locale#ROOT} 显式小写比较而非 {@code equalsIgnoreCase}：后者是 findsecbugs {@code
   * IMPROPER_UNICODE} 的固定报点，而这里的语义恰恰是「大小写不敏感文件系统上的同一文件」，属该规则的误报；改成全小写比较既保留 语义又不引入 locale 相关的隐式转换。
   *
   * @param fileName 纯文件名（不含目录），null 视为不是
   */
  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "AGENT.md 是 ASCII 保留文件名；此处的产物就是「按大小写不敏感文件系统语义比较同一文件名」，"
              + "不存在 Unicode 混淆风险，且小写化显式指定 Locale.ROOT 不依赖默认 locale。"
              + "撤销条件：把该比较用于用户可控的自由文本。")
  public static boolean matches(String fileName) {
    return fileName != null && FILE_NAME_LOWER.equals(fileName.toLowerCase(Locale.ROOT));
  }

  /**
   * 同 {@link #matches(String)}，接受路径形态（只取末段名）。
   *
   * @param path 任意路径，null 或其末段名为 null（根路径）时视为不是
   */
  public static boolean matchesPath(Path path) {
    if (path == null) {
      return false;
    }
    Path fileName = path.getFileName();
    return fileName != null && matches(fileName.toString());
  }

  /**
   * 在 Agent 目录下定位主定义文件：精确名 {@code AGENT.md} 优先，其次大小写变体。
   *
   * <p>只认目录下的直接文件（不递归、不跟随「同名目录」）。
   *
   * @param agentDir Agent 目录
   * @return 定义文件路径；目录下没有任何大小写变体时为空
   * @throws IOException 列目录失败
   */
  public static Optional<Path> locate(Path agentDir) throws IOException {
    if (agentDir == null || !Files.isDirectory(agentDir)) {
      return Optional.empty();
    }
    Path exact = agentDir.resolve(FILE_NAME);
    if (Files.isRegularFile(exact)) {
      return Optional.of(exact);
    }
    try (Stream<Path> stream = Files.list(agentDir)) {
      List<Path> candidates =
          stream
              .filter(Files::isRegularFile)
              .filter(AgentDefinitionFile::matchesPath)
              .sorted()
              .toList();
      return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }
  }

  /**
   * 同 {@link #locate(Path)}，但列目录失败时视为「没有定义」而不是抛出。
   *
   * <p>给「判断该 Agent 有没有定义」这类展示/预检路径用：调用方只关心真假，不需要区分「没有」与「读不到」。
   *
   * @param agentDir Agent 目录
   */
  public static boolean existsIn(Path agentDir) {
    try {
      return locate(agentDir).isPresent();
    } catch (IOException e) {
      return false;
    }
  }
}
