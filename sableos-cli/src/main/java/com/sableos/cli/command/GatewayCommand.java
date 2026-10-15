package com.sableos.cli.command;

import com.sableos.cli.SableOsRuntime;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import picocli.CommandLine.Command;

/**
 * 重命令外壳：守护进程，同时挂多个消息通道（完整多通道归后续渠道节）。
 *
 * <p>轻/重分流按 FR-002：gateway 属重命令，必须先起应用上下文，形态对齐 {@link ServeCommand}（契约 cli-bootstrap.md "gateway 同
 * serve 起上下文后占位"）：起 {@link WebApplicationType#SERVLET} 上下文后打印占位，再 {@link ServeCommand#keepAlive()}
 * 常驻（不阻塞则 {@code run()} 返回后进程被 {@code System.exit} 立刻带走）。
 *
 * <p>不挂真实多通道；三种运行模式共享同一份 Profile 与 Session 存储的语义由同一个 {@link SableOsRuntime} 装配根承载。端口同样取 {@code
 * server.port}，与 serve 同时运行会争同一端口。
 */
@Command(name = "gateway", description = "守护进程：同时挂多个消息通道", mixinStandardHelpOptions = true)
public class GatewayCommand implements Runnable {

  @Override
  public void run() {
    new SpringApplicationBuilder(SableOsRuntime.class).web(WebApplicationType.SERVLET).run();
    System.out.println("gateway：多通道守护进程的完整实现归后续渠道节；本节仅登记命令外壳（上下文已启动，Ctrl-C 退出）。");
    ServeCommand.keepAlive();
  }
}
