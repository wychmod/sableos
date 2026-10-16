package com.sableos.tool.interaction;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;

/**
 * 终端档交互实现：把问题打到终端、读用户输入的一行。
 *
 * <p>读行与 chat 循环共用同一 stdin——ReAct 循环同步执行（原则七），ask_user 与对话读行不会并发争抢，一问一答顺序发生。
 */
public class ConsoleUserInteraction implements UserInteraction {

  private final BufferedReader in;
  private final PrintStream out;

  public ConsoleUserInteraction() {
    this(System.in, System.out, Charset.defaultCharset());
  }

  /** 注入构造（测试用）：按指定 charset 解输入流；生产默认构造跟 JVM 默认 charset（Windows 上多为 GBK）。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：PrintStream 是构造注入的协作者（依赖注入本就必须持有其引用），并非把可变内部表示暴露出去"
              + "（本类无返回 out 的 getter，ask 只在方法栈内使用）。撤销条件：一旦新增暴露 out 的 getter，须重新评估并移除本抑制。")
  public ConsoleUserInteraction(InputStream in, PrintStream out, Charset charset) {
    this.in = new BufferedReader(new InputStreamReader(in, charset));
    this.out = out;
  }

  @Override
  public String ask(String question) {
    out.println("[Agent 提问] " + question);
    out.print("> ");
    try {
      String line = in.readLine();
      if (line == null) {
        throw new IllegalStateException("输入流已结束，无法获取用户回答");
      }
      return line;
    } catch (IOException e) {
      throw new UncheckedIOException("读取用户回答失败", e);
    }
  }
}
