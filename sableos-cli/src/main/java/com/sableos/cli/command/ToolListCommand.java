package com.sableos.cli.command;

import picocli.CommandLine.Command;

/**
 * 轻命令：列出已注册工具。不起 Spring。
 *
 * <p>工具注册表（{@code ToolRegistry}/{@code ToolCatalog}）归第 20 节；核心阶段尚无内置工具登记，且轻命令不启动引擎、
 * 无法枚举运行期工具表——如实告知，不造假数据。
 */
@Command(
    name = "tool",
    description = "Tool 相关操作",
    mixinStandardHelpOptions = true,
    subcommands = ToolListCommand.ListCommand.class)
public class ToolListCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  @Command(name = "list", description = "列出已注册工具", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {
    @Override
    public void run() {
      System.out.println("内置工具注册归第 20 节；当前无工具登记。");
      System.out.println("（工具表由运行期引擎持有，轻命令不启动 Spring，故此处不枚举运行期工具。）");
    }
  }
}
