package com.sableos.tool.sandbox;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 核心阶段唯一一档沙箱实现（技术方案 §6.7）：应用层白名单校验，覆盖文件路径、Shell 命令、HTTP 域名三类。
 *
 * <p>接口只暴露 {@link #enforce(SandboxAction)}；三个私有校验方法是本档实现的内部组织，不进入接口契约。 空配置 =
 * 全部拒绝（deny-all），不是"不校验"。本档是劝阻级防线，防的是模型误操作，不承诺拦住蓄意攻击。
 *
 * <p>校验失败抛 {@link SandboxViolationException}，由工具调用方上抛、经既有执行层收敛为失败审计； 本类不写日志，避免用户可控目标进入日志。
 */
@Component
public class WhitelistSandbox implements Sandbox {

  private static final String HTTP_SCHEME = "http";
  private static final String HTTPS_SCHEME = "https";
  private static final String WILDCARD_PREFIX = "*.";
  private static final char ASCII_UPPER_A = 'A';
  private static final char ASCII_UPPER_Z = 'Z';
  private static final int ASCII_CASE_OFFSET = 'a' - 'A';

  private final List<Path> allowedRoots;
  private final Set<String> allowedCommands;
  private final List<String> allowedDomainPatterns;

  public WhitelistSandbox(
      FileSandboxProperties fileProperties,
      ShellSandboxProperties shellProperties,
      HttpSandboxProperties httpProperties) {
    // 目标与白名单根都绝对化后再比较——相对根（如 .sableos/agents/x）才能正确命中
    this.allowedRoots =
        List.copyOf(
            fileProperties.allowedPaths().stream()
                .filter(path -> !path.isBlank())
                .map(path -> Path.of(path).toAbsolutePath().normalize())
                .toList());
    this.allowedCommands =
        Set.copyOf(
            shellProperties.allowedCommands().stream()
                .filter(command -> !command.isBlank())
                .collect(Collectors.toSet()));
    this.allowedDomainPatterns =
        List.copyOf(
            httpProperties.allowedDomains().stream()
                .filter(domain -> !domain.isBlank())
                .map(domain -> domain.toLowerCase(Locale.ROOT))
                .toList());
  }

  @Override
  public void enforce(SandboxAction action) {
    if (action == null || action.type() == null) {
      throw new SandboxViolationException("沙箱动作缺少类型");
    }
    switch (action.type()) {
      case FILE_READ:
      case FILE_WRITE:
        checkFilePath(action.target());
        break;
      case SHELL_COMMAND:
        checkShellCommand(action.target());
        break;
      case HTTP_REQUEST:
        checkHttpUrl(action.target());
        break;
      default:
        throw new IllegalStateException("未知的动作类型：" + action.type());
    }
  }

  private void checkFilePath(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      throw new SandboxViolationException("路径为空，无法校验");
    }
    Path target;
    try {
      target = Path.of(rawPath).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      throw new SandboxViolationException("路径无法解析: " + rawPath);
    }
    boolean allowed = allowedRoots.stream().anyMatch(target::startsWith);
    if (!allowed) {
      throw new SandboxViolationException("路径不在白名单内: " + rawPath);
    }
  }

  private void checkShellCommand(String command) {
    if (command == null || command.isBlank()) {
      throw new SandboxViolationException("命令为空，无法校验");
    }
    String firstToken = command.strip().split("\\s+")[0];
    if (!allowedCommands.contains(firstToken)) {
      throw new SandboxViolationException("命令不在白名单内: " + firstToken);
    }
  }

  private void checkHttpUrl(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank()) {
      throw new SandboxViolationException("URL 为空，无法校验");
    }
    URI uri;
    try {
      uri = URI.create(rawUrl);
    } catch (IllegalArgumentException e) {
      throw new SandboxViolationException("URL 无法解析: " + rawUrl);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !isHttpScheme(scheme)) {
      throw new SandboxViolationException("URL 协议不在允许范围: " + rawUrl);
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new SandboxViolationException("URL 缺少主机名: " + rawUrl);
    }
    String normalizedHost = host.toLowerCase(Locale.ROOT);
    boolean allowed =
        allowedDomainPatterns.stream().anyMatch(pattern -> matchesDomain(normalizedHost, pattern));
    if (!allowed) {
      throw new SandboxViolationException("域名不在白名单内: " + host);
    }
  }

  private static boolean isHttpScheme(String scheme) {
    // URI scheme 按 RFC 3986 是 ASCII；手写 ASCII 比较，避免 Unicode 大小写映射被误用于安全判定
    return asciiEqualsIgnoreCase(scheme, HTTP_SCHEME)
        || asciiEqualsIgnoreCase(scheme, HTTPS_SCHEME);
  }

  private static boolean asciiEqualsIgnoreCase(String value, String expected) {
    if (value.length() != expected.length()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (asciiLower(value.charAt(i)) != expected.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private static char asciiLower(char value) {
    if (value >= ASCII_UPPER_A && value <= ASCII_UPPER_Z) {
      return (char) (value + ASCII_CASE_OFFSET);
    }
    return value;
  }

  private boolean matchesDomain(String host, String pattern) {
    if (pattern.startsWith(WILDCARD_PREFIX)) {
      // 保留点号边界：evil-example.com 不以 ".example.com" 结尾，不能借 *.example.com 绕过
      return host.endsWith(pattern.substring(1));
    }
    return host.equals(pattern);
  }
}
