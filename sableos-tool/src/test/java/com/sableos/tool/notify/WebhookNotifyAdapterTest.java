package com.sableos.tool.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 课件《第19节》验收 harness 第一批：WebhookNotifyAdapterTest。
 *
 * <p>假 webhook 用 JDK 内置 HttpServer（本地随机端口、真 HTTP，仍是单测层）——research D-04。 四条回归：①POST 恰好一次 + body 带
 * content；②地址来自 NotifyTarget.config 非硬编码； ③5xx 异常上抛；④缺 url 报错点名且零请求。
 */
class WebhookNotifyAdapterTest {

  /** 一次收到的请求：方法 + 路径 + body。 */
  private record ReceivedRequest(String method, String path, String body) {}

  private HttpServer server;
  private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();
  private volatile int responseStatus = 200;

  private WebhookNotifyAdapter adapter;

  @BeforeEach
  void startFakeWebhook() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/", this::record);
    server.start();
    adapter = new WebhookNotifyAdapter(RestClient.create());
  }

  @AfterEach
  void stopFakeWebhook() {
    server.stop(0);
  }

  private void record(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    received.add(
        new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
    exchange.sendResponseHeaders(responseStatus, -1);
    exchange.close();
  }

  private String urlOf(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  private static NotifyTarget webhookTarget(String url) {
    return new NotifyTarget("webhook", Map.of("url", url));
  }

  @Test
  @DisplayName("发送后收到一次 POST，body 里带 content")
  void sendPostsJsonBodyContainingContent() {
    adapter.send(webhookTarget(urlOf("/team-hook")), "今天 28°C，建议短袖");

    assertEquals(1, received.size(), "恰好一次请求");
    ReceivedRequest request = received.get(0);
    assertEquals("POST", request.method());
    assertTrue(request.body().contains("今天 28°C，建议短袖"), "body 里带 content");
    assertTrue(request.body().contains("\"content\""), "按约定包成 {\"content\": ...}");
  }

  @Test
  @DisplayName("换通知目标只改配置零代码")
  void urlComesFromTargetConfigNotHardcoded() {
    adapter.send(webhookTarget(urlOf("/group-a")), "发到 A 群");
    adapter.send(webhookTarget(urlOf("/group-b")), "发到 B 群");

    assertEquals(2, received.size());
    assertEquals("/group-a", received.get(0).path());
    assertEquals("/group-b", received.get(1).path());
  }

  @Test
  @DisplayName("webhook 返回 5xx 异常向上抛、不静默吞掉")
  void serverErrorPropagatesAsException() {
    responseStatus = 500;

    assertThrows(
        RestClientResponseException.class,
        () -> adapter.send(webhookTarget(urlOf("/flaky")), "hello"));
  }

  @Test
  @DisplayName("缺 url 配置：报错点名且零请求发出")
  void missingUrlFailsFastWithoutRequest() {
    NotifyTarget target = new NotifyTarget("webhook", Map.of());

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> adapter.send(target, "hello"));

    assertTrue(failure.getMessage().contains("url"), "报错点名 url");
    assertEquals(0, received.size(), "零请求发出");
  }
}
