package com.sableos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.core.tool.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** McpToolAdapter 的回归测试：三要素直映射 / 参数原样转发 / 错误可重试包装。全程 mock，不碰网。 */
class McpToolAdapterTest {

  private final ObjectMapper json = new ObjectMapper();

  private static McpSchema.Tool fixtureTool(String name) {
    return McpSchema.Tool.builder()
        .name(name)
        .description("MCP 夹具工具")
        .inputSchema(McpJsonDefaults.getMapper(), "{\"type\":\"object\"}")
        .build();
  }

  @Test
  @DisplayName("三要素直接映射自 MCP tool spec（含 schema 序列化）")
  void contractMapsFromMcpToolSpec() {
    McpSyncClient client = mock(McpSyncClient.class);
    McpToolAdapter adapter = new McpToolAdapter(client, fixtureTool("good_mcp_tool"));

    assertEquals("good_mcp_tool", adapter.getName());
    assertEquals("MCP 夹具工具", adapter.getDescription());
    assertTrue(adapter.getInputSchema().contains("object"), adapter.getInputSchema());
  }

  @Test
  @DisplayName("execute 参数原样转发、成功结果包装为 success")
  void executeForwardsArgsAndWrapsResult() throws Exception {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.callTool(any()))
        .thenReturn(McpSchema.CallToolResult.builder().addTextContent("晴").isError(false).build());
    McpToolAdapter adapter = new McpToolAdapter(client, fixtureTool("good_mcp_tool"));
    JsonNode input = json.readTree("{\"city\":\"beijing\",\"n\":2}");

    ToolResult result = adapter.execute(input);

    ArgumentCaptor<McpSchema.CallToolRequest> captor =
        ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(client).callTool(captor.capture());
    assertEquals("good_mcp_tool", captor.getValue().name());
    assertEquals(Map.of("city", "beijing", "n", 2), captor.getValue().arguments(), "参数逐字转发");
    assertTrue(result.success());
    assertTrue(result.content().contains("晴"));
  }

  @Test
  @DisplayName("MCP isError=true_包装为可重试失败")
  void mcpErrorWrapsAsRetryableFailure() throws Exception {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.callTool(any()))
        .thenReturn(
            McpSchema.CallToolResult.builder()
                .addTextContent("server exploded")
                .isError(true)
                .build());
    McpToolAdapter adapter = new McpToolAdapter(client, fixtureTool("good_mcp_tool"));

    ToolResult result = adapter.execute(json.readTree("{}"));

    assertFalse(result.success());
    assertTrue(result.retryable(), "外部故障多为瞬态，标记可重试");
    assertTrue(result.errorMessage().contains("server exploded"), result.errorMessage());
  }
}
