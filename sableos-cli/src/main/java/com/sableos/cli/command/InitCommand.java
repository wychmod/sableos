package com.sableos.cli.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;

/**
 * 轻命令：初始化一个 SableOS 工程（建 {@code .sableos/} 骨架）。不起 Spring，直接文件操作、秒回。
 *
 * <p>幂等：已存在的目录不覆盖、不清空（只补缺失的骨架目录）。
 */
@Command(name = "init", description = "初始化一个 SableOS 工程", mixinStandardHelpOptions = true)
public class InitCommand implements Runnable {

  @Override
  public void run() {
    Path root = Workspace.root();
    Path agents = Workspace.agentsDir();
    try {
      boolean rootExisted = Files.isDirectory(root);
      Files.createDirectories(agents);
      if (rootExisted) {
        System.out.printf("工作区已存在，补齐骨架：%s%n", root.toAbsolutePath());
      } else {
        System.out.printf("已初始化 SableOS 工作区：%s%n", root.toAbsolutePath());
      }
      System.out.printf("  agents 目录：%s%n", agents.toAbsolutePath());
      System.out.println(
          "下一步：在 agents/ 下建 <name>/AGENT.md 定义一个 Agent，然后 sableos chat --profile <name>。");
    } catch (IOException e) {
      System.err.println("初始化工作区失败: " + e.getMessage());
    }
  }
}
