package com.sableos.core.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Agent 目录的文件读写（第 30 节）：{@code writeAll} 落一组文件、{@code archive} 把整个目录移进归档区、 {@code delete}
 * 回滚本次刚写出的目录。所有路径都必须落在 {@code <root>/agents/<name>} 或 {@code <root>/archive} 之内。
 *
 * <p>本类只管文件语义，不碰注册表与调度器（那是 {@link AgentLifecycleService} 的职责）；因此可脱离 Spring 直接单测。
 *
 * <p><b>归档绝不覆盖</b>：同名目录已存在时追加 {@code -yyyyMMddHHmmss} 时间戳后缀，历史归档只增不改。
 */
public class AgentStore {

  /** Agent 名只能是安全目录段：首字符字母数字，其余允许字母数字与 {@code _ . -}，总长 ≤ 64。 */
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

  private static final String AGENT_FILE = "AGENT.md";
  private static final String AGENTS_DIR = "agents";
  private static final String ARCHIVE_DIR = "archive";
  private static final String PATH_SEPARATOR = "/";
  private static final String BACKSLASH = "\\";
  private static final String PARENT_SEGMENT = "..";
  private static final DateTimeFormatter ARCHIVE_SUFFIX =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private final Path root;
  private final Path agentsDir;
  private final Path archiveDir;

