package com.sableos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.core.profile.Profile;
import com.sableos.core.tool.SableTool;
import com.sableos.core.tool.ToolResult;
import com.sableos.tool.mcp.McpToolAdapter;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 课件《第20节》验收 harness：ToolRegistryTest——三种来源统一注册为 SableTool、重名拒绝、按 Profile 过滤不多不少， 以及注解管道的实参转发与异常不吞。
 */
class ToolRegistryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 直接实现来源的替身。 */
  private static SableTool directTool(String name) {
    return new SableTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return name + " 描述";
      }

      @Override
      public String getInputSchema() {
        return "{\"type\":\"object\"}";
      }

      @Override
      public ToolResult execute(JsonNode input) {
        return ToolResult.success("ok");
      }
    };
  }

  /**
   * @Tool 注解来源的替身 bean。
   */
  static class AnnotatedBean {

    @Tool(name = "annotated_tool", description = "注解来源工具")
    public String annotatedTool(@ToolParam(description = "入参") String input) {
      return input;
    }
  }

  /** 回显 bean：记录收到的实参，供「JSON 实参真被转发」断言。 */
  static class EchoBean {

    private String received;

    @Tool(name = "echo_tool", description = "回显工具")
    public String echo(@ToolParam(description = "要回显的文本") String text) {
      this.received = text;
      return "echo:" + text;
    }
  }

  /** 必炸 bean：方法体抛异常，验证管道不吞。 */
  static class ExplodingBean {

    @Tool(name = "exploding_tool", description = "必抛异常的工具")
    public String explode(@ToolParam(description = "入参") String input) {
      throw new IllegalStateException("boom");
    }
  }

  /** MCP 来源的替身：三要素来自夹具 Tool，client 本轮不参与调用。 */
  private static McpToolAdapter mcpTool(String name) {
    McpSyncClient client = mock(McpSyncClient.class);
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name(name)
            .description(name + " mcp")
            .inputSchema(McpJsonDefaults.getMapper(), "{\"type\":\"object\"}")
            .build();
    return new McpToolAdapter(client, tool);
  }

  private static Profile profileWithTools(List<String> tools) {
    return new Profile(
        "lesson20-agent",
        "第20节测试 Agent",
        new Profile.Identity("lesson20-agent", "你是测试助手。"),
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        tools,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(null, null));
  }

  @Test
  @DisplayName("三种来源的工具都以 SableTool 身份注册进来")
  void allThreeSourcesRegisterAsSableTool() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(directTool("notify"));
    registry.registerAnnotated(new AnnotatedBean());
    registry.register(mcpTool("github_search"));

    assertTrue(registry.contains("notify"));
    assertTrue(registry.contains("annotated_tool"));
    assertTrue(registry.contains("github_search"));
    assertEquals(3, registry.all().size());
  }

  @Test
  @DisplayName("重名注册被拒绝并点名")
  void duplicateNameIsRejected() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(directTool("shell"));

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> registry.register(directTool("shell")));

    assertTrue(ex.getMessage().contains("shell"));
    assertEquals(1, registry.all().size(), "拒绝时不得覆盖原有注册");
  }

  @Test
  @DisplayName("按Profile的tools字段过滤_子集恰好等于声明列表")
  void filterByProfileToolsIsExact() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(directTool("read_file"));
    registry.register(directTool("http_get"));
    registry.register(directTool("shell"));

    List<SableTool> filtered =
        registry.toolsFor(profileWithTools(List.of("read_file", "http_get", "not_registered")));

    // 不多（shell 没混进来）不少（两个声明的都在）；未知名跳过不报错；顺序随声明
    assertEquals(
        List.of("read_file", "http_get"), filtered.stream().map(SableTool::getName).toList());
  }

  @Test
  @DisplayName("注解来源工具_JSON实参原样转发到方法调用")
  void annotatedToolForwardsJsonArgumentsToMethod() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    EchoBean bean = new EchoBean();
    registry.registerAnnotated(bean);

    SableTool echo = registry.find("echo_tool");
    assertNotNull(echo, "注解方法经管道注册后可查");
    ToolResult result = echo.execute(MAPPER.readTree("{\"text\":\"你好\"}"));

    assertTrue(result.success());
    // ToolCallback 按 Spring AI 约定返回 JSON 序列化串（字符串带引号），断言按包含关系校验输出流经管道
    assertTrue(result.content().contains("echo:你好"), "返回值包成 success 且内容来自方法输出");
    assertEquals("你好", bean.received, "方法真被调用且实参未被改写");
  }

  @Test
  @DisplayName("注解方法抛异常_execute上抛不吞_交执行层统一收敛")
  void annotatedToolExceptionPropagates() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new ExplodingBean());

    SableTool exploding = registry.find("exploding_tool");
    RuntimeException ex =
        assertThrows(
            RuntimeException.class, () -> exploding.execute(MAPPER.readTree("{\"input\":\"x\"}")));

    Throwable root = ex;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    assertTrue(String.valueOf(root.getMessage()).contains("boom"), "异常本体穿透到调用方");
  }
}
