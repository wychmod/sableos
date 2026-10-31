package com.sableos.boot.agent;

import com.sableos.core.agent.AgentLoader;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.tool.SableTool;
import com.sableos.provider.ProvidersProperties;
import com.sableos.tool.ToolRegistry;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Agent 装配：启动期全量扫描 {@code .sableos/agents/} 并派生 Profile 注册表（坏目录不阻断启动）。 */
@Configuration
public class AgentConfiguration {

  /**
   * 工具名单以 {@code Set<String>} 传入而非 {@code ToolRegistry} 对象：core 不得依赖 tool 模块（ArchitectureTest
   * 红线），且该名单只用于「未注册工具告警」，不参与派生校验。
   */
  @Bean
  public AgentLoader agentLoader(
      ProvidersProperties providersProperties,
      ToolRegistry toolRegistry,
      @Value("${workspace.root:.sableos}") String workspaceRoot) {
    Set<String> knownProviders =
        providersProperties.providers().stream()
            .map(ProvidersProperties.Provider::name)
            .collect(Collectors.toSet());
    Set<String> knownTools =
        toolRegistry.all().stream().map(SableTool::getName).collect(Collectors.toSet());
    return new AgentLoader(Path.of(workspaceRoot, "agents"), knownProviders, knownTools);
  }

  @Bean
  public ProfileRegistry profileRegistry(AgentLoader agentLoader) {
    return agentLoader.loadAll();
  }
}
