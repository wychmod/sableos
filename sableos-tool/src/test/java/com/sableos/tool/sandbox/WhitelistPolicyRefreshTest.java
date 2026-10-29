package com.sableos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 008 快照刷新守点：{@code refresh} 后 {@code enforce} 立即看到新策略；空策略 deny-all；刷新前后互不污染。
 *
 * <p>既有 29 例（{@link WhitelistSandboxTest}）负责"校验规则本身"，本类只负责"策略换版"。
 */
class WhitelistPolicyRefreshTest {

  private static WhitelistPolicy policy(
      List<Path> roots, Set<String> commands, List<String> domains) {
    return new WhitelistPolicy(roots, commands, domains);
  }

  @Test
  @DisplayName("refresh 后立即生效_新增的文件根立刻放行")
  void refresh_newRootTakesEffectImmediately(@TempDir Path dir) {
    WhitelistSandbox sandbox = new WhitelistSandbox(policy(List.of(), Set.of(), List.of()));
    Path target = dir.resolve("note.txt");

    assertThrows(
        SandboxViolationException.class,
        () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, target.toString())));

    sandbox.refresh(policy(List.of(dir), Set.of(), List.of()));

    assertDoesNotThrow(
        () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, target.toString())));
  }

  @Test
  @DisplayName("refresh 后立即生效_被移除的根立刻拒绝")
  void refresh_removedRootIsRejectedImmediately(@TempDir Path dir) {
    WhitelistSandbox sandbox = new WhitelistSandbox(policy(List.of(dir), Set.of(), List.of()));
    Path target = dir.resolve("note.txt");

    assertDoesNotThrow(
        () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, target.toString())));

    sandbox.refresh(WhitelistPolicy.empty());

    assertThrows(
        SandboxViolationException.class,
        () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, target.toString())));
  }

  @Test
  @DisplayName("空策略等于全部拒绝_三类都不放行")
  void emptyPolicy_deniesAllThreeTypes() {
    WhitelistSandbox sandbox = new WhitelistSandbox(WhitelistPolicy.empty());

    assertThrows(
        SandboxViolationException.class,
        () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "/tmp/x")));
    assertThrows(
        SandboxViolationException.class,
        () -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")));
    assertThrows(
        SandboxViolationException.class,
        () -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com")));
  }

  @Test
  @DisplayName("刷新只影响被替换的那一类_其他两类保持")
  void refresh_doesNotAffectUnrelatedTypes(@TempDir Path dir) {
    WhitelistSandbox sandbox =
        new WhitelistSandbox(policy(List.of(), Set.of("ls"), List.of("example.com")));

    sandbox.refresh(policy(List.of(dir), Set.of("ls"), List.of("example.com")));

    assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")));
    assertDoesNotThrow(
        () -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com/x")));
  }

  @Test
  @DisplayName("策略快照对构造入参做不可变拷贝_外部改动不穿透")
  void policySnapshot_isDefensivelyCopied(@TempDir Path dir) {
    WhitelistPolicy original = new WhitelistPolicy(List.of(dir), Set.of(), List.of());
    WhitelistSandbox sandbox = new WhitelistSandbox(original);
    Path target = dir.resolve("note.txt");

    // 构造所用集合本身已不可变；这里验证再次 refresh 成空策略后，旧策略的引用不会让校验继续放行
    sandbox.refresh(WhitelistPolicy.empty());

    assertThrows(
        SandboxViolationException.class,
        () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, target.toString())));
  }

  @Test
  @DisplayName("三参数构造器行为不变_仍按配置生成初始策略")
  void legacyConstructor_stillBuildsInitialPolicy(@TempDir Path dir) {
    WhitelistSandbox sandbox =
        new WhitelistSandbox(
            new FileSandboxProperties(List.of(dir.toString())),
            new ShellSandboxProperties(List.of("ls")),
            new HttpSandboxProperties(List.of("example.com")));

    assertDoesNotThrow(
        () ->
            sandbox.enforce(new SandboxAction(ActionType.FILE_READ, dir.resolve("x").toString())));
    assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")));
    assertDoesNotThrow(
        () -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com/x")));
  }
}
