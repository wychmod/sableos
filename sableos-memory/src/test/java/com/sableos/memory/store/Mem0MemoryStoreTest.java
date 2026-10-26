package com.sableos.memory.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sableos.memory.MemoryScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Mem0 后端测试：mock RestClient，验证 append 请求体带 scope、recall 转发查询。 */
class Mem0MemoryStoreTest {

  private RestClient.Builder builder;
  private MockRestServiceServer server;
  private Mem0MemoryStore store;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder().baseUrl("https://mem0.example.com");
    server = MockRestServiceServer.bindTo(builder).build();
    store = new Mem0MemoryStore(builder.build(), "sableos");
  }

  @Test
  @DisplayName("append 发送 POST 且请求体含 scope metadata")
  void appendPostsWithScopeMetadata() {
    server
        .expect(requestTo("https://mem0.example.com/v1/memories/"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"scope\":\"CORE\"")))
        .andRespond(withSuccess("{\"message\":\"ok\"}", MediaType.APPLICATION_JSON));

    store.append("核心事实", MemoryScope.CORE, "p1");

    server.verify();
  }

  @Test
  @DisplayName("load 发送 GET 并按 user_id 过滤")
  void loadGetsByUserId() {
    server
        .expect(requestTo(org.hamcrest.Matchers.containsString("user_id=sableos")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "[{\"memory\":\"核心\",\"metadata\":{\"scope\":\"CORE\"}}]",
                MediaType.APPLICATION_JSON));

    String loaded = store.load("p1");

    assertTrue(loaded.contains("核心"));
    server.verify();
  }

  @Test
  @DisplayName("recall 发送 POST search 并返回结果")
  void recallPostsSearchQuery() {
    server
        .expect(requestTo("https://mem0.example.com/v1/memories/search/"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"query\":\"关键词\"")))
        .andRespond(withSuccess("[{\"memory\":\"命中\"}]", MediaType.APPLICATION_JSON));

    var hits = store.recallByKeyword("关键词", "p1");

    assertEquals(1, hits.size());
    assertEquals("命中", hits.getFirst());
    server.verify();
  }
}
