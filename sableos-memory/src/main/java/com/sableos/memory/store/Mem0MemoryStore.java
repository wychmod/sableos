package com.sableos.memory.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.memory.LongTermMemoryStore;
import com.sableos.memory.Mem0Mode;
import com.sableos.memory.MemoryScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.client.RestClient;

/**
 * Mem0 后端：把 {@code append/load/recall} 翻译成 Mem0 REST 调用，支持两种部署形态（{@link Mem0Mode}）。
 *
 * <p><b>Platform（默认）</b>：{@code api.mem0.ai} v3，实体 ID 必须放进 {@code filters}，路径为 {@code
 * /v3/memories/add/}、{@code /v3/memories/}、{@code /v3/memories/search/}，鉴权由装配层注入 {@code
 * Authorization: Token}。
 *
 * <p><b>OSS（自托管 server）</b>：路径无版本前缀——{@code POST /memories}、{@code GET /memories?user_id=}、 {@code
 * POST /search}，鉴权由装配层注入 {@code X-API-Key}（server 关鉴权时可留空）。
 *
 * <p><b>写入统一 {@code infer=false}</b>：{@link LongTermMemoryStore#append} 的语义是「追加一条给定内容」。 交给 Mem0
 * 的抽取管线会改写、合并内容，且 Platform 默认异步落库——既违背契约一（写入立刻可读），也让核心区 「一字不少」无法保证。抽取/冲突消解应作为独立提炼步骤，不藏在 append 里。
 *
 * <p><b>scope 过滤在客户端完成</b>：两种形态的响应都带 metadata，{@code recall} 只留归档区，{@code load} 返回核心区全量 + 归档区前
 * {@value #MAX_ARCHIVE_ROWS} 条。
 */
public class Mem0MemoryStore implements LongTermMemoryStore {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String PLATFORM_ADD_URI = "/v3/memories/add/";
  private static final String PLATFORM_GET_URI = "/v3/memories/";
  private static final String PLATFORM_SEARCH_URI = "/v3/memories/search/";

  private static final String OSS_ADD_URI = "/memories";
  private static final String OSS_GET_URI = "/memories";
  private static final String OSS_SEARCH_URI = "/search";

  /** Platform 单页上限。 */
  private static final int PAGE_SIZE = 200;

  /** Platform 分页上限：防止异常大的记忆量把一次 load 拖成几十次请求。 */
  private static final int MAX_PAGES = 10;

  /** 归档区展示上限，对齐 SqliteMemoryStore。 */
  private static final int MAX_ARCHIVE_ROWS = 100;

  /** 检索候选数：多取再按 scope 过滤，避免归档条目被核心条目挤掉。 */
  private static final int SEARCH_TOP_K = 50;

  private static final String SCOPE_KEY = "scope";

  private final RestClient restClient;
  private final String userId;
  private final Mem0Mode mode;

  public Mem0MemoryStore(RestClient restClient, String userId, Mem0Mode mode) {
    this.restClient = Objects.requireNonNull(restClient, "restClient 不能为空");
    this.userId = Objects.requireNonNull(userId, "userId 不能为空");
    this.mode = Objects.requireNonNull(mode, "mode 不能为空");
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

    restClient.post().uri(addUri()).body(body).retrieve().toBodilessEntity();
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

    JsonNode response =
        restClient
            .post()
            .uri(searchUri())
            .body(searchBody(keyword, profileName))
            .retrieve()
            .body(JsonNode.class);
    // 契约四：检索会跨 scope，必须在结果侧收一次，只留归档区
    return filterByScope(parseItems(response), MemoryScope.ARCHIVAL).stream()
        .map(MemoryItem::memory)
        .filter(Objects::nonNull)
        .toList();
  }

  private String addUri() {
    return mode == Mem0Mode.PLATFORM ? PLATFORM_ADD_URI : OSS_ADD_URI;
  }

  private String searchUri() {
    return mode == Mem0Mode.PLATFORM ? PLATFORM_SEARCH_URI : OSS_SEARCH_URI;
  }

  private Map<String, Object> searchBody(String keyword, String profileName) {
    if (mode == Mem0Mode.PLATFORM) {
      return Map.of(
          "query", keyword,
          "filters", Map.of("user_id", userIdOf(profileName)),
          "top_k", SEARCH_TOP_K);
    }
    // OSS 的实体 ID 在顶层，且不接受 Platform 的 filters/top_k 形状
    return Map.of("query", keyword, "user_id", userIdOf(profileName));
  }

  private List<MemoryItem> fetchAll(String profileName) {
    return mode == Mem0Mode.PLATFORM ? fetchPlatform(profileName) : fetchOss(profileName);
  }

  /** Platform：POST + filters + page/page_size，响应为分页信封。 */
  private List<MemoryItem> fetchPlatform(String profileName) {
    List<MemoryItem> items = new ArrayList<>();
    for (int page = 1; page <= MAX_PAGES; page++) {
      int currentPage = page;
      JsonNode envelope =
          restClient
              .post()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(PLATFORM_GET_URI)
                          .queryParam("page", currentPage)
                          .queryParam("page_size", PAGE_SIZE)
                          .build())
              .body(Map.of("filters", Map.of("user_id", userIdOf(profileName))))
              .retrieve()
              .body(JsonNode.class);
      List<MemoryItem> pageItems = parseItems(envelope);
      if (pageItems.isEmpty()) {
        break;
      }
      items.addAll(pageItems);
      if (envelope.path("next").isNull() || items.size() >= envelope.path("count").asInt(0)) {
        break;
      }
    }
    return items;
  }

  /** OSS：GET + user_id 查询，响应为数组（或带 results 的对象）。 */
  private List<MemoryItem> fetchOss(String profileName) {
    JsonNode response =
        restClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path(OSS_GET_URI)
                        .queryParam("user_id", userIdOf(profileName))
                        .build())
            .retrieve()
            .body(JsonNode.class);
    return parseItems(response);
  }

  /** 兼容三种响应壳：裸数组、{@code {results:[...]}}、{@code {memories:[...]}}。 */
  private static List<MemoryItem> parseItems(JsonNode response) {
    if (response == null || response.isNull()) {
      return List.of();
    }
    JsonNode array = response;
    if (!response.isArray()) {
      array = response.has("results") ? response.path("results") : response.path("memories");
    }
    if (!array.isArray()) {
      return List.of();
    }
    return MAPPER.convertValue(array, new TypeReference<List<MemoryItem>>() {});
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

  /** Mem0 单条记忆；未知字段由 Jackson 忽略。 */
  record MemoryItem(String memory, JsonNode metadata) {}
}
