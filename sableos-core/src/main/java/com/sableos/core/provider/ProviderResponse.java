package com.sableos.core.provider;

import java.util.List;

/**
 * Provider 调用的输出（中立 DTO）：模型文本与/或「想调工具」的请求，原样交回上层。
 *
 * @param content 模型文本，可为 null/空
 * @param toolCalls 模型返回的工具调用请求；恒非 null（可能为空表），本层不解释不执行
 * @param usage token 用量，供应商未返回时为 null
 */
public record ProviderResponse(String content, List<ToolCallRequest> toolCalls, Usage usage) {

  public ProviderResponse {
    toolCalls = (toolCalls == null) ? List.of() : List.copyOf(toolCalls);
  }
}
