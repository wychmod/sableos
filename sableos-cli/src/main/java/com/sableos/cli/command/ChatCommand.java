package com.sableos.cli.command;

import com.sableos.channel.cli.CliChannel;
import com.sableos.cli.SableOsRuntime;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * 重命令：自启动完整运行时后进入交互对话（课件骨架 + oryxos {@code ChatCommand} 同构）。
 *
 * <p>轻/重分流标准：要调模型/跑引擎才起 Spring。chat 是终端对话、不占 HTTP 端口（{@code WebApplicationType.NONE}），banner
 * 关掉保持界面干净。 CLI 只是入口——真正的读—转交—打印在 {@link CliChannel}，Agent 智能在引擎里。
 */
@Command(name = "chat", description = "在终端里和 Agent 交互式对话", mixinStandardHelpOptions = true)
public class ChatCommand implements Runnable {

  @Option(names = "--profile", defaultValue = "default", description = "使用的 Agent（Profile 名）")
  String profileName;

  @Override
  public void run() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(SableOsRuntime.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      context.getBean(CliChannel.class).run(profileName, currentUser());
    }
  }

  /** 核心阶段无认证体系，"当前用户"取运行环境的系统用户名（对齐 oryxos）。 */
  static String currentUser() {
    return System.getProperty("user.name", "unknown");
  }
}
