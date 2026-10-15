package com.sableos.channel.cli;

import com.sableos.core.agent.AgentService;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.core.trace.TraceContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;

/**
 * {@code sableos chat} 的交互通道（技术方案 §8.4）：读 stdin、写 stdout，维护当前 Session，每行交给引擎，{@code /quit} 退出。
 *
 * <p>CLI 是消息进出的门，不是干活的人——本类没有任何 Agent 智能，就是"读—转交—打印"的壳（课件骨架）： 想、调模型、执行工具全在 {@link AgentService}
 * 里。channel 字面量 {@code "cli"} 只作为三元组参数提供， session_id 拼接在 {@link SessionManager} 内部（不变量 H4④）。
 *
 * <p>框架无关（只依赖 core），可写纯单测：{@link #run(String, String, BufferedReader, PrintStream)} 供测试注入脚本化
 * stdin/stdout。
 */
public class CliChannel {

  private static final String QUIT = "/quit";

  private final AgentService agentService;
  private final SessionManager sessionManager;

  public CliChannel(AgentService agentService, SessionManager sessionManager) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
  }

  /** 从终端进入交互对话，直到 {@code /quit} 或 EOF。 */
  public void run(String profileName, String userId) {
    run(profileName, userId, stdinReader(), System.out);
  }

  /**
   * 交互循环（可见给单测）：维护一个当前会话，逐行读入、交给引擎、打印回复。
   *
   * @param profileName Agent 运行配置名
   * @param userId 用户标识（三元组之一；session_id 由 SessionManager 拼接）
   * @param in 输入源
   * @param out 输出目标
   */
  void run(String profileName, String userId, BufferedReader in, PrintStream out) {
    Session session = sessionManager.getOrCreate(TraceContext.CHANNEL_CLI, userId, profileName);
    out.printf("已连接 Agent [%s]，输入 %s 退出。%n", profileName, QUIT);
    while (true) {
      out.print("> ");
      String line = readLine(in);
      // EOF（Ctrl-D / 管道结束）等同退出——不抛堆栈
      if (line == null || QUIT.equals(line.trim())) {
        out.println("再见。");
        return;
      }
      if (line.isBlank()) {
        continue;
      }
      // 关键一步：把这句话连同 Session 交给引擎（里面跑 ReAct 循环），CLI 到此撒手等结果
      String reply = agentService.process(session, line);
      out.println(reply);
    }
  }

  /** stdin 的 reader：有 console 用 console reader，否则跟 JVM 默认编码（不硬编码 UTF-8）。 */
  static BufferedReader stdinReader() {
    java.io.Console console = System.console();
    if (console != null) {
      return new BufferedReader(console.reader());
    }
    return new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
  }

  private static String readLine(BufferedReader in) {
    try {
      return in.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException("读取终端输入失败", e);
    }
  }
}
