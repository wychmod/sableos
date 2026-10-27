package com.sableos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 24 节验收 harness：应用层白名单三类规则（文件路径 / Shell 命令 / HTTP 域名）， 每类"允许 + 拒绝"成对，并覆盖两个关键绕过点——{@code ..} 路径穿越与
 * {@code *.example.com} 形似域。
 */
class WhitelistSandboxTest {

  private static WhitelistSandbox sandbox(
      List<String> paths, List<String> commands, List<String> domains) {
    return new WhitelistSandbox(
        new FileSandboxProperties(paths),
        new ShellSandboxProperties(commands),
        new HttpSandboxProperties(domains));
  }

  private static WhitelistSandbox fileSandbox(Path root) {
    return sandbox(List.of(root.toString()), List.of(), List.of());
  }

  // ---------- 文件路径 ----------

  @Test
  @DisplayName("文件路径_白名单内放行")
  void fileInsideWhitelist_isAllowed(@TempDir Path dir) {
    WhitelistSandbox whitelist = fileSandbox(dir);

    assertDoesNotThrow(
        () ->
            whitelist.enforce(
                new SandboxAction(ActionType.FILE_READ, dir.resolve("note.txt").toString())));
  }

  @Test
  @DisplayName("文件路径_白名单外拒绝")
  void fileOutsideWhitelist_isRejected(@TempDir Path dir) {
    WhitelistSandbox whitelist = fileSandbox(dir);
    String outside = dir.resolveSibling("outside-secret.txt").toString();

    SandboxViolationException failure =
        assertThrows(
            SandboxViolationException.class,
            () -> whitelist.enforce(new SandboxAction(ActionType.FILE_READ, outside)));

    assertTrue(failure.getMessage().contains("路径不在白名单内"), failure.getMessage());
    assertTrue(failure.getMessage().contains(outside), "异常消息必须点名被拒目标: " + failure.getMessage());
  }

  @Test
  @DisplayName("相对路径穿越（.. 爬出白名单目录）被拦")
  void relativePathTraversal_isRejected(@TempDir Path dir) {
    WhitelistSandbox whitelist = fileSandbox(dir);
    String traversal = dir.resolve("../outside/secret.txt").toString();

    assertThrows(
        SandboxViolationException.class,
        () -> whitelist.enforce(new SandboxAction(ActionType.FILE_READ, traversal)));
  }

  @Test
  @DisplayName("相似前缀目录不被白名单根误命中")
  void lookalikePrefix_isRejected(@TempDir Path dir) {
    WhitelistSandbox whitelist = fileSandbox(dir);
    String lookalike =
        dir.resolveSibling(dir.getFileName() + "-evil").resolve("secret.txt").toString();

    assertThrows(
        SandboxViolationException.class,
        () -> whitelist.enforce(new SandboxAction(ActionType.FILE_READ, lookalike)));
  }

  @Test
  @DisplayName("目标恰好等于白名单根_放行")
  void rootItself_isAllowed(@TempDir Path dir) {
    WhitelistSandbox whitelist = fileSandbox(dir);

    assertDoesNotThrow(
        () -> whitelist.enforce(new SandboxAction(ActionType.FILE_READ, dir.toString())));
  }

  @Test
  @DisplayName("相对白名单根与相对目标按当前工作目录解析后可匹配")
  void relativeRoot_isResolvedAgainstWorkingDirectory() {
    WhitelistSandbox whitelist = sandbox(List.of("build/whitelist-relative"), List.of(), List.of());

    assertDoesNotThrow(
        () ->
            whitelist.enforce(
                new SandboxAction(ActionType.FILE_READ, "build/whitelist-relative/file.txt")));
  }

