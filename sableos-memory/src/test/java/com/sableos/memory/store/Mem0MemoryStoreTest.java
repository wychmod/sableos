package com.sableos.memory.store;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sableos.memory.Mem0Mode;
import com.sableos.memory.MemoryScope;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Mem0 后端测试：Platform v3 与 OSS 两套路径、filters、scope 过滤与归档截断。 */
class Mem0MemoryStoreTest {

  private MockRestServiceServer server;
  private Mem0MemoryStore store;

  private void bind(String baseUrl, Mem0Mode mode) {
    RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
    server = MockRestServiceServer.bindTo(builder).build();
    store = new Mem0MemoryStore(builder.build(), "sableos", mode);
  }

  @Nested
  @DisplayName("Platform v3")
  class Platform {

    @BeforeEach
    void setUp() {
      bind("https://api.mem0.ai", Mem0Mode.PLATFORM);
    }

    @Test
    @DisplayName("append 走 v3 add 且同步原样写入（infer=false + scope metadata）")
    void appendPostsSynchronouslyWithScopeMetadata() {
      server
          .expect(requestTo("https://api.mem0.ai/v3/memories/add/"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(content().contentType(MediaType.APPLICATION_JSON))
          .andExpect(content().string(containsString("\"infer\":false")))
          .andExpect(content().string(containsString("\"scope\":\"CORE\"")))
          .andExpect(content().string(containsString("\"user_id\":\"sableos:p1\"")))
          .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

      store.append("核心事实", MemoryScope.CORE, "p1");

      server.verify();
    }

    @Test
    @DisplayName("load 走 v3 分页并以 filters 传 user_id")
    void loadPagesWithFilters() {
      server
          .expect(requestTo(containsString("/v3/memories/?page=1&page_size=200")))
          .andExpect(method(HttpMethod.POST))
          .andExpect(content().string(containsString("\"filters\":{\"user_id\":\"sableos:p1\"}")))
          .andRespond(
              withSuccess(
                  """
                  {"count":2,"next":null,"previous":null,"results":[
                    {"memory":"核心","metadata":{"scope":"CORE"}},
                    {"memory":"归档","metadata":{"scope":"ARCHIVAL"}}]}
                  """,
                  MediaType.APPLICATION_JSON));

      String loaded = store.load("p1");

      assertTrue(loaded.contains("核心"));
      assertTrue(loaded.contains("归档"));
      server.verify();
    }

    @Test
    @DisplayName("load 归档区截断到 100 条，核心区不截断")
    void loadTruncatesArchiveOnly() {
      StringBuilder results =
          new StringBuilder("[{\"memory\":\"核心\",\"metadata\":{\"scope\":\"CORE\"}}");
      for (int i = 0; i < 120; i++) {
        results
            .append(",{\"memory\":\"归档")
            .append(i)
            .append("\",\"metadata\":{\"scope\":\"ARCHIVAL\"}}");
      }
      results.append("]}");
      String body = "{\"count\":121,\"next\":null,\"results\":" + results;

      server
          .expect(requestTo(containsString("/v3/memories/?page=1&page_size=200")))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

      String loaded = store.load("p1");

      assertTrue(loaded.contains("核心"));
      assertTrue(loaded.contains("归档99"));
      assertFalse(loaded.contains("归档100"));
      server.verify();
    }

    @Test
    @DisplayName("recall 走 v3 search，且结果只保留归档区")
    void recallSearchesAndKeepsArchiveOnly() {
      server
          .expect(requestTo("https://api.mem0.ai/v3/memories/search/"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(content().string(containsString("\"filters\":{\"user_id\":\"sableos:p1\"}")))
          .andExpect(content().string(containsString("\"query\":\"关键词\"")))
          .andRespond(
              withSuccess(
                  """
                  {"results":[
                    {"memory":"核心偏好","metadata":{"scope":"CORE"}},
                    {"memory":"归档命中","metadata":{"scope":"ARCHIVAL"}}]}
                  """,
                  MediaType.APPLICATION_JSON));

      List<String> hits = store.recallByKeyword("关键词", "p1");

      assertEquals(List.of("归档命中"), hits);
      server.verify();
    }
  }

  @Nested
  @DisplayName("OSS 自托管 server")
  class Oss {

    @BeforeEach
    void setUp() {
      bind("http://localhost:8888", Mem0Mode.OSS);
    }

    @Test
    @DisplayName("append 走 /memories，实体 ID 在顶层")
    void appendPostsToOssEndpoint() {
      server
          .expect(requestTo("http://localhost:8888/memories"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(content().string(containsString("\"user_id\":\"sableos:p1\"")))
          .andExpect(content().string(containsString("\"infer\":false")))
          .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

      store.append("归档事实", MemoryScope.ARCHIVAL, "p1");

      server.verify();
    }

    @Test
    @DisplayName("load 走 GET /memories?user_id=，响应为裸数组")
    void loadGetsOssArray() {
      server
          .expect(requestTo(containsString("/memories?user_id=sableos:p1")))
          .andExpect(method(HttpMethod.GET))
          .andRespond(
              withSuccess(
                  """
                  [{"memory":"核心","metadata":{"scope":"CORE"}},
                   {"memory":"归档","metadata":{"scope":"ARCHIVAL"}}]
                  """,
                  MediaType.APPLICATION_JSON));

      String loaded = store.load("p1");

      assertTrue(loaded.contains("核心"));
      assertTrue(loaded.contains("归档"));
      server.verify();
    }

    @Test
    @DisplayName("recall 走 POST /search，且只保留归档区")
    void recallPostsOssSearch() {
      server
          .expect(requestTo("http://localhost:8888/search"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(content().string(containsString("\"user_id\":\"sableos:p1\"")))
          .andExpect(content().string(containsString("\"query\":\"关键词\"")))
          .andRespond(
              withSuccess(
                  """
                  {"results":[
                    {"memory":"核心偏好","metadata":{"scope":"CORE"}},
                    {"memory":"归档命中","metadata":{"scope":"ARCHIVAL"}}]}
                  """,
                  MediaType.APPLICATION_JSON));

      List<String> hits = store.recallByKeyword("关键词", "p1");

      assertEquals(List.of("归档命中"), hits);
      server.verify();
    }
  }
}
