package com.sableos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sableos.tool.sandbox.ActionType;
import com.sableos.tool.sandbox.PermissiveSandbox;
import com.sableos.tool.sandbox.SandboxAction;
import com.sableos.tool.sandbox.SandboxViolationException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ShellTools 的回归测试：真实进程 + OS 分支夹具（Windows cmd / Unix sh）。 */
class ShellToolsTest {

  private static final boolean WINDOWS =
      System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

  private final ShellTools tools = new ShellTools(new PermissiveSandbox());

  @Test
  @DisplayName("shell 正常命令_返回标准输出")
  void shell_echoReturnsStdout() {
    String result =
        WINDOWS ? tools.shell("cmd", List.of("/c", "echo hi")) : tools.shell("echo", List.of("hi"));

    assertTrue(result.contains("hi"), "stdout 应包含 echo 输出: " + result);
  }

  @Test
  @DisplayName("shell 白名单外可执行文件_起进程前被拦")
  void shell_outsideWhitelistIsBlocked() {
    AtomicReference<SandboxAction> seen = new AtomicReference<>();
    ShellTools guarded =
        new ShellTools(
            action -> {
              seen.set(action);
              throw new SandboxViolationException("命令不在白名单");
            });

    // 用必然启动失败的可执行文件名：若没被拦下，拿到的会是启动失败（UncheckedIOException）而非 SandboxViolationException
    assertThrows(
        SandboxViolationException.class,
        () -> guarded.shell("definitely-not-a-real-binary-xyz", List.of()));

    assertEquals(ActionType.SHELL_COMMAND, seen.get().type());
    assertEquals("definitely-not-a-real-binary-xyz", seen.get().target());
  }

  @Test
  @DisplayName("shell 非零退出码_异常携带退出码与 stderr")
  void shell_nonZeroExitCarriesStderr() {
    IllegalStateException ex =
        WINDOWS
            ? assertThrows(
                IllegalStateException.class,
                () -> tools.shell("cmd", List.of("/c", "echo boom 1>&2 & exit 3")))
            : assertThrows(
                IllegalStateException.class,
                () -> tools.shell("sh", List.of("-c", "echo boom 1>&2; exit 3")));

    assertTrue(ex.getMessage().contains("3"), ex.getMessage());
    assertTrue(ex.getMessage().contains("boom"), ex.getMessage());
  }

  @Test
  @DisplayName("shell 命令挂死_按超时终止并报失败")
  void shell_timeoutKillsProcess() {
    ShellTools shortTimeout = new ShellTools(new PermissiveSandbox(), Duration.ofMillis(300));
    long start = System.nanoTime();

    IllegalStateException ex =
        WINDOWS
            ? assertThrows(
                IllegalStateException.class,
                () -> shortTimeout.shell("ping", List.of("-n", "5", "127.0.0.1")))
            : assertThrows(
                IllegalStateException.class, () -> shortTimeout.shell("sleep", List.of("5")));

    long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
    assertTrue(ex.getMessage().contains("超时"), ex.getMessage());
    assertTrue(elapsedMillis < 4_000L, "应在超时点即返回而非等命令自然结束: " + elapsedMillis + "ms");
  }
}
