package com.sableos.boot.agent;

import com.sableos.core.agent.AgentLoader;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.provider.ProvidersProperties;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Agent 装配：启动期全量扫描 {@code .sableos/agents/} 并派生 Profile 注册表（坏目录不阻断启动）。 */
@Configuration
public class AgentConfiguration {

  @Bean
  public AgentLoader agentLoader(
      ProvidersProperties providersProperties,
      @Value("${workspace.root:.sableos}") String workspaceRoot) {
    Set<String> knownProviders =
        providersProperties.providers().stream()
            .map(ProvidersProperties.Provider::name)
            .collect(Collectors.toSet());
    return new AgentLoader(Path.of(workspaceRoot, "agents"), knownProviders);
  }

  @Bean
  public ProfileRegistry profileRegistry(AgentLoader agentLoader) {
    return agentLoader.loadAll();
  }
}
