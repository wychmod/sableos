package com.sableos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sableos.tool.ToolRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** McpClientService 的回归测试：失联不拖垮启动（课件骨架断言逐条对齐）。全程 mock，不真起子进程。 */
class McpClientServiceTest {

  @TempDir Path dir;

  @Test
  @DisplayName("好 server 注册、坏 server 只 WARN 不拖垮（connectAll 不抛）")
  void goodServerRegisters_badServerOnlyWarns() throws IOException {
    Path file = dir.resolve("mcp_servers.yaml");
    Files.writeString(
        file,
        """
        servers:
          - name: good
            transport: stdio
            command: good-server
          - name: bad
            transport: stdio
            command: bad-server
        """);
    McpConfigLoader loader = new McpConfigLoader(file);

    McpSyncClient good = mock(McpSyncClient.class);
    McpSchema.Tool goodTool =
        McpSchema.Tool.builder()
            .name("good_mcp_tool")
            .description("好工具")
            .inputSchema(McpJsonDefaults.getMapper(), "{\"type\":\"object\"}")
            .build();
    when(good.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(goodTool), null));
    McpSyncClient bad = mock(McpSyncClient.class);
    doThrow(new RuntimeException("refused")).when(bad).initialize();

    McpClientService service =
        new McpClientService(loader, config -> "good".equals(config.name()) ? good : bad);
    ToolRegistry registry = new ToolRegistry();

    assertDoesNotThrow(() -> service.connectAll(registry));

    assertTrue(registry.contains("good_mcp_tool"), "好 server 的工具必须注册");
    assertFalse(registry.contains("bad_mcp_tool"), "坏 server 的工具不得注册");
  }
}
