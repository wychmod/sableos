package com.sableos.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.tool.sandbox.ActionType;
import com.sableos.tool.sandbox.Sandbox;
import com.sableos.tool.sandbox.SandboxAction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * {@link SearchProvider} 的核心阶段一档实现：DuckDuckGo Instant Answer（免 key）。
 *
 * <p>治理单点在引擎入口——{@code search()} 第一行对<b>真实请求 URL</b> 过 {@code enforce(HTTP_REQUEST,
 * url)}，通过才发请求、被拦则请求零发生（与 http_get 同口径，D-08）。工具层不持 Sandbox、不用伪目标，避免脏化 24 节域名白名单解析。
 *
 * <p>换 Google/Bing/Tavily 只新增 {@link SearchProvider} 实现并同款在入口 enforce，不改工具与调用方。
 */
public class DuckDuckGoSearchProvider implements SearchProvider {

  private static final String DEFAULT_ENDPOINT = "https://api.duckduckgo.com/";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // Instant Answer JSON 字段名（抽为常量，避免 P3C 魔法常量告警）
  private static final String FIELD_ABSTRACT_TEXT = "AbstractText";
  private static final String FIELD_ABSTRACT_URL = "AbstractURL";
  private static final String FIELD_HEADING = "Heading";
  private static final String FIELD_RELATED_TOPICS = "RelatedTopics";
  private static final String FIELD_TOPICS = "Topics";
  private static final String FIELD_TEXT = "Text";
  private static final String FIELD_FIRST_URL = "FirstURL";

  private final Sandbox sandbox;
  private final RestClient restClient;
  private final String endpoint;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：RestClient 是无状态、线程安全的 HTTP 客户端抽象，构造注入即依赖注入本就必须持有协作者引用，"
              + "并非把可变内部表示暴露出去（本类无返回该字段的 getter，search 只在方法栈内使用）。"
              + "撤销条件：一旦本类新增暴露 restClient 的 getter，或对其做原地可变配置，须重新评估并移除本抑制。")
  public DuckDuckGoSearchProvider(Sandbox sandbox, RestClient restClient) {
    this(sandbox, restClient, DEFAULT_ENDPOINT);
  }

  /** 注入 endpoint（测试指向本地假服务，零出网）。 */
  DuckDuckGoSearchProvider(Sandbox sandbox, RestClient restClient, String endpoint) {
    this.sandbox = sandbox;
    this.restClient = restClient;
    this.endpoint = endpoint;
  }

  @Override
  public List<SearchResult> search(String query) {
    String url =
        endpoint
            + "?q="
            + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8)
            + "&format=json&no_html=1";
    // 治理单点：真实请求 URL 先过白名单，被拦则请求零发生（D-08）
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));
    String body = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
    return parse(body);
  }

  private static List<SearchResult> parse(String body) {
    JsonNode root;
    try {
      root = MAPPER.readTree(body == null ? "{}" : body);
    } catch (IOException e) {
      throw new UncheckedIOException("解析搜索结果失败", e);
    }
    List<SearchResult> results = new ArrayList<>();
    String abstractText = text(root, FIELD_ABSTRACT_TEXT);
    if (!abstractText.isBlank()) {
      String heading = text(root, FIELD_HEADING);
      results.add(
          new SearchResult(
              heading.isBlank() ? "摘要" : heading, text(root, FIELD_ABSTRACT_URL), abstractText));
    }
    for (JsonNode topic : root.path(FIELD_RELATED_TOPICS)) {
      collectTopic(topic, results);
    }
    return results;
  }

  /** RelatedTopics 可能是扁平时事，也可能是分组（内嵌 Topics 数组）——分组递归一层收拢。 */
  private static void collectTopic(JsonNode topic, List<SearchResult> results) {
    if (topic.has(FIELD_TOPICS)) {
      for (JsonNode nested : topic.path(FIELD_TOPICS)) {
        collectTopic(nested, results);
      }
      return;
    }
    String topicText = text(topic, FIELD_TEXT);
    String url = text(topic, FIELD_FIRST_URL);
    if (url.isBlank()) {
      return;
    }
    int dash = topicText.indexOf(" - ");
    String title = dash > 0 ? topicText.substring(0, dash) : topicText;
    results.add(new SearchResult(title, url, topicText));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() ? value.asText() : "";
  }
}
