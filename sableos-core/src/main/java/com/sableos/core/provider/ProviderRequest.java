package com.sableos.core.provider;

import com.sableos.core.tool.SableTool;
import java.util.List;

/**
 * Provider 调用的输入（中立 DTO，不含任何 Spring AI 类型）：用户输入内容 + 本次可用工具说明。
 *
 * @param content 用户输入内容
 * @param availableTools 本次可用工具；空列表 = 无可用工具（恒非 null）
 */
public record ProviderRequest(String content, List<SableTool> availableTools) {

  public ProviderRequest {
    availableTools = (availableTools == null) ? List.of() : List.copyOf(availableTools);
  }
}
