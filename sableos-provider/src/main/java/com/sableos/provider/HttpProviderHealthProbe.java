package com.sableos.provider;

import com.sableos.core.provider.ProviderHealthProbe;
import com.sableos.core.provider.ProviderStatus;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 基于 JDK HttpClient 的提供方连通探测（26 节）：对每个已配置提供方的 base-url 发一次 {@code HEAD}。
 *
 * <p><b>为什么不是调模型</b>：{@code /info} 要的是“网络与端点是否可达”，不是“模型能否推理”。发推理请求既耗 token，又要求有效模型名， CI 与无 key
 * 环境都跑不了。任何 HTTP 响应（含 401/404/405）都说明端点可达，因此都判 {@code reachable=true}；只有连接失败、超时、 非法 URL 才判不可达。
 *
 * <p>顺序探测、单条失败不影响其它条目；不读取、不记录 api-key。
 */
public class HttpProviderHealthProbe implements ProviderHealthProbe {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
  private static final String DASHSCOPE_DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";
  private static final int MAX_ERROR_LENGTH = 200;

  private final ProvidersProperties properties;
  private final HttpClient httpClient;

  public HttpProviderHealthProbe(ProvidersProperties properties) {
    this(properties, defaultClient());
  }

  /** 测试可注入短超时客户端；生产只走上面的单参构造。 */
  HttpProviderHealthProbe(ProvidersProperties properties, HttpClient httpClient) {
    this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient 不能为空");
  }

  @Override
  public List<ProviderStatus> probe() {
    List<ProviderStatus> statuses = new ArrayList<>(properties.providers().size());
    for (ProvidersProperties.Provider provider : properties.providers()) {
      statuses.add(probeOne(provider));
    }
    return List.copyOf(statuses);
  }

  private ProviderStatus probeOne(ProvidersProperties.Provider provider) {
    String baseUrl = resolveBaseUrl(provider);
    long startedAtNanos = System.nanoTime();
    try {
      URI uri = URI.create(baseUrl);
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .method("HEAD", HttpRequest.BodyPublishers.noBody())
              .timeout(READ_TIMEOUT)
              .build();
      httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      return new ProviderStatus(
          provider.name(), provider.effectiveType(), true, elapsedMs(startedAtNanos), null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable(provider, startedAtNanos, e);
    } catch (IOException | RuntimeException e) {
      return unreachable(provider, startedAtNanos, e);
    }
  }

  private static ProviderStatus unreachable(
      ProvidersProperties.Provider provider, long startedAtNanos, Exception error) {
    return new ProviderStatus(
        provider.name(),
        provider.effectiveType(),
        false,
        elapsedMs(startedAtNanos),
        describe(error));
  }

  private static String resolveBaseUrl(ProvidersProperties.Provider provider) {
    String configured = ProviderChatModelFactory.normalizeBaseUrl(provider.baseUrl());
    if (configured != null) {
      return configured;
    }
    // DashScope 的 base-url 可省略（SDK 有默认端点）；探测与 SDK 保持同一默认值
    return DASHSCOPE_DEFAULT_BASE_URL;
  }

  private static String describe(Exception error) {
    String message = error.getMessage();
    String raw =
        (message == null || message.isBlank())
            ? error.getClass().getSimpleName()
            : error.getClass().getSimpleName() + ": " + message;
    String truncated =
        raw.length() > MAX_ERROR_LENGTH ? raw.substring(0, MAX_ERROR_LENGTH) + "..." : raw;
    // findsecbugs 的 CRLF 注入规则只认可 String.replaceAll 形态，勿改用 Pattern/Matcher
    return truncated.replaceAll("[\r\n]", "_");
  }

  private static long elapsedMs(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L;
  }

  private static HttpClient defaultClient() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(CONNECT_TIMEOUT)
        .build();
  }
}
