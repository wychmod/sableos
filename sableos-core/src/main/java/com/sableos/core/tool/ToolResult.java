package com.sableos.core.tool;

/**
 * 工具执行结果（技术方案 §6.1）：成功标识、结果内容、错误信息、是否可重试。
 *
 * @param success 是否成功
 * @param content 结果内容（成功时）
 * @param errorMessage 错误信息（失败时）
 * @param retryable 失败是否可重试
 */
public record ToolResult(boolean success, String content, String errorMessage, boolean retryable) {

  /** 成功结果。 */
  public static ToolResult success(String content) {
    return new ToolResult(true, content, null, false);
  }

  /** 失败结果。 */
  public static ToolResult failure(String errorMessage, boolean retryable) {
    return new ToolResult(false, null, errorMessage, retryable);
  }
}
