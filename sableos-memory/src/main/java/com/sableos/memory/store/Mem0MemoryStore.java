package com.sableos.memory.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.sableos.memory.LongTermMemoryStore;
import com.sableos.memory.MemoryScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.client.RestClient;

/**
 * Mem0 Platform（{@code api.mem0.ai} v3）后端：把 {@code append/load/recall} 翻译成 Platform REST 调用。
 *
 * <p><b>写入用 {@code infer=false}（同步、原样存储）</b>：{@link LongTermMemoryStore#append} 的语义是「追加一条给定内容」。 若交给
 * Platform 的抽取管线（默认 {@code infer=true}），内容会被改写、合并且异步落库——既违背契约一（写入立刻可读），
 * 也让核心区「一字不少」无法保证。抽取/冲突消解应作为独立的提炼步骤，不藏在 append 里。
 *
 * <p>Platform v3 的两个硬性约定：实体 ID 必须放在 {@code filters} 内（顶层 {@code user_id} 会 400）； 读取/检索响应都带
 * metadata，所以 scope 过滤在客户端完成——{@code recall} 只返回归档区，{@code load} 返回核心区全量 + 归档区前 {@value
 * #MAX_ARCHIVE_ROWS} 条。
 */
public class Mem0MemoryStore implements LongTermMemoryStore {

  /** 写入：{@code infer=false} 时同步返回 results，不返回 event_id。 */
  private static final String ADD_URI = "/v3/memories/add/";

  /** 读取：POST + filters + 分页。 */
  private static final String GET_URI = "/v3/memories/";

  /** 检索：混合检索（语义 + BM25 + 实体匹配）。 */
  private static final String SEARCH_URI = "/v3/memories/search/";

  /** Platform 单页上限。 */
  private static final int PAGE_SIZE = 200;

  /** 分页上限：防止异常大的记忆量把一次 load 拖成几十次请求。 */
  private static final int MAX_PAGES = 10;

  /** 归档区展示上限，对齐 SqliteMemoryStore。 */
  private static final int MAX_ARCHIVE_ROWS = 100;

  /** 检索候选数：多取再按 scope 过滤，避免归档条目被核心条目挤掉。 */
  private static final int SEARCH_TOP_K = 50;

  private static final String SCOPE_KEY = "scope";

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

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("messages", List.of(Map.of("role", "user", "content", content)));
    body.put("user_id", userIdOf(profileName));
    body.put("metadata", Map.of(SCOPE_KEY, scope.name()));
    body.put("infer", false);

    restClient.post().uri(ADD_URI).body(body).retrieve().toBodilessEntity();
  }

  @Override
  public String load(String profileName) {
    Objects.requireNonNull(profileName, "profileName 不能为空");

    List<MemoryItem> all = fetchAll(profileName);
    String core = render(filterByScope(all, MemoryScope.CORE), Integer.MAX_VALUE);
    String archive = render(filterByScope(all, MemoryScope.ARCHIVAL), MAX_ARCHIVE_ROWS);
    return (core + "\n" + archive).strip();
  }

  @Override
  public List<String> recallByKeyword(String keyword, String profileName) {
    Objects.requireNonNull(keyword, "keyword 不能为空");
    Objects.requireNonNull(profileName, "profileName 不能为空");

    SearchEnvelope response =
        restClient
            .post()
            .uri(SEARCH_URI)
            .body(
                Map.of(
                    "query", keyword,
                    "filters", Map.of("user_id", userIdOf(profileName)),
                    "top_k", SEARCH_TOP_K))
            .retrieve()
            .body(SearchEnvelope.class);
    if (response == null || response.results() == null) {
      return List.of();
    }
    // 契约四：Platform 检索会跨 scope，必须在结果侧收一次，只留归档区
    return filterByScope(response.results(), MemoryScope.ARCHIVAL).stream()
        .map(MemoryItem::memory)
        .filter(Objects::nonNull)
        .toList();
  }

  /** 分页拉全量；{@code next} 为空或达 {@link #MAX_PAGES} 即停。 */
  private List<MemoryItem> fetchAll(String profileName) {
    List<MemoryItem> items = new ArrayList<>();
    for (int page = 1; page <= MAX_PAGES; page++) {
      int currentPage = page;
      PageEnvelope envelope =
          restClient
              .post()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(GET_URI)
                          .queryParam("page", currentPage)
                          .queryParam("page_size", PAGE_SIZE)
                          .build())
              .body(Map.of("filters", Map.of("user_id", userIdOf(profileName))))
              .retrieve()
              .body(PageEnvelope.class);
      if (envelope == null || envelope.results() == null || envelope.results().isEmpty()) {
        break;
      }
      items.addAll(envelope.results());
      if (envelope.next() == null || items.size() >= envelope.count()) {
        break;
      }
    }
    return items;
  }

  /** Mem0 侧按 user_id 隔离记忆：整个 SableOS 实例固定前缀 {@code sableos}，Profile 作为后缀。 */
  private String userIdOf(String profileName) {
    return userId + ":" + profileName;
  }

  /** 按写入时 metadata 里的 scope 分拣 core / archival。 */
  private List<MemoryItem> filterByScope(List<MemoryItem> items, MemoryScope scope) {
    String target = scope.name();
    return items.stream().filter(item -> target.equals(scopeOf(item))).toList();
  }

  private static String scopeOf(MemoryItem item) {
    return item.metadata() == null ? "" : item.metadata().path(SCOPE_KEY).asText("");
  }

  private String render(List<MemoryItem> items, int limit) {
    return items.stream()
        .limit(limit)
        .map(MemoryItem::memory)
        .filter(Objects::nonNull)
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  /** Platform 单条记忆；未知字段由 Jackson 忽略。 */
  record MemoryItem(String memory, JsonNode metadata) {}

  /** {@code GET /v3/memories/} 的分页信封。 */
  record PageEnvelope(int count, String next, List<MemoryItem> results) {}

  /** {@code POST /v3/memories/search/} 的检索信封。 */
  record SearchEnvelope(List<MemoryItem> results) {}
}
