package com.sableos.cli;

import com.sableos.cli.command.ChatCommand;
import com.sableos.cli.command.GatewayCommand;
import com.sableos.cli.command.InitCommand;
import com.sableos.cli.command.ProfileCommand;
import com.sableos.cli.command.ProviderListCommand;
import com.sableos.cli.command.ServeCommand;
import com.sableos.cli.command.SessionListCommand;
import com.sableos.cli.command.StatusCommand;
import com.sableos.cli.command.ToolListCommand;
import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * SableOS 命令行主入口（技术方案 §8.7）：整个程序的 {@code main}，纯 Picocli、不含 Spring。
 *
 * <p>核心阶段 12 个子命令（§8.7 展开口径）：三种运行模式 chat/serve/gateway，查看类 status / profile list·create·show·delete
 * / provider list / tool list / session list，起项目 init。轻命令零 Spring 秒回，重命令（chat/serve/gateway）在各自
 * {@code run()} 内自启动 {@link SableOsRuntime}。
 *
 * <p>可执行 fat JAR 由 sableos-boot 产出，其 {@code mainClass} 即本类（AGENTS.md「boot 产出 fat JAR」不变）。
 */
@Command(
    name = "sableos",
    mixinStandardHelpOptions = true,
    versionProvider = SableOsCli.VersionProvider.class,
    description = "SableOS 命令行入口",
    subcommands = {
      ChatCommand.class,
      ServeCommand.class,
      GatewayCommand.class,
      InitCommand.class,
      StatusCommand.class,
      ProfileCommand.class,
      ProviderListCommand.class,
      ToolListCommand.class,
      SessionListCommand.class
    })
public class SableOsCli implements Runnable {

  @Spec private CommandSpec spec;

  public static void main(String[] args) {
    System.exit(new CommandLine(new SableOsCli()).execute(args));
  }

  @Override
  public void run() {
    PrintWriter out = spec.commandLine().getOut();
    for (String line : spec.version()) {
      out.println(line);
    }
    out.println("运行 sableos --help 查看可用命令。");
    out.flush();
  }

  static class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
      String version = SableOsCli.class.getPackage().getImplementationVersion();
      return new String[] {"SableOS " + (version != null ? version : "dev")};
    }
  }
}
