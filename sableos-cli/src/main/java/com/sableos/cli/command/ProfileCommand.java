package com.sableos.cli.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * 轻命令组：{@code profile list/create/show/delete}——读写 {@code .sableos/agents/} 下的 Agent 目录。不起 Spring。
 *
 * <p>一个目录 = 一个 Agent（{@code <name>/AGENT.md}）。目录名做安全校验（拒路径分隔符与 {@code ..}），杜绝越出 agents 目录的路径穿越。
 */
@Command(
    name = "profile",
    description = "管理 Agent（Profile）：list / create / show / delete",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProfileCommand.ListCommand.class,
      ProfileCommand.CreateCommand.class,
      ProfileCommand.ShowCommand.class,
      ProfileCommand.DeleteCommand.class
    })
public class ProfileCommand implements Runnable {

  private static final String SLASH = "/";
  private static final String BACKSLASH = "\\";
  private static final String DOT_DOT = "..";
  private static final String DOT = ".";

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  /** 校验目录名：非空、无路径分隔符、无 {@code ..}、不以点开头。返回合法名，非法则抛 IllegalArgumentException。 */
  static String requireSafeName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Agent 名不能为空");
    }
    if (name.contains(SLASH)
        || name.contains(BACKSLASH)
        || name.contains(DOT_DOT)
        || name.startsWith(DOT)) {
      throw new IllegalArgumentException("非法 Agent 名（不得含路径分隔符、'..' 或以 '.' 开头）: " + name);
    }
    return name;
  }

  static Path agentDir(String name) {
    return Workspace.agentsDir().resolve(requireSafeName(name));
  }

  @Command(name = "list", description = "列出所有 Agent", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {
    @Override
    public void run() {
      Path agents = Workspace.agentsDir();
      if (!Files.isDirectory(agents)) {
        System.out.println("暂无 Agent（先运行 sableos init，再在 .sableos/agents/ 下建目录）。");
        return;
      }
      try (Stream<Path> list = Files.list(agents)) {
        java.util.List<Path> dirs =
            list.filter(Files::isDirectory).sorted().collect(java.util.stream.Collectors.toList());
        if (dirs.isEmpty()) {
          System.out.println("暂无 Agent。");
          return;
        }
        for (Path p : dirs) {
          System.out.println(p.getFileName());
        }
      } catch (IOException e) {
        System.err.println("读取 agents 目录失败: " + e.getMessage());
      }
    }
  }

  @Command(
      name = "create",
      description = "新建一个 Agent 目录（含 AGENT.md 骨架）",
      mixinStandardHelpOptions = true)
  static class CreateCommand implements Runnable {
    @Parameters(index = "0", description = "Agent 名（目录名）")
    String name;

    @Override
    public void run() {
      Path dir;
      try {
        dir = agentDir(name);
      } catch (IllegalArgumentException e) {
        System.err.println(e.getMessage());
        return;
      }
      Path markdown = dir.resolve("AGENT.md");
      if (Files.exists(markdown)) {
        System.out.printf("Agent 已存在，不覆盖：%s%n", markdown.toAbsolutePath());
        return;
      }
      String skeleton =
          "---\n"
              + "name: "
              + name
              + "\n"
              + "description: TODO 一句话描述这个 Agent\n"
              + "provider:\n"
              + "  name: deepseek        # TODO 改成你配置的 provider 名\n"
              + "  model: deepseek-chat  # TODO 改成具体模型\n"
              + "---\n\n"
              + "你是 "
              + name
              + "。TODO：在这里写任务指令（正文会全量注入 system prompt）。\n";
      try {
        Files.createDirectories(dir);
        Files.writeString(markdown, skeleton, StandardCharsets.UTF_8);
        System.out.printf("已创建 Agent 骨架：%s%n", markdown.toAbsolutePath());
      } catch (IOException e) {
        System.err.println("创建 Agent 失败: " + e.getMessage());
      }
    }
  }

  @Command(name = "show", description = "打印指定 Agent 的 AGENT.md", mixinStandardHelpOptions = true)
  static class ShowCommand implements Runnable {
    @Parameters(index = "0", description = "Agent 名")
    String name;

    @Override
    public void run() {
      Path markdown;
      try {
        markdown = agentDir(name).resolve("AGENT.md");
      } catch (IllegalArgumentException e) {
        System.err.println(e.getMessage());
        return;
      }
      if (!Files.isRegularFile(markdown)) {
        System.out.printf("未找到 Agent：%s（%s 不存在）%n", name, markdown);
        return;
      }
      try {
        System.out.print(Files.readString(markdown, StandardCharsets.UTF_8));
      } catch (IOException e) {
        System.err.println("读取 AGENT.md 失败: " + e.getMessage());
      }
    }
  }

  @Command(
      name = "delete",
      description = "删除一个 Agent 目录（需 --yes 确认）",
      mixinStandardHelpOptions = true)
  static class DeleteCommand implements Runnable {
    @Parameters(index = "0", description = "Agent 名")
    String name;

    @Option(
        names = {"-y", "--yes"},
        description = "确认删除（不带此flag只提示、不删）")
    boolean confirmed;

    @Override
    public void run() {
      Path dir;
      try {
        dir = agentDir(name);
      } catch (IllegalArgumentException e) {
        System.err.println(e.getMessage());
        return;
      }
      if (!Files.isDirectory(dir)) {
        System.out.printf("未找到 Agent：%s（%s 不存在）%n", name, dir);
        return;
      }
      if (!confirmed) {
        System.out.printf("将删除整个目录：%s%n重新运行并加 --yes 确认删除。%n", dir.toAbsolutePath());
        return;
      }
      try (Stream<Path> walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.delete(p);
                  } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                  }
                });
        System.out.printf("已删除 Agent：%s%n", dir.toAbsolutePath());
      } catch (IOException | java.io.UncheckedIOException e) {
        System.err.println("删除 Agent 失败: " + e.getMessage());
      }
    }
  }
}
