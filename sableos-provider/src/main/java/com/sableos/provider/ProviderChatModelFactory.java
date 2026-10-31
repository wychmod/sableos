package com.sableos.provider;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * provider name → ChatModel 的显式映射工厂（宪法原则 IV）：遍历清单逐条构造，绝不做类型扫描。
 *
 * <p>构造共性：HTTP 客户端用 JDK HttpClient（HTTP/1.1、不跟随重定向、连接 10s / 读取 120s）；
 * 单次调用（maxAttempts=1）不引入隐式重试；defaultOptions 内嵌关闭自动工具执行（宪法原则 III）。 产物只交给 ProviderService
 * 实现的内部路由表，不注册为 Spring bean。
 */
public class ProviderChatModelFactory {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);
  private static final String TRAILING_SLASH = "/";
  private static final String API_VERSION_SUFFIX = "/v1";

  /** 依据清单构造 name → ChatModel 映射；空清单返回空表。 */
  public Map<String, ChatModel> build(ProvidersProperties properties) {
    Map<String, ChatModel> models = new LinkedHashMap<>();
    for (ProvidersProperties.Provider provider : properties.providers()) {
      models.put(provider.name(), create(provider));
    }
    return Map.copyOf(models);
  }

  private static ChatModel create(ProvidersProperties.Provider provider) {
    if (ProvidersProperties.TYPE_MOCK.equals(provider.effectiveType())) {
      // 27 节：显式映射表里的确定性 mock provider，不建 HTTP 客户端、不读 key。
      return new MockChatModel();
    }
    RetryTemplate retryTemplate = RetryTemplate.builder().maxAttempts(1).build();
    String baseUrl = normalizeBaseUrl(provider.baseUrl());
    if (ProvidersProperties.TYPE_DASHSCOPE.equals(provider.effectiveType())) {
      DashScopeApi.Builder apiBuilder = DashScopeApi.builder().apiKey(provider.apiKey());
      if (baseUrl != null) {
        apiBuilder.baseUrl(baseUrl);
      }
      return DashScopeChatModel.builder()
          .dashScopeApi(apiBuilder.restClientBuilder(restClientBuilder()).build())
          .defaultOptions(
              DashScopeChatOptions.builder().withInternalToolExecutionEnabled(false).build())
          .retryTemplate(retryTemplate)
          .build();
    }
    OpenAiApi api =
        OpenAiApi.builder()
            .apiKey(provider.apiKey())
            .baseUrl(baseUrl)
            .restClientBuilder(restClientBuilder())
            .build();
    return OpenAiChatModel.builder()
        .openAiApi(api)
        .defaultOptions(OpenAiChatOptions.builder().internalToolExecutionEnabled(false).build())
        .retryTemplate(retryTemplate)
        .build();
  }

  /** base-url 归一：去尾部 {@code /} 与 {@code /v1}（OpenAI 兼容端点的两种常见写法等价）。 */
  static String normalizeBaseUrl(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return null;
    }
    String normalized = baseUrl.trim();
    while (normalized.endsWith(TRAILING_SLASH)) {
      normalized = normalized.substring(0, normalized.length() - TRAILING_SLASH.length());
    }
    if (normalized.endsWith(API_VERSION_SUFFIX)) {
      normalized = normalized.substring(0, normalized.length() - API_VERSION_SUFFIX.length());
    }
    while (normalized.endsWith(TRAILING_SLASH)) {
      normalized = normalized.substring(0, normalized.length() - TRAILING_SLASH.length());
    }
    return normalized;
  }

  private static RestClient.Builder restClientBuilder() {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(READ_TIMEOUT);
    return RestClient.builder().requestFactory(requestFactory);
  }
}
