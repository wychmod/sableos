package com.sableos.tool.mcp;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * 读 {@code .sableos/mcp_servers.yaml}（顶层 {@code servers:} 列表）。
 *
 * <p>文件缺失 / {@code servers} 键缺失 = 零 server，启动照常；结构非法（YAML 解析失败 / {@code servers} 非列表 / 条目非对象）抛
 * {@link IllegalArgumentException} 点名报错——配置写错要立刻知道，不能静默按零 server 处理。
 */
public class McpConfigLoader {

  private final Path configFile;

  public McpConfigLoader(Path configFile) {
    this.configFile = configFile;
  }

  public List<McpServerConfig> load() {
    if (!Files.isRegularFile(configFile)) {
      return List.of();
    }
    Object root;
    try (Reader reader = Files.newBufferedReader(configFile)) {
      root = new Yaml().load(reader);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 MCP 配置失败: " + configFile, e);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("MCP 配置 YAML 解析失败: " + configFile, e);
    }
    if (root == null) {
      return List.of();
    }
    if (!(root instanceof Map<?, ?> rootMap)) {
      throw new IllegalArgumentException("MCP 配置顶层必须是映射（含 servers: 键）: " + configFile);
    }
    Object servers = rootMap.get("servers");
    if (servers == null) {
      return List.of();
    }
    if (!(servers instanceof List<?> list)) {
      throw new IllegalArgumentException("MCP 配置 servers: 必须是列表: " + configFile);
    }
    List<McpServerConfig> configs = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> entry)) {
        throw new IllegalArgumentException("MCP 配置存在非对象条目: " + item);
      }
      configs.add(toConfig(entry));
    }
    return List.copyOf(configs);
  }

  private static McpServerConfig toConfig(Map<?, ?> entry) {
    return new McpServerConfig(
        asString(entry.get("name")),
        asString(entry.get("transport")),
        asString(entry.get("command")),
        asStringMap(entry.get("env")));
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /** env 缺省 → 空 Map；显式写了但非映射 → 点名报错。 */
  private static Map<String, String> asStringMap(Object value) {
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("MCP 配置 env 必须是映射: " + value);
    }
    Map<String, String> env = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      env.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
    }
    return Map.copyOf(env);
  }
}
