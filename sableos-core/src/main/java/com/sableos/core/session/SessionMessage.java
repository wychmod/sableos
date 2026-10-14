package com.sableos.core.session;

import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.provider.ToolCallRequest;
import com.sableos.core.tool.ToolResult;
import java.util.List;

/**
 * 会话内一条往来记录（只读）：区分角色与各自携带的附加信息（技术方案 §4）。
 *
 * <p>{@code toolCalls} 仅 {@link MessageRole#ASSISTANT} 消息可非空；{@code toolName}/{@code success} 仅
 * {@link MessageRole#TOOL} 消息携带。构造经静态工厂，保证各角色字段口径一致。
 *
 * @param role 角色
 * @param content 文本（工具结果条目：成功为结果内容、失败为原因）
 * @param toolCalls 模型提出的工具调用请求（非 ASSISTANT 恒为空表）
 * @param toolName 工具名（仅 TOOL 条目）
 * @param success 工具成败（仅 TOOL 条目；其余为 null）
 */
public record SessionMessage(
    MessageRole role,
    String content,
    List<ToolCallRequest> toolCalls,
    String toolName,
    Boolean success) {

  public SessionMessage {
    toolCalls = (toolCalls == null) ? List.of() : List.copyOf(toolCalls);
  }

  /** 用户消息。 */
  public static SessionMessage user(String content) {
    return new SessionMessage(MessageRole.USER, content, List.of(), null, null);
  }

  /** 模型输出（保留其提出的工具调用请求，供后续轮参考）。 */
  public static SessionMessage assistant(ProviderResponse response) {
    return new SessionMessage(
        MessageRole.ASSISTANT, response.content(), response.toolCalls(), null, null);
  }

  /** 工具结果（成功记内容、失败记原因；成败标识随条目保留）。 */
  public static SessionMessage tool(String toolName, ToolResult result) {
    String text = result.success() ? result.content() : result.errorMessage();
    return new SessionMessage(MessageRole.TOOL, text, List.of(), toolName, result.success());
  }
}
