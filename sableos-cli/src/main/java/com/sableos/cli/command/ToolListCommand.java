package com.sableos.cli.command;

import picocli.CommandLine.Command;

/**
 * 轻命令：列出内置工具面。不起 Spring、秒回。
 *
 * <p>输出静态内置清单（12 个）；运行期实时清单（含 MCP 连接结果）由运行期引擎持有，归第 26 节 Web 管理台——轻命令不启动 Spring、
 * 无法枚举运行期工具表，故此处只列静态内置面，如实标注、不造假数据。
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

    /** 静态内置工具面（名称 + 一行简述），与第 20 节注册表登记一致。 */
    private static final String[][] BUILTIN_TOOLS = {
      {"read_file", "读取指定路径的文本文件内容"},
      {"write_file", "把内容写入指定路径的文件（覆盖写）"},
      {"list_dir", "列出指定目录下的文件和子目录名"},
      {"shell", "执行已在白名单中的可执行文件（参数原样、不经 Shell 解释）"},
      {"http_get", "发起一个 HTTP GET 请求，返回响应体"},
      {"http_post", "发起一个 HTTP POST 请求（JSON body），返回响应体"},
      {"edit_file", "把文件中一段唯一出现的旧文本替换为新文本（局部编辑）"},
      {"grep", "在指定路径下按正则搜索文件内容，返回 文件:行号:内容"},
      {"glob", "在指定目录下按 glob 通配（如 **/*.java）查找文件路径"},
      {"ask_user", "向当前用户提问并等待回答（无人值守渠道明确报不支持）"},
      {"web_search", "用搜索引擎检索网页，返回标题、链接和摘要列表"},
      {"notify", "向已注册的通知渠道推送一条消息（渠道注册表归后续节）"},
    };

    @Override
    public void run() {
      System.out.println("内置工具（" + BUILTIN_TOOLS.length + " 个）：");
      for (String[] tool : BUILTIN_TOOLS) {
        System.out.printf("  %-12s %s%n", tool[0], tool[1]);
      }
      System.out.println();
      System.out.println("（运行期实时清单含 MCP 连接结果，归第 26 节 Web 管理台；");
      System.out.println("  轻命令不启动 Spring，故此处只列静态内置面。）");
    }
  }
}
