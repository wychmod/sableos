package com.sableos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sableos.core.provider.ProviderStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 连通探测的本地验收：JDK 内置 HttpServer 模拟可达端点，关闭端口模拟不可达。
 *
 * <p>不碰外网、不调模型、不消耗 token；任何 HTTP 响应（含 401）都证明端点可达。
 */
class HttpProviderHealthProbeTest {

  @Test
  @DisplayName("任意HTTP响应即视为可达_且不回显密钥")
  void anyHttpResponseMeansReachable() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
        });
    server.start();
    try {
      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
      List<ProviderStatus> statuses =
          probe(new ProvidersProperties.Provider("local", "openai", "test-key", baseUrl));

      assertThat(statuses).hasSize(1);
      ProviderStatus status = statuses.get(0);
      assertThat(status.name()).isEqualTo("local");
      assertThat(status.type()).isEqualTo("openai");
      assertThat(status.reachable()).isTrue();
      assertThat(status.error()).isNull();
      assertThat(status.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0L);
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("端口关闭_判不可达且错误原因已净化")
  void closedPortMeansUnreachable() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    List<ProviderStatus> statuses =
        probe(
            new ProvidersProperties.Provider(
                "local", "openai", "test-key", "http://127.0.0.1:" + closedPort));

    assertThat(statuses).hasSize(1);
    ProviderStatus status = statuses.get(0);
    assertThat(status.reachable()).isFalse();
    assertThat(status.error()).isNotBlank();
    assertThat(status.error()).doesNotContain("\n").doesNotContain("\r");
  }

  @Test
  @DisplayName("空清单_返回空表")
  void emptyProvidersReturnsEmptyList() {
    HttpProviderHealthProbe probe = new HttpProviderHealthProbe(new ProvidersProperties(List.of()));
    assertThat(probe.probe()).isEmpty();
  }

  private static List<ProviderStatus> probe(ProvidersProperties.Provider provider) {
    return new HttpProviderHealthProbe(new ProvidersProperties(List.of(provider))).probe();
  }
}
