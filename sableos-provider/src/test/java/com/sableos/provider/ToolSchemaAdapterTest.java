package com.sableos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sableos.core.tool.SableTool;
import com.sableos.core.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 工具 schema 翻译验收：字段一一对齐且保真，产物不含任何执行逻辑（call 恒抛）。 */
class ToolSchemaAdapterTest {

  private static final String SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}";

  private final ToolSchemaAdapter adapter = new ToolSchemaAdapter();

  private static SableTool tool(String name, String description, String schema) {
    return new SableTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return description;
      }

      @Override
      public String getInputSchema() {
        return schema;
      }

      @Override
      public ToolResult execute(JsonNode input) {
        throw new UnsupportedOperationException("测试桩不应被执行");
      }
    };
  }

  @Test
  @DisplayName("翻译_schema字段一一对齐且保真")
  void toSpringAiTools_mapsFieldsOneToOne() {
    List<ToolCallback> callbacks =
        adapter.toSpringAiTools(List.of(tool("http_get", "发起 HTTP GET 请求", SCHEMA)));

    assertThat(callbacks).hasSize(1);
    ToolDefinition definition = callbacks.get(0).getToolDefinition();
    assertThat(definition.name()).isEqualTo("http_get");
    assertThat(definition.description()).isEqualTo("发起 HTTP GET 请求");
    assertThat(definition.inputSchema()).isEqualTo(SCHEMA);
  }

  @Test
  @DisplayName("翻译_多工具保序不去重不改名")
  void toSpringAiTools_keepsOrderAndDuplicates() {
    List<ToolCallback> callbacks =
        adapter.toSpringAiTools(
            List.of(
                tool("alpha", "第一个", SCHEMA),
                tool("beta", "第二个", SCHEMA),
                tool("alpha", "同名再来一个", SCHEMA)));

    assertThat(callbacks).hasSize(3);
    assertThat(callbacks.get(0).getToolDefinition().name()).isEqualTo("alpha");
    assertThat(callbacks.get(1).getToolDefinition().name()).isEqualTo("beta");
    assertThat(callbacks.get(2).getToolDefinition().name()).isEqualTo("alpha");
  }

  @Test
  @DisplayName("翻译_call恒抛_产物不含执行逻辑")
  void toSpringAiTools_callAlwaysThrows() {
    ToolCallback callback =
        adapter.toSpringAiTools(List.of(tool("http_get", "发起 HTTP GET 请求", SCHEMA))).get(0);

    assertThatThrownBy(() -> callback.call("{\"url\":\"https://example.com\"}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("只翻译")
        .hasMessageContaining("不执行");
  }

  @Test
  @DisplayName("翻译_空入参返回空表")
  void toSpringAiTools_emptyInput_returnsEmptyList() {
    assertThat(adapter.toSpringAiTools(List.of())).isEmpty();
    assertThat(adapter.toSpringAiTools(null)).isEmpty();
  }
}