  @Test
  @DisplayName("文件写与文件读共用同一份路径白名单")
  void fileWriteUsesSamePathWhitelist(@TempDir Path dir) {
    WhitelistSandbox whitelist = fileSandbox(dir);

    assertDoesNotThrow(
        () ->
            whitelist.enforce(
                new SandboxAction(ActionType.FILE_WRITE, dir.resolve("out.txt").toString())));
    assertThrows(
        SandboxViolationException.class,
        () ->
            whitelist.enforce(
                new SandboxAction(
                    ActionType.FILE_WRITE, dir.resolveSibling("outside.txt").toString())));
  }

  @Test
  @DisplayName("空白文件路径_拒绝")
  void blankFilePath_isRejected(@TempDir Path dir) {
    WhitelistSandbox whitelist = fileSandbox(dir);

    assertThrows(
        SandboxViolationException.class,
        () -> whitelist.enforce(new SandboxAction(ActionType.FILE_READ, "   ")));
  }

  @Test
  @DisplayName("空文件白名单_全部拒绝")
  void emptyFileWhitelist_deniesAll(@TempDir Path dir) {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of());

    assertThrows(
        SandboxViolationException.class,
        () ->
            whitelist.enforce(
                new SandboxAction(ActionType.FILE_READ, dir.resolve("x").toString())));
  }

  // ---------- Shell 命令 ----------

  @Test
  @DisplayName("Shell_白名单内命令放行")
  void allowedCommand_isAllowed() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of("ls"), List.of());

    assertDoesNotThrow(() -> whitelist.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")));
  }

  @Test
  @DisplayName("Shell_白名单外命令拒绝")
  void commandOutsideWhitelist_isRejected() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of("ls"), List.of());

    SandboxViolationException failure =
        assertThrows(
            SandboxViolationException.class,
            () -> whitelist.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "rm")));

    assertTrue(failure.getMessage().contains("命令不在白名单内"), failure.getMessage());
    assertTrue(failure.getMessage().contains("rm"), "异常消息必须点名被拒目标: " + failure.getMessage());
  }

  @Test
  @DisplayName("Shell_首 token 带前导空白_去空白后放行")
  void leadingWhitespaceIsTrimmed() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of("ls"), List.of());

    assertDoesNotThrow(
        () -> whitelist.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "  ls")));
  }

  @Test
  @DisplayName("Shell_大小写变体_因精确匹配拒绝")
  void caseVariant_isRejected() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of("ls"), List.of());

    assertThrows(
        SandboxViolationException.class,
        () -> whitelist.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "LS")));
  }

  @Test
  @DisplayName("Shell_带参数命令_只比对首 token")
  void commandWithArguments_comparesFirstToken() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of("ls"), List.of());

    assertDoesNotThrow(
        () -> whitelist.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls -la")));
  }

  @Test
  @DisplayName("Shell_空白命令_拒绝")
  void blankCommand_isRejected() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of("ls"), List.of());

    assertThrows(
        SandboxViolationException.class,
        () -> whitelist.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "   ")));
  }

  @Test
  @DisplayName("空 Shell 白名单_全部拒绝")
  void emptyShellWhitelist_deniesAll() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of());

    assertThrows(
        SandboxViolationException.class,
        () -> whitelist.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")));
  }

  // ---------- HTTP 域名 ----------

  @Test
  @DisplayName("HTTP_精确域名放行")
  void exactDomain_isAllowed() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of("example.com"));

    assertDoesNotThrow(
        () ->
            whitelist.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com/x")));
  }

  @Test
  @DisplayName("通配符域名_命中子域")
  void wildcardSubdomain_isAllowed() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of("*.example.com"));

    assertDoesNotThrow(
        () ->
            whitelist.enforce(
                new SandboxAction(ActionType.HTTP_REQUEST, "https://api.example.com/x")));
  }

  @Test
  @DisplayName("通配符域名_命中多级子域")
  void wildcardMatchesMultiLevelSubdomain() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of("*.example.com"));

    assertDoesNotThrow(
        () ->
            whitelist.enforce(
                new SandboxAction(ActionType.HTTP_REQUEST, "https://a.b.example.com/x")));
  }

  @Test
  @DisplayName("通配符域名_不能被形似域名绕过")
  void wildcardDomain_doesNotMatchLookalike() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of("*.example.com"));

    assertThrows(
        SandboxViolationException.class,
        () ->
            whitelist.enforce(
                new SandboxAction(ActionType.HTTP_REQUEST, "https://evil-example.com/x")));
  }

  @Test
  @DisplayName("通配符域名_不命中裸域")
  void wildcardDomain_doesNotMatchBareDomain() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of("*.example.com"));

    assertThrows(
        SandboxViolationException.class,
        () ->
            whitelist.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com/x")));
  }

  @Test
  @DisplayName("HTTP_白名单外域名拒绝")
  void domainOutsideWhitelist_isRejected() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of("example.com"));

    SandboxViolationException failure =
        assertThrows(
            SandboxViolationException.class,
            () ->
                whitelist.enforce(
                    new SandboxAction(ActionType.HTTP_REQUEST, "https://other.org/x")));

    assertTrue(failure.getMessage().contains("域名不在白名单内"), failure.getMessage());
    assertTrue(failure.getMessage().contains("other.org"), "异常消息必须点名被拒目标: " + failure.getMessage());
  }

  @Test
  @DisplayName("HTTP_主机名大小写不敏感")
  void uppercaseHost_isMatchedCaseInsensitively() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of("*.example.com"));

    assertDoesNotThrow(
        () ->
            whitelist.enforce(
                new SandboxAction(ActionType.HTTP_REQUEST, "https://API.EXAMPLE.COM/x")));
  }

  @Test
  @DisplayName("HTTP_带端口 URL_只比对 host")
  void urlWithPort_usesHostOnly() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of("api.example.com"));

    assertDoesNotThrow(
        () ->
            whitelist.enforce(
                new SandboxAction(ActionType.HTTP_REQUEST, "https://api.example.com:8443/x")));
  }

  @Test
  @DisplayName("HTTP_非 HTTP(S) 协议_拒绝")
  void nonHttpScheme_isRejected() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of("example.com"));

    assertThrows(
        SandboxViolationException.class,
        () -> whitelist.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "ftp://example.com/x")));
  }

  @Test
  @DisplayName("HTTP_无 host 的相对地址_拒绝")
  void relativeUrlWithoutHost_isRejected() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of("example.com"));

    assertThrows(
        SandboxViolationException.class,
        () -> whitelist.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "/relative/path")));
  }

  @Test
  @DisplayName("HTTP_无法解析的 URL_拒绝")
  void malformedUrl_isRejected() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of("example.com"));

    assertThrows(
        SandboxViolationException.class,
        () -> whitelist.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "http://[bad")));
  }

  @Test
  @DisplayName("空 HTTP 白名单_全部拒绝")
  void emptyDomainWhitelist_deniesAll() {
    WhitelistSandbox whitelist = sandbox(List.of(), List.of(), List.of());

    assertThrows(
        SandboxViolationException.class,
        () ->
            whitelist.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com/x")));
  }

  // ---------- 配置边界 ----------

  @Test
  @DisplayName("配置列表含 null 条目_跳过而非构造失败")
  void nullConfigEntries_areSkipped(@TempDir Path dir) {
    WhitelistSandbox whitelist =
        new WhitelistSandbox(
            new FileSandboxProperties(Arrays.asList(null, "  ", dir.toString())),
            new ShellSandboxProperties(Arrays.asList(null, "  ", "ls")),
            new HttpSandboxProperties(Arrays.asList(null, "  ", "example.com")));

    assertDoesNotThrow(
        () ->
            whitelist.enforce(
                new SandboxAction(ActionType.FILE_READ, dir.resolve("x").toString())));
    assertDoesNotThrow(() -> whitelist.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")));
    assertDoesNotThrow(
        () ->
            whitelist.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com/x")));
  }
}
