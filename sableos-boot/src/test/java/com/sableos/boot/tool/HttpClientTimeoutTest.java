package com.sableos.boot.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * HTTP 超时兜底验收（课件 28 节 §2.4「该超时的都要超时」）。
 *
 * <p>共享 {@link RestClient} 被 {@code http_get} / {@code http_post} / {@code notify} / web search
 * 共用；它一旦无界， 一个挂死的对端就能长期占住调度线程池（池大小 4）。这里用一个「接了不回」的本地服务验证读取超时确实生效。
 */
class HttpClientTimeoutTest {

  private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

  private static final HttpServer SLOW_SERVER = startSlowServer();

  @AfterAll
  static void stopServer() {
    SLOW_SERVER.stop(0);
  }

  @Test
  @DisplayName("读取超时_挂死的对端被按时掐断而不是无限等待")
  void readTimeout_abortsHangingPeer() {
    RestClient client = new ToolConfiguration().restClient(Duration.ofSeconds(1), READ_TIMEOUT);
    String url = "http://127.0.0.1:" + SLOW_SERVER.getAddress().getPort() + "/slow";

    long startNanos = System.nanoTime();
    assertThatThrownBy(() -> client.get().uri(url).retrieve().body(String.class))
        .isInstanceOf(ResourceAccessException.class);
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

    // 对端要睡 5 秒；超时生效时必须远早于它返回。
    assertThat(elapsedMs).as("应在读取超时附近返回，而不是等到对端答复").isLessThan(3_000L);
  }

  private static HttpServer startSlowServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/slow",
          exchange -> {
            try {
              Thread.sleep(5_000L);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            byte[] body = "late".getBytes(StandardCharsets.UTF_8);
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
