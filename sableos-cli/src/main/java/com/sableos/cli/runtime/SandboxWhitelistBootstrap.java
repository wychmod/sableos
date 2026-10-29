package com.sableos.cli.runtime;

import com.sableos.core.sandbox.SandboxWhitelistAdmin;
import com.sableos.core.sandbox.SandboxWhitelistStore;
import com.sableos.core.sandbox.WhitelistEntry;
import com.sableos.core.sandbox.WhitelistType;
import com.sableos.tool.sandbox.DefaultSandboxWhitelistAdmin;
import com.sableos.tool.sandbox.FileSandboxProperties;
import com.sableos.tool.sandbox.HttpSandboxProperties;
import com.sableos.tool.sandbox.ShellSandboxProperties;
import com.sableos.tool.sandbox.WhitelistPolicy;
import com.sableos.tool.sandbox.WhitelistRules;
import com.sableos.tool.sandbox.WhitelistSandbox;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Sandbox 白名单的装配与首次导入（008-sandbox-whitelist-admin）。
 *
 * <p><b>为什么在 cli 而不是 boot</b>：自第 18 节起真正的装配根是 {@code SableOsRuntime}（唯一声明
 * {@code @EnableJpaRepositories} / {@code @EntityScan} 的地方）。放 boot 的话 JPA 仓库在该上下文不可用；oryxos 同样把播种放在
 * {@code OryxOsRuntime}。
 *
 * <p><b>为什么用 {@code @Bean} 而非 {@code ApplicationRunner}</b>：Bean 创建完成即代表策略已就绪，不存在"Bean
 * 已存在但还没导入"的窗口。 导入失败直接让上下文启动失败（{@link #seed}），符合"配置错误不许静默降级"的口径。
 */
public final class SandboxWhitelistBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(SandboxWhitelistBootstrap.class);

  private static final String ADMIN_ENABLED_PROPERTY = "sableos.sandbox.admin.enabled";

  private SandboxWhitelistBootstrap() {}

  /**
   * 构造并发布初始策略：已导入过则只从库恢复；从未导入则先按配置播种（严格模式）再恢复。
   *
   * @throws com.sableos.core.sandbox.WhitelistValidationException 配置里有非法值时**启动失败**
   */
  public static WhitelistSandbox buildSandbox(
      SandboxWhitelistStore store,
      FileSandboxProperties fileProperties,
      ShellSandboxProperties shellProperties,
      HttpSandboxProperties httpProperties,
      Environment environment) {
    seedIfNeeded(store, fileProperties, shellProperties, httpProperties);
    WhitelistSandbox sandbox = WhitelistSandbox.withPolicy(toPolicy(store.findAll(null)));
    warnIfAdminEnabled(isAdminEnabled(environment));
    return sandbox;
  }

  /** 管理服务：把存储与运行时沙箱接到一起。 */
  public static SandboxWhitelistAdmin buildAdmin(
      SandboxWhitelistStore store, WhitelistSandbox sandbox) {
    return new DefaultSandboxWhitelistAdmin(store, sandbox);
  }

  /**
   * 首次导入：判据是标记表有没有行，不是条目表空不空——否则管理员删光条目后重启会把配置再插一遍。
   *
   * <p>严格模式：任一条配置非法即抛出，让启动失败，而不是跳过脏条目造成"配了却没生效"。归一在我方完成，落库与打标记由存储层在一个事务里做。
   */
  private static void seedIfNeeded(
      SandboxWhitelistStore store,
      FileSandboxProperties fileProperties,
      ShellSandboxProperties shellProperties,
      HttpSandboxProperties httpProperties) {
    if (store.isSeeded()) {
      LOG.info("Sandbox whitelist already seeded; configuration file values are ignored");
      return;
    }
    List<SandboxWhitelistStore.SeedEntry> entries = new ArrayList<>();
    collect(entries, WhitelistType.FILE_PATH, fileProperties.allowedPaths());
    collect(entries, WhitelistType.SHELL_COMMAND, shellProperties.allowedCommands());
    collect(entries, WhitelistType.HTTP_DOMAIN, httpProperties.allowedDomains());

    int imported = store.seedIfNeeded(entries);
    LOG.info("Sandbox whitelist seeded from configuration: {} entries", imported);
  }

  private static void collect(
      List<SandboxWhitelistStore.SeedEntry> target, WhitelistType type, List<String> raw) {
    if (raw == null) {
      return;
    }
    for (String value : raw) {
      if (value == null || value.isBlank()) {
        continue;
      }
      // 归一与校验在此完成：非法值直接抛 WhitelistValidationException，让启动失败（严格模式）
      String normalized = WhitelistRules.normalize(type, value);
      target.add(
          new SandboxWhitelistStore.SeedEntry(type, normalized, "imported from configuration"));
    }
  }

  private static WhitelistPolicy toPolicy(List<WhitelistEntry> entries) {
    List<Path> roots = new ArrayList<>();
    Set<String> commands = new LinkedHashSet<>();
    List<String> domains = new ArrayList<>();
    for (WhitelistEntry entry : entries) {
      // 传统 switch（colon + break）：P3C SwitchStatementRule 不认增强 switch 的 arrow 形态
      switch (entry.type()) {
        case FILE_PATH:
          roots.add(Path.of(entry.value()).toAbsolutePath().normalize());
          break;
        case SHELL_COMMAND:
          commands.add(entry.value());
          break;
        case HTTP_DOMAIN:
          domains.add(entry.value());
          break;
        default:
          throw new IllegalStateException("未知的白名单类型：" + entry.type());
      }
    }
    return new WhitelistPolicy(roots, commands, domains);
  }

  private static void warnIfAdminEnabled(boolean enabled) {
    if (!enabled) {
      LOG.info("Sandbox whitelist admin API is disabled (sableos.sandbox.admin.enabled=false)");
      return;
    }
    LOG.warn(
        "Sandbox whitelist admin API is ENABLED (root-equivalent): it can add any path and any"
            + " interpreter. Bind to loopback only or put it behind an authenticating proxy."
            + " Disable with sableos.sandbox.admin.enabled=false.");
  }

  /** 与 {@code @ConditionalOnProperty(havingValue = "true", matchIfMissing = true)} 同口径判定。 */
  private static boolean isAdminEnabled(Environment environment) {
    String value = environment.getProperty(ADMIN_ENABLED_PROPERTY);
    if (value == null) {
      return true;
    }
    if (value.isBlank()) {
      return false;
    }
    return "true".equals(asciiLowerCase(value));
  }

  /** 仅折叠 ASCII 大小写：与 WhitelistSandbox 同口径，避免 Unicode 大小写映射被用于开关判定（IMPROPER_UNICODE）。 */
  private static String asciiLowerCase(String value) {
    StringBuilder builder = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        c = (char) (c + ('a' - 'A'));
      }
      builder.append(c);
    }
    return builder.toString();
  }
}
