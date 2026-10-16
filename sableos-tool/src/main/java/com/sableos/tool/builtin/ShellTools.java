package com.sableos.tool.builtin;

import com.sableos.tool.sandbox.ActionType;
import com.sableos.tool.sandbox.Sandbox;
import com.sableos.tool.sandbox.SandboxAction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置命令工具：直接执行获准的可执行文件，带超时兜底——命令挂死不能拖死整个 ReAct 循环。
 *
 * <p>白名单校验可执行文件名（SHELL_COMMAND，第一行 enforce）；参数作为 argv 直接传给进程，不经 Shell 解释。两个运维细节： (1) stdout/stderr
 * 在 {@code waitFor} 前就并发排空——否则输出超过管道缓冲的命令会写阻塞、被误判超时；(2) 超时后先杀子孙再杀自身——只杀父进程会留孤儿继续跑。输出按 {@link
 * Charset#defaultCharset()} 解码（与本地控制台代码页一致，中文 Windows 上是 GBK）。
 */
public class ShellTools {

  /** 默认超时：30 秒。 */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /** 排空子进程输出的虚拟线程执行器（宪法 VII）。 */
  @SuppressWarnings("PMD.ThreadPoolCreationRule") // Java 21 虚拟线程无池参数可配，非 P3C 针对的固定线程池反模式。
  private static final ExecutorService DRAINER = Executors.newVirtualThreadPerTaskExecutor();

  private final Sandbox sandbox;
  private final Duration timeout;

  public ShellTools(Sandbox sandbox) {
    this(sandbox, DEFAULT_TIMEOUT);
  }

  ShellTools(Sandbox sandbox, Duration timeout) {
    this.sandbox = sandbox;
    this.timeout = timeout;
  }

  @Tool(name = "shell", description = "执行一个已获许可的可执行文件，返回标准输出")
  @SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification =
          "确认误报（有缓解）：本工具的全部意义就是执行受控外部命令。缓解有二——① 方法第一行 "
              + "sandbox.enforce(SHELL_COMMAND, executable) 过白名单，未获准的可执行文件直接抛 "
              + "SandboxViolationException、进程根本不启动；② 参数以 argv 数组直传 ProcessBuilder、不经 Shell 解释，"
              + "不存在 shell 元字符注入面。白名单实现归 23/24 节，届时治理更严。撤销条件：一旦去掉首行 enforce "
              + "或改经 Shell 解释执行，须重新评估并移除本抑制。")
  public String shell(
      @ToolParam(description = "要执行的、已在白名单中的可执行文件") String executable,
      @ToolParam(description = "传给可执行文件的独立参数数组，不支持 shell 语法") List<String> arguments) {
    sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, executable));
    List<String> command = new ArrayList<>();
    command.add(executable);
    if (arguments != null) {
      command.addAll(arguments);
    }
    Process process;
    try {
      process = new ProcessBuilder(command).start();
    } catch (IOException e) {
      throw new UncheckedIOException("命令启动失败: " + executable, e);
    }
    // 先起并发排空再 waitFor：管道不被写满阻塞，waitFor 只在「命令真没跑完」时超时
    Future<String> stdout = DRAINER.submit(() -> drain(process.getInputStream()));
    Future<String> stderr = DRAINER.submit(() -> drain(process.getErrorStream()));
    boolean finished;
    try {
      finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      killTree(process);
      throw new IllegalStateException("命令执行被中断: " + executable, e);
    }
    if (!finished) {
      killTree(process);
      throw new IllegalStateException("命令超时（" + timeout.toSeconds() + "s）被终止: " + executable);
    }
    try {
      String errText = stderr.get().trim();
      if (process.exitValue() != 0) {
        throw new IllegalStateException("命令退出码 " + process.exitValue() + ": " + errText);
      }
      return stdout.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("命令执行被中断: " + executable, e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("命令输出读取失败: " + executable, e.getCause());
    }
  }

  /** 先递归杀命令派生的子孙进程，再杀命令本身（只 destroyForcibly 主进程会留孤儿继续执行）。 */
  private static void killTree(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  private static String drain(InputStream in) throws IOException {
    return new String(in.readAllBytes(), Charset.defaultCharset());
  }
}
