package com.sableos.boot.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 重定向不跟随验收（沙箱 SSRF 回归锁）。
 *
 * <p>共享 {@link RestClient} 被 {@code http_get} / {@code http_post} / {@code notify} / web search
 * 共用，而这些工具都只在**发请求前**校验一次域名白名单（{@code sandbox.enforce(HTTP_REQUEST, url)}）。 一旦客户端自行跟随 30x，第二跳的
 * {@code Location} 就完全绕过了白名单——白名单内主机返回 302 指向 {@code 169.254.169.254} 或任意内网端口，就能把沙箱读穿。
 *
 * <p>此前用 {@code SimpleClientHttpRequestFactory}（基于 {@code HttpURLConnection}），其实例级 {@code
 * getInstanceFollowRedirects()} 默认就是 {@code true}，白名单因此形同虚设。本测试用一个 「302 跳到本站另一个端口」的本地服务锁住这个行为。
 */
class HttpClientRedirectTest {

  private static final String SECRET_BODY = "INTERNAL_SECRET_LEAKED";

  /** 重定向目标被真实访问的次数——这是「有没有跟过去」的直接证据，比查响应体更可靠。 */
  private static final AtomicInteger TARGET_HITS = new AtomicInteger();

  private static final HttpServer REDIRECT_SERVER = startRedirectServer();

  private static final HttpServer TARGET_SERVER = startTargetServer();

  @AfterAll
  static void stopServers() {
    REDIRECT_SERVER.stop(0);
    TARGET_SERVER.stop(0);
  }

  @Test
  @DisplayName("30x 重定向_不被自动跟随_否则第二跳绕过域名白名单")
  void redirect_isNotFollowed() {
    RestClient client =
        new ToolConfiguration().restClient(Duration.ofSeconds(5), Duration.ofSeconds(5));
    String url = "http://127.0.0.1:" + REDIRECT_SERVER.getAddress().getPort() + "/start";

    String result = null;
    boolean raised = false;
    try {
      result = client.get().uri(url).retrieve().body(String.class);
    } catch (RuntimeException e) {
      // JDK HttpClient 在 Redirect.NEVER 下把 302 当最终响应，RestClient 判定非 2xx 而抛异常——
      // 同样是可接受的结果：请求没有跟到第二跳。
      raised = true;
    }

    // 直接证据：目标服务一次都没被访问。跟随重定向的实现会把这里变成 1。
    assertThat(TARGET_HITS.get())
        .as("跟随了重定向：目标服务被访问 %d 次（raised=%s, body=%s）", TARGET_HITS.get(), raised, result)
        .isZero();
    assertThat(result).as("不得把重定向目标的内容交出来").isNotEqualTo(SECRET_BODY);
  }

  private static HttpServer startRedirectServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/start",
          exchange -> {
            String location =
                "http://127.0.0.1:" + TARGET_SERVER.getAddress().getPort() + "/secret";
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static HttpServer startTargetServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/secret",
          exchange -> {
            TARGET_HITS.incrementAndGet();
            byte[] body = SECRET_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
