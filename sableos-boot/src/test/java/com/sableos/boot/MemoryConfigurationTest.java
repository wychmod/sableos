package com.sableos.boot;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sableos.memory.LongTermMemoryStore;
import com.sableos.memory.Mem0Mode;
import com.sableos.memory.MemoryScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Mem0 装配测试：两种形态的默认地址、鉴权头与缺 key 快速失败。 */
class MemoryConfigurationTest {

  @Test
  @DisplayName("platform：默认 api.mem0.ai + Authorization: Token")
  void platformUsesTokenHeaderAndDefaultBaseUrl() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    LongTermMemoryStore store =
        MemoryConfiguration.mem0Store(builder, Mem0Mode.PLATFORM, null, "test-key", "sableos");

    server
        .expect(requestTo("https://api.mem0.ai/v3/memories/add/"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Token test-key"))
        .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

    store.append("x", MemoryScope.CORE, "p1");

    server.verify();
  }

  @Test
  @DisplayName("oss：默认 localhost:8888 + X-API-Key")
  void ossUsesApiKeyHeaderAndLocalDefault() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    LongTermMemoryStore store =
        MemoryConfiguration.mem0Store(builder, Mem0Mode.OSS, null, "m0sk_test", "sableos");

    server
        .expect(requestTo("http://localhost:8888/memories"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-API-Key", "m0sk_test"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    store.append("x", MemoryScope.ARCHIVAL, "p1");

    server.verify();
  }

  @Test
  @DisplayName("oss 关鉴权：无 api-key 时不发鉴权头")
  void ossWithoutKeyOmitsHeader() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    LongTermMemoryStore store =
        MemoryConfiguration.mem0Store(builder, Mem0Mode.OSS, null, "", "sableos");

    server
        .expect(requestTo("http://localhost:8888/memories"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    store.append("x", MemoryScope.ARCHIVAL, "p1");

    server.verify();
  }

  @Test
  @DisplayName("platform 缺 key：启动即失败，不静默发匿名请求")
  void platformWithoutKeyFailsFast() {
    assertThrows(
        IllegalStateException.class,
        () ->
            MemoryConfiguration.mem0Store(
                RestClient.builder(), Mem0Mode.PLATFORM, null, "", "sableos"));
  }
}
