package com.sableos.cli.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import picocli.CommandLine.Command;

/** 轻命令：看当前状态（工作区根、DB 路径、Agent 计数）。不起 Spring，直接文件读取、秒回。 */
@Command(name = "status", description = "查看当前工作区状态", mixinStandardHelpOptions = true)
public class StatusCommand implements Runnable {

  @Override
  public void run() {
    Path root = Workspace.root();
    Path agents = Workspace.agentsDir();
    System.out.printf("工作区根：%s%n", root.toAbsolutePath());
    System.out.printf(
        "会话库　：%s（%s）%n",
        Workspace.dbFile().toAbsolutePath(),
        Files.exists(Workspace.dbFile()) ? "已创建" : "尚未创建，首次 chat 时生成");
    System.out.printf("Agent 数：%d%n", countAgentDirs(agents));
  }

  private static int countAgentDirs(Path agents) {
    if (!Files.isDirectory(agents)) {
      return 0;
    }
    try (Stream<Path> list = Files.list(agents)) {
      return (int) list.filter(Files::isDirectory).count();
    } catch (IOException e) {
      System.err.println("读取 agents 目录失败: " + e.getMessage());
      return 0;
    }
  }
}
