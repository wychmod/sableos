package com.sableos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sableos.tool.sandbox.SandboxViolationException;
import com.sableos.tool.web.DuckDuckGoSearchProvider;
import com.sableos.tool.web.SearchProvider;
import com.sableos.tool.web.SearchProvider.SearchResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** WebSearchTools（web_search）的回归测试：渲染 provider 结果；白名单校验先于任何请求。 */
class WebSearchToolsTest {

  @Test
  @DisplayName("web_search_应渲染标题链接摘要")
  void webSearch_rendersProviderResults() {
    SearchProvider fake =
        query ->
            List.of(
                new SearchResult("SableOS", "https://example.com/sableos", "一个可私有部署的 Agent OS"),
                new SearchResult("Agent", "https://example.com/agent", "Agent 运行时内核"));
    WebSearchTools tools = new WebSearchTools(fake);

    String rendered = tools.webSearch("agent os");

    assertTrue(rendered.contains("- SableOS"), rendered);
    assertTrue(rendered.contains("https://example.com/sableos"), rendered);
    assertTrue(rendered.contains("一个可私有部署的 Agent OS"), rendered);
    assertTrue(rendered.contains("- Agent"), rendered);
  }

  @Test
  @DisplayName("web_search_白名单校验先于任何请求（引擎入口治理单点）")
  void webSearch_whitelistCheckedBeforeAnyRequest() {
    RestClient restClient = mock(RestClient.class);
    DuckDuckGoSearchProvider engine =
        new DuckDuckGoSearchProvider(
            action -> {
              throw new SandboxViolationException("域名不在白名单");
            },
            restClient);
    WebSearchTools tools = new WebSearchTools(engine);

    assertThrows(SandboxViolationException.class, () -> tools.webSearch("agent os"));
    verifyNoInteractions(restClient);
  }
}
