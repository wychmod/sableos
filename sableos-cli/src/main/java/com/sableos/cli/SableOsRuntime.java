package com.sableos.cli;

import com.sableos.channel.cli.CliChannel;
import com.sableos.cli.runtime.SandboxWhitelistBootstrap;
import com.sableos.core.agent.AgentService;
import com.sableos.core.sandbox.SandboxWhitelistAdmin;
import com.sableos.core.sandbox.SandboxWhitelistStore;
import com.sableos.core.session.SessionManager;
import com.sableos.storage.sandbox.JpaSandboxWhitelistStore;
import com.sableos.storage.sandbox.SandboxWhitelistEntryRepository;
import com.sableos.storage.sandbox.SandboxWhitelistSeedRepository;
import com.sableos.tool.sandbox.FileSandboxProperties;
import com.sableos.tool.sandbox.HttpSandboxProperties;
import com.sableos.tool.sandbox.Sandbox;
import com.sableos.tool.sandbox.ShellSandboxProperties;
import com.sableos.tool.sandbox.WhitelistSandbox;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 重命令（chat/serve/gateway）的 Spring 装配根（18 节，对齐 oryxos {@code OryxOsRuntime}）。轻命令不进这里。
 *
 * <p>课件坑四：{@code scanBasePackages} 只管普通 Bean，不会带动 JPA 仓库与实体扫描跟着跨模块——存储在独立模块 {@code
 * com.sableos.storage}， 必须显式 {@code @EnableJpaRepositories} + {@code @EntityScan}，否则 "Found 0 JPA
 * repository interfaces"、审计与会话静默写不进去。 这两个注解**只此一处**（boot 的 {@code SableOsApplication} 已剥掉，避免重复注册）。
 *
 * <p>{@code scanBasePackages="com.sableos"} 在运行期（fat JAR 含各模块）收编 boot 的 {@code
 * ReactConfiguration}/{@code ProviderConfiguration}/ {@code AgentConfiguration}/{@code
 * ObservabilityConfig} 等既有装配；本节只在此声明 cli/channel 侧新增的 {@link CliChannel} bean。
 */
@SpringBootApplication(scanBasePackages = "com.sableos")
@EnableJpaRepositories(basePackages = "com.sableos.storage")
@EntityScan(basePackages = "com.sableos.storage")
public class SableOsRuntime {

  @Bean
  public CliChannel cliChannel(AgentService agentService, SessionManager sessionManager) {
    return new CliChannel(agentService, sessionManager);
  }

  /** 白名单存储（008）：JPA 实现，用显式 @Bean 而不是标注式注册，与 storage 模块其他适配器（会话/审计）保持一致—— storage 提供实现类，装配由装配根决定。 */
  @Bean
  public SandboxWhitelistStore sandboxWhitelistStore(
      SandboxWhitelistEntryRepository entryRepository,
      SandboxWhitelistSeedRepository seedRepository) {
    return new JpaSandboxWhitelistStore(entryRepository, seedRepository);
  }

  /**
   * 白名单沙箱（008）：白名单以库为运行时事实源，首次启动时按配置导入一次。
   *
   * <p>返回具体类型而非 {@code Sandbox} 接口：同一实例既被工具按 {@code Sandbox} 注入，也被管理服务按具体类型刷新策略。 Spring
   * 会按实际返回类型注册，因此声明为 {@code Sandbox} 的注入点也能拿到本实例。
   */
  @Bean
  public WhitelistSandbox whitelistSandbox(
      SandboxWhitelistStore store,
      FileSandboxProperties fileProperties,
      ShellSandboxProperties shellProperties,
      HttpSandboxProperties httpProperties,
      Environment environment) {
    return SandboxWhitelistBootstrap.buildSandbox(
        store, fileProperties, shellProperties, httpProperties, environment);
  }

  /** 与 {@link #whitelistSandbox} 同一实例，按 {@code Sandbox} 接口暴露（工具侧注入点用这个类型）。 */
  @Bean
  @Primary
  public Sandbox sandbox(WhitelistSandbox whitelistSandbox) {
    return whitelistSandbox;
  }

  /** 白名单管理服务：写库成功后重建并发布策略快照。 */
  @Bean
  public SandboxWhitelistAdmin sandboxWhitelistAdmin(
      SandboxWhitelistStore store, WhitelistSandbox whitelistSandbox) {
    return SandboxWhitelistBootstrap.buildAdmin(store, whitelistSandbox);
  }
}
