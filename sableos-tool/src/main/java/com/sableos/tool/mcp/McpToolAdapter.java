package com.sableos.tool.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.core.tool.SableTool;
import com.sableos.core.tool.ToolResult;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把一个 MCP 工具适配成 {@link SableTool}：注册时三要素直接映射 tools/list 返回， 执行时经 MCP 协议原样转发、结果包成 ToolResult（技术方案
 * §6.4）。
 *
 * <p>调用失败标记可重试——网络抖动类失败值得 ReAct 循环再试一次。
 */
public class McpToolAdapter implements SableTool {

  /** 执行侧的 JsonNode→Map 转换用本地 Jackson（入参本就是 Jackson 树），与 SDK mapper 无关。 */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final McpSyncClient client;
  private final McpSchema.Tool tool;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：McpSyncClient 是 MCP SDK 提供的连接句柄接口，构造注入即依赖注入本就必须持有协作者引用，"
              + "并非把可变内部表示暴露出去（本类无返回该字段的 getter，execute 只在方法栈内使用）；"
              + "连接生命周期由 McpClientService 统一管理，适配器不拥有也不变更它。"
              + "撤销条件：一旦本类新增暴露 client 的 getter，或对其做原地可变配置，须重新评估并移除本抑制。")
  public McpToolAdapter(McpSyncClient client, McpSchema.Tool tool) {
    this.client = client;
    this.tool = tool;
  }

  @Override
  public String getName() {
    return tool.name();
  }

  @Override
  public String getDescription() {
    return tool.description();
  }

  @Override
  public String getInputSchema() {
    try {
      return McpJsonDefaults.getMapper().writeValueAsString(tool.inputSchema());
    } catch (IOException e) {
      throw new IllegalStateException("MCP 工具 inputSchema 序列化失败: " + tool.name(), e);
    }
  }

  @Override
  public ToolResult execute(JsonNode input) {
    Map<String, Object> args =
        input == null
            ? Map.of()
            : MAPPER.convertValue(input, new TypeReference<Map<String, Object>>() {});
    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest(tool.name(), args));
    String content = renderContent(result);
    if (Boolean.TRUE.equals(result.isError())) {
      return ToolResult.failure("MCP 调用失败: " + content, true);
    }
    return ToolResult.success(content);
  }

  private static String renderContent(McpSchema.CallToolResult result) {
    if (result.content() == null) {
      return "";
    }
    return result.content().stream()
        .map(c -> c instanceof McpSchema.TextContent text ? text.text() : String.valueOf(c))
        .collect(Collectors.joining("\n"));
  }
}
