package com.sableos.memory.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.sableos.memory.LongTermMemoryStore;
import com.sableos.memory.MemoryScope;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * Mem0 外部集成后端：把 {@code append/load/recall} 翻译成自托管 Mem0 server 的 REST 调用。
 *
 * <p>提炼、冲突消解、语义检索都交给 Mem0；本类只做协议转换。写入时把 {@code scope} 放进 metadata， 加载时按 metadata 分 core/archival
 * 拼接。
 */
public class Mem0MemoryStore implements LongTermMemoryStore {

  private static final String ADD_URI = "/v1/memories/";
  private static final String GET_URI = "/v1/memories/";
  private static final String SEARCH_URI = "/v1/memories/search/";

  private final RestClient restClient;
  private final String userId;

  public Mem0MemoryStore(RestClient restClient, String userId) {
    this.restClient = Objects.requireNonNull(restClient, "restClient 不能为空");
    this.userId = Objects.requireNonNull(userId, "userId 不能为空");
  }

  @Override
  public void append(String content, MemoryScope scope, String profileName) {
    Objects.requireNonNull(content, "content 不能为空");
    Objects.requireNonNull(scope, "scope 不能为空");
    Objects.requireNonNull(profileName, "profileName 不能为空");

    restClient
        .post()
        .uri(ADD_URI)
        .body(
            Map.of(
                "messages", List.of(Map.of("role", "user", "content", content)),
                "user_id", userIdOf(profileName),
                "metadata", Map.of("scope", scope.name())))
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  public String load(String profileName) {
    Objects.requireNonNull(profileName, "profileName 不能为空");

    List<Mem0Entry> entries =
        restClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder.path(GET_URI).queryParam("user_id", userIdOf(profileName)).build())
            .retrieve()
            .body(new ParameterizedTypeReference<List<Mem0Entry>>() {});
    if (entries == null) {
      return "";
    }
    String core = render(filterByScope(entries, MemoryScope.CORE));
    String archive = render(filterByScope(entries, MemoryScope.ARCHIVAL));
    return (core + "\n" + archive).strip();
  }

  @Override
  public List<String> recallByKeyword(String keyword, String profileName) {
    Objects.requireNonNull(keyword, "keyword 不能为空");
    Objects.requireNonNull(profileName, "profileName 不能为空");

    List<Mem0SearchResult> results =
        restClient
            .post()
            .uri(SEARCH_URI)
            .body(Map.of("query", keyword, "user_id", userIdOf(profileName)))
            .retrieve()
            .body(new ParameterizedTypeReference<List<Mem0SearchResult>>() {});
    if (results == null) {
      return List.of();
    }
    return results.stream().map(Mem0SearchResult::memory).toList();
  }

  private String userIdOf(String profileName) {
    return userId + ":" + profileName;
  }

  private List<Mem0Entry> filterByScope(List<Mem0Entry> entries, MemoryScope scope) {
    String target = scope.name();
    return entries.stream()
        .filter(e -> target.equals(e.metadata().get("scope").asText("")))
        .toList();
  }

  private String render(List<Mem0Entry> entries) {
    return entries.stream()
        .map(Mem0Entry::memory)
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  record Mem0Entry(String memory, JsonNode metadata) {}

  record Mem0SearchResult(String memory) {}
}
