package com.sableos.tool.builtin;

import com.sableos.tool.sandbox.ActionType;
import com.sableos.tool.sandbox.Sandbox;
import com.sableos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置文件工具（read_file / write_file / list_dir / edit_file / grep / glob）。
 *
 * <p>硬规矩：每个方法第一行过 {@code sandbox.enforce} 路径白名单——校验不过异常拦下，文件根本不碰（契约 S1）。
 */
public class FileTools {

  /** grep / glob 单次返回上限，防超大目录撑爆上下文。 */
  private static final int MAX_MATCHES = 200;

  /** 截断标注：到限且仍有命中时追加。 */
  private static final String TRUNCATION_NOTE = "...（已达 " + MAX_MATCHES + " 条上限，结果截断）";

  /** glob 递归前缀；Java PathMatcher 对根下单段相对路径需去掉后再补匹配。 */
  private static final String GLOB_RECURSIVE_PREFIX = "**/";

  private final Sandbox sandbox;

  public FileTools(Sandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Tool(name = "read_file", description = "读取指定路径的文本文件内容")
  public String readFile(@ToolParam(description = "要读取的文件路径") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path file = Path.of(path);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("文件不存在或不是普通文件: " + path);
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件失败: " + path, e);
    }
  }

  @Tool(name = "write_file", description = "把内容写入指定路径的文件（覆盖写）")
  public String writeFile(
      @ToolParam(description = "要写入的文件路径") String path,
      @ToolParam(description = "要写入的内容") String content) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    try {
      // 不自动建父目录（spec 边界）：父目录缺失即失败点名，由模型显式决定目录结构
      Files.writeString(Path.of(path), content);
      return "已写入: " + path;
    } catch (IOException e) {
      throw new UncheckedIOException("写入文件失败: " + path, e);
    }
  }

  @Tool(name = "list_dir", description = "列出指定目录下的文件和子目录名")
  public String listDir(@ToolParam(description = "要列出的目录路径") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path dir = Path.of(path);
    if (!Files.isDirectory(dir)) {
      throw new IllegalArgumentException("目录不存在: " + path);
    }
    try (Stream<Path> entries = Files.list(dir)) {
      return entries
          .map(p -> String.valueOf(p.getFileName()))
          .sorted()
          .collect(Collectors.joining("\n"));
    } catch (IOException e) {
      throw new UncheckedIOException("列目录失败: " + path, e);
    }
  }

  @Tool(name = "edit_file", description = "把文件中一段唯一出现的旧文本替换为新文本（局部编辑，不整文件覆盖）")
  public String editFile(
      @ToolParam(description = "要编辑的文件路径") String path,
      @ToolParam(description = "要被替换的原文本（必须在文件中唯一出现）") String oldString,
      @ToolParam(description = "替换后的新文本") String newString) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    Path file = Path.of(path);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("文件不存在或不是普通文件: " + path);
    }
    try {
      String content = Files.readString(file);
      int first = content.indexOf(oldString);
      if (first < 0) {
        throw new IllegalArgumentException("原文本在文件中未找到: " + path);
      }
      if (content.indexOf(oldString, first + 1) >= 0) {
        // 多处匹配会改错地方——要求唯一，逼调用方给足上下文
        throw new IllegalArgumentException("原文本在文件中出现多次，无法定位唯一编辑点: " + path);
      }
      Files.writeString(file, content.replace(oldString, newString));
      return "已编辑: " + path;
    } catch (IOException e) {
      throw new UncheckedIOException("编辑文件失败: " + path, e);
    }
  }

  @Tool(name = "grep", description = "在指定路径下按正则搜索文件内容，返回匹配的 文件:行号:内容")
  public String grep(
      @ToolParam(description = "要搜索的正则表达式") String pattern,
      @ToolParam(description = "搜索根路径（文件或目录）") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path root = Path.of(path);
    if (!Files.exists(root)) {
      throw new IllegalArgumentException("路径不存在: " + path);
    }
    Pattern regex = Pattern.compile(pattern);
    List<String> matches = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
        if (appendMatches(file, regex, matches)) {
          matches.add(TRUNCATION_NOTE);
          break;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("搜索失败: " + path, e);
    }
    return matches.isEmpty() ? "（无匹配）" : String.join("\n", matches);
  }

  @Tool(name = "glob", description = "在指定目录下按 glob 通配（如 **/*.java）查找文件路径")
  public String glob(
      @ToolParam(description = "glob 通配模式，如 **/*.yaml") String pattern,
      @ToolParam(description = "查找根目录") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path root = Path.of(path);
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("目录不存在: " + path);
    }
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    List<String> hits = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      // 多取一条用于判定「真截断」：恰 200 条时不出现截断标注
      for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
        if (!globMatches(matcher, pattern, root.relativize(file))) {
          continue;
        }
        if (hits.size() >= MAX_MATCHES) {
          hits.add(TRUNCATION_NOTE);
          break;
        }
        hits.add(file.toString());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("查找失败: " + path, e);
    }
    return hits.isEmpty() ? "（无匹配）" : String.join("\n", hits);
  }

  /** 逐行匹配追加；返回 true 表示已达上限且仍有新命中（真截断）。单文件读取失败（二进制/非 UTF-8）跳过继续。 */
  private static boolean appendMatches(Path file, Pattern regex, List<String> matches) {
    try {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++) {
        if (regex.matcher(lines.get(i)).find()) {
          if (matches.size() >= MAX_MATCHES) {
            return true;
          }
          matches.add(file + ":" + (i + 1) + ":" + lines.get(i));
        }
      }
    } catch (IOException e) {
      // 二进制/非 UTF-8 文件读不出来，跳过而非中断整次搜索
    }
    return false;
  }

  /**
   * Java {@code PathMatcher} 对 {@code **}{@code /} 前缀不匹配 walk 根下的单段相对路径（如 {@code top.txt}），对根目录直接
   * 下的文件补一次去掉该前缀后的匹配。
   */
  private static boolean globMatches(PathMatcher matcher, String pattern, Path relative) {
    if (matcher.matches(relative)) {
      return true;
    }
    if (pattern.startsWith(GLOB_RECURSIVE_PREFIX) && relative.getNameCount() == 1) {
      PathMatcher rest =
          FileSystems.getDefault()
              .getPathMatcher("glob:" + pattern.substring(GLOB_RECURSIVE_PREFIX.length()));
      return rest.matches(relative);
    }
    return false;
  }
}
