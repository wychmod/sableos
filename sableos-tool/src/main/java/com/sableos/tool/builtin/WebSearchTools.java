package com.sableos.tool.builtin;

import com.sableos.tool.web.SearchProvider;
import com.sableos.tool.web.SearchProvider.SearchResult;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置搜索工具：web_search——只把 {@link SearchProvider} 的结果渲染成"标题 / 链接 / 摘要"文本。
 *
 * <p>治理不在工具层（工具无 Sandbox、零伪目标）：HTTP_REQUEST 白名单校验落在引擎入口（{@code DuckDuckGoSearchProvider.search()}
 * 第一行），被拦则请求零发生（D-08）。换引擎只换 {@link SearchProvider} 实现，本工具不动。
 */
public class WebSearchTools {

  private static final String EMPTY_NOTE = "（未搜到相关结果）";

  private final SearchProvider provider;

  public WebSearchTools(SearchProvider provider) {
    this.provider = provider;
  }

  @Tool(name = "web_search", description = "用搜索引擎检索网页，返回标题、链接和摘要列表")
  public String webSearch(@ToolParam(description = "搜索关键词") String query) {
    List<SearchResult> results = provider.search(query);
    if (results == null || results.isEmpty()) {
      return EMPTY_NOTE;
    }
    StringBuilder sb = new StringBuilder();
    for (SearchResult result : results) {
      if (!sb.isEmpty()) {
        sb.append('\n');
      }
      sb.append("- ")
          .append(result.title())
          .append('\n')
          .append("  ")
          .append(result.url())
          .append('\n')
          .append("  ")
          .append(result.snippet());
    }
    return sb.toString();
  }
}
