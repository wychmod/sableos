package com.sableos.tool.sandbox;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 核心阶段唯一一档沙箱实现（技术方案 §6.7）：应用层白名单校验，覆盖文件路径、Shell 命令、HTTP 域名三类。
 *
 * <p>接口只暴露 {@link #enforce(SandboxAction)}；三个私有校验方法是本档实现的内部组织，不进入接口契约。 空配置 =
 * 全部拒绝（deny-all），不是"不校验"。本档是劝阻级防线，防的是模型误操作，不承诺拦住蓄意攻击。
 *
 * <p>校验失败抛 {@link SandboxViolationException}，由工具调用方上抛、经既有执行层收敛为失败审计； 本类不写日志，避免用户可控目标进入日志。
 *
 * <p><b>策略快照（008-sandbox-whitelist-admin）</b>：三类白名单放在一个不可变的 {@link WhitelistPolicy} 里，由 {@link
 * AtomicReference} 整体发布。{@link #enforce} 在入口只读一次快照，因而单次校验内看到的永远是同一版本——不会"文件规则用新的、域名规则用旧的"。 {@link
 * #refresh} 是无锁写（volatile 语义的安全发布），校验热路径上没有任何锁。
 *
 * <p><b>为什么不再标 {@code @Component}</b>：008 起白名单以数据库为事实源，初始策略必须由装配层"先导入或从库恢复、再构造"。组件扫描无法表达这个顺序，
 * 且会尝试无参实例化而失败；现在由 {@code SableOsRuntime} 的 {@code @Bean} 显式创建。
 */
public class WhitelistSandbox implements Sandbox {

  private static final String HTTP_SCHEME = "http";
  private static final String HTTPS_SCHEME = "https";
  private static final String WILDCARD_PREFIX = "*.";
  private static final char ASCII_UPPER_A = 'A';
  private static final char ASCII_UPPER_Z = 'Z';
  private static final int ASCII_CASE_OFFSET = 'a' - 'A';

  private final AtomicReference<WhitelistPolicy> policy;

  /**
   * 按三项配置构造初始策略（第 24 节行为不变）。
   *
   * <p>目标与白名单根都绝对化后再比较——相对根（如 .sableos/agents/x）才能正确命中。
   */
  public WhitelistSandbox(
      FileSandboxProperties fileProperties,
      ShellSandboxProperties shellProperties,
      HttpSandboxProperties httpProperties) {
    this(
        new WhitelistPolicy(
            toRoots(fileProperties.allowedPaths()),
            toCommands(shellProperties.allowedCommands()),
            toDomainPatterns(httpProperties.allowedDomains())));
  }

  /** 直接用现成策略构造（装配层重建快照时使用）。 */
  WhitelistSandbox(WhitelistPolicy initialPolicy) {
    this.policy = new AtomicReference<>(initialPolicy);
  }

  /**
   * 用现成策略创建沙箱（装配层用：策略来自数据库而不是配置文件）。
   *
   * <p>公开工厂而非公开构造器：让"从配置构造"与"从库策略构造"两条路径在调用点就能区分开。
   */
  public static WhitelistSandbox withPolicy(WhitelistPolicy initialPolicy) {
    return new WhitelistSandbox(initialPolicy);
  }

  private static List<Path> toRoots(List<String> rawPaths) {
    return rawPaths.stream()
        .filter(path -> !path.isBlank())
        .map(path -> Path.of(path).toAbsolutePath().normalize())
        .toList();
  }

  private static Set<String> toCommands(List<String> rawCommands) {
    return rawCommands.stream().filter(command -> !command.isBlank()).collect(Collectors.toSet());
  }

  private static List<String> toDomainPatterns(List<String> rawDomains) {
    return rawDomains.stream()
        .filter(domain -> !domain.isBlank())
        .map(domain -> domain.toLowerCase(Locale.ROOT))
        .toList();
  }

  /**
   * 替换生效策略（包内可见：只允许同包的管理服务调用）。
   *
   * <p>失败时不调用本方法——由调用方保证"库写成功才换快照"，避免内存与库不一致。
   */
  void refresh(WhitelistPolicy newPolicy) {
    policy.set(newPolicy);
  }

  @Override
  public void enforce(SandboxAction action) {
    if (action == null || action.type() == null) {
      throw new SandboxViolationException("沙箱动作缺少类型");
    }
    // 一次调用只读一次：本方法之后的所有判断都基于这同一个版本
    WhitelistPolicy current = policy.get();
    switch (action.type()) {
      case FILE_READ:
      case FILE_WRITE:
        checkFilePath(current, action.target());
        break;
      case SHELL_COMMAND:
        checkShellCommand(current, action.target());
        break;
      case HTTP_REQUEST:
        checkHttpUrl(current, action.target());
        break;
      default:
        throw new IllegalStateException("未知的动作类型：" + action.type());
    }
  }

  private void checkFilePath(WhitelistPolicy current, String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      throw new SandboxViolationException("路径为空，无法校验");
    }
    Path target;
    try {
      target = Path.of(rawPath).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      throw new SandboxViolationException("路径无法解析: " + rawPath);
    }
    boolean allowed = current.allowedRoots().stream().anyMatch(target::startsWith);
    if (!allowed) {
      throw new SandboxViolationException("路径不在白名单内: " + rawPath);
    }
  }

  private void checkShellCommand(WhitelistPolicy current, String command) {
    if (command == null || command.isBlank()) {
      throw new SandboxViolationException("命令为空，无法校验");
    }
    String firstToken = command.strip().split("\\s+")[0];
    if (!current.allowedCommands().contains(firstToken)) {
      throw new SandboxViolationException("命令不在白名单内: " + firstToken);
    }
  }

  private void checkHttpUrl(WhitelistPolicy current, String rawUrl) {
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
        current.allowedDomainPatterns().stream()
            .anyMatch(pattern -> matchesDomain(normalizedHost, pattern));
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
