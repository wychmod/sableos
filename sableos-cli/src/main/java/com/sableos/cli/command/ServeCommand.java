package com.sableos.cli.command;

import com.sableos.cli.SableOsRuntime;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import picocli.CommandLine.Command;

/**
 * 重命令外壳：启动 Web Service（完整 REST 实现归 26 节）。
 *
 * <p>轻/重分流按 FR-002：serve 属重命令，必须先起应用上下文——故与 {@link ChatCommand} 同形，只把 {@code WebApplicationType} 换成
 * {@link WebApplicationType#SERVLET}（要起 Web 容器）。本节只交付命令外壳与"三运行模式共享同一份 Profile 与会话存储"这条语义（同一个 {@link
 * SableOsRuntime} 装配根）；REST 端点实现归 26 节。
 *
 * <p>与 chat 的两点差异都源于"服务要一直活着"：①<b>不套 try-with-resources</b>，跑完不主动关上下文；②起完上下文后 {@link #keepAlive()}
 * 阻塞。端口取 {@code server.port}（默认 8080，可被环境变量覆盖），故 serve 与 {@link GatewayCommand} 同时运行会争同一端口。
 */
@Command(name = "serve", description = "启动 HTTP 服务，供业务系统调用", mixinStandardHelpOptions = true)
public class ServeCommand implements Runnable {

  @Override
  public void run() {
    new SpringApplicationBuilder(SableOsRuntime.class).web(WebApplicationType.SERVLET).run();
    System.out.println("serve：Web Service 的完整实现归第 26 节；本节仅登记命令外壳（上下文已启动，Ctrl-C 退出）。");
    keepAlive();
  }

  /**
   * 常驻直到进程被终止（Ctrl-C），对齐 oryxos {@code ServeCommand.keepAlive()}。
   *
   * <p>必须显式阻塞：{@code SableOsCli.main} 末尾是 {@code System.exit(new
   * CommandLine(...).execute(args))}，{@code run()} 返回后整机即退（实测上下文启动完成后约 50ms 就走到
   * GracefulShutdown）——Tomcat 的非守护线程顶不住一次 {@code System.exit}。阻塞后由 Spring 注册的关闭钩子接管：Ctrl-C
   * 触发钩子、上下文优雅关闭、进程退出。
   */
  static void keepAlive() {
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
