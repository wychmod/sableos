package com.sableos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sableos.tool.sandbox.PermissiveSandbox;
import com.sableos.tool.sandbox.SandboxViolationException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** HttpTools 的回归测试：JDK HttpServer 假服务（随机端口），零出网。 */
class HttpToolsTest {

  private HttpServer server;
  private final AtomicInteger hits = new AtomicInteger();
  private final List<String> receivedBodies = new ArrayList<>();

  private final HttpTools tools = new HttpTools(new PermissiveSandbox(), RestClient.create());

  @BeforeEach
  void startFakeService() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          if (exchange.getRequestURI().getPath().equals("/error")) {
            byte[] body = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
          }
          receivedBodies.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response = "{\"weather\":\"晴\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopFakeService() {
    server.stop(0);
  }

  private String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  @Test
  @DisplayName("http_get_应能取回响应")
  void httpGet_returnsResponseBody() {
    String result = tools.httpGet(url("/api"));

    assertTrue(result.contains("晴"), result);
  }

  @Test
  @DisplayName("http_get_命中白名单外域名应被拦下")
  void httpGet_domainOutsideWhitelistIsBlocked() {
    HttpTools guarded =
        new HttpTools(
            action -> {
              throw new SandboxViolationException("域名不在白名单");
            },
            RestClient.create());

    assertThrows(SandboxViolationException.class, () -> guarded.httpGet(url("/api")));
    assertEquals(0, hits.get(), "校验不过，请求根本不该发出");
  }

  @Test
  @DisplayName("http_post_应能提交 JSON body 并取回响应")
  void httpPost_returnsResponseBody() {
    String result = tools.httpPost(url("/api"), "{\"city\":\"beijing\"}");

    assertTrue(result.contains("晴"), result);
    assertEquals("{\"city\":\"beijing\"}", receivedBodies.get(0));
  }

  @Test
  @DisplayName("http_get 非 2xx_异常携带状态码（不特判、不自建错误页）")
  void httpGet_nonSuccessStatusCarriesStatusCode() {
    RestClientResponseException ex =
        assertThrows(RestClientResponseException.class, () -> tools.httpGet(url("/error")));

    assertEquals(500, ex.getStatusCode().value());
  }
}
