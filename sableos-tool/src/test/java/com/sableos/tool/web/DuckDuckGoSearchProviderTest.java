package com.sableos.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sableos.tool.sandbox.PermissiveSandbox;
import com.sableos.tool.web.SearchProvider.SearchResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** DuckDuckGoSearchProvider 的解析回归：JDK HttpServer 假服务（随机端口）返回 Instant Answer JSON，零出网。 */
class DuckDuckGoSearchProviderTest {

  private static final String FIXTURE =
      "{\"Heading\":\"SableOS\","
          + "\"AbstractText\":\"一个可私有部署的 Agent OS\","
          + "\"AbstractURL\":\"https://example.com/sableos\","
          + "\"RelatedTopics\":["
          + "{\"Text\":\"Agent - 运行时内核\",\"FirstURL\":\"https://example.com/agent\"},"
          + "{\"Topics\":[{\"Text\":\"MCP - 协议\",\"FirstURL\":\"https://example.com/mcp\"}]}"
          + "]}";

  private HttpServer server;

  @BeforeEach
  void startFakeService() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] response = FIXTURE.getBytes(StandardCharsets.UTF_8);
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

  @Test
  @DisplayName("web_search_应解析 Instant Answer 的摘要与相关主题（含分组）")
  void duckDuckGoSearch_parsesInstantAnswerJson() {
    String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    DuckDuckGoSearchProvider provider =
        new DuckDuckGoSearchProvider(new PermissiveSandbox(), RestClient.create(), endpoint);

    List<SearchResult> results = provider.search("agent os");

    assertEquals(3, results.size(), results.toString());
    SearchResult abstractResult = results.get(0);
    assertEquals("SableOS", abstractResult.title());
    assertEquals("https://example.com/sableos", abstractResult.url());
    assertEquals("一个可私有部署的 Agent OS", abstractResult.snippet());
    assertTrue(
        results.stream().anyMatch(r -> "https://example.com/agent".equals(r.url())), "扁平时事被收拢");
    assertTrue(
        results.stream().anyMatch(r -> "https://example.com/mcp".equals(r.url())), "分组内嵌套时事被递归收拢");
  }
}
