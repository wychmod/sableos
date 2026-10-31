package com.sableos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

/**
 * 工厂构造验收：两套协议栈（openai / dashscope）都能显式构造。
 *
 * <p>dashscope starter 排除了 RAG/OpenNLP/okhttp 等 chat 路径不触达的重传递依赖（见 pom 注释）， 本测试钉住"排除后 dashscope
 * 模型仍可构造"——若未来依赖升级让构造路径需要被排除的类，这里立刻红。
 */
class ProviderChatModelFactoryTest {

  @Test
  @DisplayName("工厂_显式构造两类模型_排除重依赖后仍可构造")
  void build_constructsBothProtocolStacks() {
    ProvidersProperties properties =
        new ProvidersProperties(
            List.of(
                new ProvidersProperties.Provider(
                    "deepseek", "openai", "test-key", "https://example.com"),
                new ProvidersProperties.Provider("qwen", "dashscope", "test-key-2", null)));

    Map<String, ChatModel> models = new ProviderChatModelFactory().build(properties);

    assertThat(models).containsOnlyKeys("deepseek", "qwen");
    assertThat(models.get("deepseek")).isInstanceOf(OpenAiChatModel.class);
    assertThat(models.get("qwen")).isInstanceOf(DashScopeChatModel.class);
  }

  @Test
  @DisplayName("工厂_mock型_构造确定性MockChatModel")
  void build_constructsMockModel() {
    ProvidersProperties properties =
        new ProvidersProperties(
            List.of(new ProvidersProperties.Provider("mock", "mock", null, null)));

    Map<String, ChatModel> models = new ProviderChatModelFactory().build(properties);

    assertThat(models).containsOnlyKeys("mock");
    assertThat(models.get("mock")).isInstanceOf(MockChatModel.class);
  }
}
