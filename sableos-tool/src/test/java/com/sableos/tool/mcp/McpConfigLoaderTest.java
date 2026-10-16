package com.sableos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** McpConfigLoader 的回归测试：缺失照常 / 逐字段解析 / 结构非法点名。 */
class McpConfigLoaderTest {

  @TempDir Path dir;

  @Test
  @DisplayName("配置缺失文件_零 server 不报错")
  void missingFileYieldsZeroServers() {
    McpConfigLoader loader = new McpConfigLoader(dir.resolve("absent.yaml"));

    assertEquals(List.of(), loader.load());
  }

  @Test
  @DisplayName("servers 键缺失_零 server 不报错")
  void missingServersKeyYieldsZeroServers() throws IOException {
    Path file = dir.resolve("mcp_servers.yaml");
    Files.writeString(file, "other_key: 1\n");

    assertEquals(List.of(), new McpConfigLoader(file).load());
  }

  @Test
  @DisplayName("合法条目_逐字段解析（env 缺省为空 Map）")
  void parsesAllFieldsPerEntry() throws IOException {
    Path file = dir.resolve("mcp_servers.yaml");
    Files.writeString(
        file,
        """
        servers:
          - name: filesystem
            transport: stdio
            command: npx -y @modelcontextprotocol/server-filesystem /tmp
            env:
              SOME_TOKEN: abc
          - name: minimal
            transport: stdio
            command: python server.py
        """);

    List<McpServerConfig> configs = new McpConfigLoader(file).load();

    assertEquals(2, configs.size());
    McpServerConfig first = configs.get(0);
    assertEquals("filesystem", first.name());
    assertEquals("stdio", first.transport());
    assertEquals("npx -y @modelcontextprotocol/server-filesystem /tmp", first.command());
    assertEquals(Map.of("SOME_TOKEN", "abc"), first.env());
    McpServerConfig second = configs.get(1);
    assertEquals("minimal", second.name());
    assertEquals(Map.of(), second.env(), "env 缺省应为空 Map");
  }

  @Test
  @DisplayName("结构非法_点名报错（servers 非列表 / 条目非对象 / YAML 坏）")
  void malformedFileFailsLoud() throws IOException {
    Path nonList = dir.resolve("non-list.yaml");
    Files.writeString(nonList, "servers: not-a-list\n");
    IllegalArgumentException e1 =
        assertThrows(IllegalArgumentException.class, () -> new McpConfigLoader(nonList).load());
    assertTrue(e1.getMessage().contains("servers"), e1.getMessage());

    Path badEntry = dir.resolve("bad-entry.yaml");
    Files.writeString(badEntry, "servers:\n  - just-a-string\n");
    IllegalArgumentException e2 =
        assertThrows(IllegalArgumentException.class, () -> new McpConfigLoader(badEntry).load());
    assertTrue(e2.getMessage().contains("just-a-string"), e2.getMessage());

    Path brokenYaml = dir.resolve("broken.yaml");
    Files.writeString(brokenYaml, "servers: [unclosed\n");
    assertThrows(IllegalArgumentException.class, () -> new McpConfigLoader(brokenYaml).load());
  }
}