  public AgentStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
    this.agentsDir = this.root.resolve(AGENTS_DIR);
    this.archiveDir = this.root.resolve(ARCHIVE_DIR);
  }

  /** 工作区根（绝对路径、已 normalize）。 */
  public Path root() {
    return root;
  }

  /** Agent 目录根 {@code <root>/agents}。 */
  public Path agentsDir() {
    return agentsDir;
  }

  /** 归档区根 {@code <root>/archive}。 */
  public Path archiveDir() {
    return archiveDir;
  }

  /** Agent 名是否可作为安全目录段（供 web 层预校验复用，避免两套规则）。 */
  public static boolean isValidName(String name) {
    return name != null && SAFE_NAME.matcher(name).matches() && !PARENT_SEGMENT.equals(name);
  }

  /** 该 Agent 目录是否已存在（不要求已注册）。 */
  public boolean exists(String name) {
    return Files.isDirectory(agentDir(name), LinkOption.NOFOLLOW_LINKS);
  }

  /**
   * 按相对路径写入整个 Agent 目录。
   *
   * <p>每个键都是相对该 Agent 目录的路径（如 {@code AGENT.md}、{@code scripts/example.py}）；逐个 normalize 后必须仍落在该目录内，
   * 否则视为越界并整体拒绝（不写半个目录）。
   *
   * @param name Agent 名（同时决定目录名，非法即拒）
   * @param files 相对路径 → 文件内容
   * @return 写入后的 Agent 目录
   */
  public Path writeAll(String name, Map<String, String> files) {
    Path dir = agentDir(name);
    try {
      Files.createDirectories(dir);
      for (Map.Entry<String, String> entry : files.entrySet()) {
        Path target = writableTarget(dir, entry.getKey());
        Path parent = target.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.writeString(target, entry.getValue() == null ? "" : entry.getValue());
      }
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException("写入 Agent 目录失败：" + name, e);
    }
  }

  /** 回滚删除本次刚创建的目录；不存在时静默（回滚路径必须幂等）。 */
  public void delete(Path agentDir) {
    if (agentDir == null || !Files.exists(agentDir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Path normalized = agentDir.toAbsolutePath().normalize();
    // 只允许删 agents/ 下的直接子目录：防止调用方误传根或任意路径
    if (!normalized.startsWith(agentsDir) || normalized.equals(agentsDir)) {
      throw new IllegalArgumentException("拒绝删除超出 Agent 目录范围的路径：" + normalized);
    }
    deleteRecursively(normalized);
  }

  /**
   * 把整个 Agent 目录移进归档区（不物理删）。
   *
   * <p>归档目标已存在时追加 {@code -yyyyMMddHHmmss} 后缀，绝不覆盖历史归档。
   *
   * @param name Agent 名
   * @return 归档后的目录
   */
  public Path archive(String name) {
    Path source = agentDir(name);
    if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Agent 目录不存在，无法归档：" + name);
    }
    Path target = archiveTarget(name);
    try {
      Files.createDirectories(archiveDir);
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("归档 Agent 目录失败：" + name, e);
    }
    return target;
  }

  /** 读 {@code agents/<name>/AGENT.md}。 */
  public String read(String name) {
    Path file = agentDir(name).resolve(AGENT_FILE);
    if (!Files.isRegularFile(file)) {
      throw new IllegalStateException("Agent 目录缺少 " + AGENT_FILE + "：" + name);
    }
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 Agent 目录失败：" + name, e);
    }
  }

  /** 读该 Agent 目录下全部可读文本文件（相对路径 → 内容），用于详情页的文件列表。 */
  public Map<String, String> readAll(String name) {
    Path dir = agentDir(name);
    if (!Files.isDirectory(dir)) {
      throw new IllegalArgumentException("Agent 目录不存在：" + name);
    }
    Map<String, String> result = new LinkedHashMap<>();
    try (Stream<Path> stream = Files.walk(dir)) {
      List<Path> files =
          stream.filter(Files::isRegularFile).filter(AgentStore::isTextFile).sorted().toList();
      for (Path file : files) {
        String relative = relativePath(dir, file);
        result.put(relative, Files.readString(file, StandardCharsets.UTF_8));
      }
      return result;
    } catch (IOException e) {
      throw new UncheckedIOException("读取 Agent 目录失败：" + name, e);
    }
  }

  /**
   * 解析工作区内的相对路径并校验边界：结果必须是工作区根之下的绝对路径，且首段为 {@code agents} 或 {@code archive}。
   *
   * @param relativePath 相对工作区根的路径（{@code /} 或 {@code \} 分隔均可）
   * @return 边界校验后的绝对路径
   * @throws IllegalArgumentException 越界、指向工作区根本身、或不在两棵可浏览子树内
   */
  public Path resolveWithin(String relativePath) {
    if (relativePath == null || relativePath.isBlank()) {
      throw new IllegalArgumentException("路径不能为空");
    }
    String normalizedText = relativePath.replace(BACKSLASH, PATH_SEPARATOR).trim();
    Path requested = Path.of(normalizedText);
    if (requested.isAbsolute() || looksAbsolute(normalizedText)) {
      throw new IllegalArgumentException("路径越界：只接受相对工作区根的路径");
    }
    Path resolved = root.resolve(requested).normalize();
    if (!resolved.startsWith(root) || resolved.equals(root)) {
      throw new IllegalArgumentException("路径越界：目标不在工作区内");
    }
    Path relative = root.relativize(resolved);
    if (relative.getNameCount() == 0) {
      throw new IllegalArgumentException("路径越界：目标不在工作区内");
    }
    String first = relative.getName(0).toString();
    if (!AGENTS_DIR.equals(first) && !ARCHIVE_DIR.equals(first)) {
      throw new IllegalArgumentException("路径越界：只允许浏览 agents/ 或 archive/ 下的内容");
    }
    return resolved;
  }

  private Path agentDir(String name) {
    if (!isValidName(name)) {
      throw new IllegalArgumentException("非法的 Agent 名：" + name);
    }
    return agentsDir.resolve(name).normalize();
  }

  private Path archiveTarget(String name) {
    Path candidate = archiveDir.resolve(name).normalize();
    if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      return candidate;
    }
    String stamped = name + "-" + LocalDateTime.now().format(ARCHIVE_SUFFIX);
    Path suffixed = archiveDir.resolve(stamped).normalize();
    // 同一秒内重复归档：再退化为带序号的名称，保证绝不覆盖
    int index = 2;
    while (Files.exists(suffixed, LinkOption.NOFOLLOW_LINKS)) {
      suffixed = archiveDir.resolve(stamped + "-" + index).normalize();
      index++;
    }
    return suffixed;
  }

  private static Path writableTarget(Path dir, String relativePath) {
    if (relativePath == null || relativePath.isBlank()) {
      throw new IllegalArgumentException("文件相对路径不能为空");
    }
    String normalizedText = relativePath.replace(BACKSLASH, PATH_SEPARATOR).trim();
    if (looksAbsolute(normalizedText)) {
      throw new IllegalArgumentException("文件路径必须是相对路径：" + relativePath);
    }
    Path candidate = Path.of(normalizedText).normalize();
    if (candidate.isAbsolute()) {
      throw new IllegalArgumentException("文件路径必须是相对路径：" + relativePath);
    }
    Path target = dir.resolve(candidate).normalize();
    if (!target.startsWith(dir) || target.equals(dir)) {
      throw new IllegalArgumentException("文件路径越出 Agent 目录：" + relativePath);
    }
    return target;
  }

  /**
   * 「像绝对路径」判定：Windows 上 {@code Path.of("/etc/passwd").isAbsolute()} 返回 {@code false}（缺盘符）， 只靠
   * {@link Path#isAbsolute()} 会让前导斜杠路径被当成相对路径而绕过校验。故额外识别前导分隔符与盘符。
   */
  private static boolean looksAbsolute(String normalizedText) {
    if (normalizedText.startsWith(PATH_SEPARATOR)) {
      return true;
    }
    return normalizedText.length() >= 2
        && Character.isLetter(normalizedText.charAt(0))
        && normalizedText.charAt(1) == ':';
  }

  private static String relativePath(Path base, Path file) {
    return base.relativize(file).toString().replace(BACKSLASH, PATH_SEPARATOR);
  }

  private static boolean isTextFile(Path file) {
    Path fileName = file.getFileName();
    if (fileName == null) {
      return false;
    }
    String name = fileName.toString().toLowerCase(java.util.Locale.ROOT);
    return !name.endsWith(".db")
        && !name.endsWith(".db-shm")
        && !name.endsWith(".db-wal")
        && !name.endsWith(".class");
  }

  private static void deleteRecursively(Path dir) {
    try (Stream<Path> stream = Files.walk(dir)) {
      List<Path> paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
      for (Path path : paths) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("删除 Agent 目录失败：" + dir, e);
    }
  }
}
