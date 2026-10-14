package com.sableos.boot.provider;

import com.sableos.core.provider.LlmCallAuditor;
import com.sableos.core.provider.ProviderService;
import com.sableos.provider.ProviderChatModelFactory;
import com.sableos.provider.ProvidersProperties;
import com.sableos.provider.SpringAiProviderServiceImpl;
import com.sableos.provider.ToolSchemaAdapter;
import com.sableos.storage.JpaLlmCallAuditor;
import com.sableos.storage.LlmCallRepository;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provider 装配：清单校验（失败快、点名）→ 显式构造 ChatModel 映射 → 适配器 → 审计 → ProviderService。
 *
 * <p>ChatModel 映射不注册为 bean——显式映射表只交给 ProviderService 的内部路由（宪法原则 IV）。
 */
@Configuration
@EnableConfigurationProperties(ProvidersProperties.class)
public class ProviderConfiguration {

  @Bean
  public ToolSchemaAdapter toolSchemaAdapter() {
    return new ToolSchemaAdapter();
  }

  @Bean
  public LlmCallAuditor llmCallAuditor(LlmCallRepository llmCallRepository) {
    return new JpaLlmCallAuditor(llmCallRepository);
  }

  @Bean
  public ProviderService providerService(
      ProvidersProperties providersProperties,
      ToolSchemaAdapter toolSchemaAdapter,
      LlmCallAuditor llmCallAuditor) {
    providersProperties.validate();
    Map<String, ChatModel> models = new ProviderChatModelFactory().build(providersProperties);
    return new SpringAiProviderServiceImpl(
        models, providersProperties, toolSchemaAdapter, llmCallAuditor);
  }
}
