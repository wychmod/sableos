package com.sableos.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SableOS 内部统一的 Tool 抽象（技术方案 §6.1）：内置 Tool、Plugin Tool、MCP Tool 都被包装成本接口， 让上层（ReActLoop /
 * ToolExecutor）不感知 Tool 来源。
 *
 * <p>Provider 层只消费前三者把工具说明翻译给模型（只翻译不执行）；{@link #execute} 由 ToolExecutor 在编排层调用（第 17/20 节），Provider
 * 层绝不调用。
 */
public interface SableTool {

  /** 工具名（模型侧 function calling 引用它）。 */
  String getName();

  /** 工具描述（告诉模型这个工具能做什么、何时用）。 */
  String getDescription();

  /** 入参的 JSON Schema 文本。 */
  String getInputSchema();

  /** 执行工具并返回结果。 */
  ToolResult execute(JsonNode input);
}
