package com.sableos.cli;

import com.sableos.channel.cli.CliChannel;
import com.sableos.core.agent.AgentService;
import com.sableos.core.session.SessionManager;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
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
}
