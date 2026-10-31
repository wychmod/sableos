package com.sableos.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 确定性 mock provider（27 节）：不连任何真实模型、不需要 key，按脚本驱动一次最小 ReAct。
 *
 * <p>第一轮（上下文里还没有 {@code save_memory} 的工具结果）返回一次 {@code save_memory} 工具调用，把用户消息里的 "记住…" 内容交给真实的
 * {@code ToolExecutor}/{@code MemoryTools} 落盘；第二轮看到 {@link #TOOL_RESULT_MARKER}
 * 后直接返回最终答复。**只有模型是假的**——ReActLoop、ToolExecutor、Memory、Session、审计全部走真实路径。
 *
 * <p>判定只看 prompt 文本（无实例状态），因此可安全并发；也让"同一批服务跑多个会话"时行为确定。
 */
public final class MockChatModel implements ChatModel {

  /** 第一轮请求的工具名（必须与 {@code MemoryTools} 的 {@code @Tool(name=...)} 一致）。 */
  static final String TOOL_NAME = "save_memory";

  /** PromptBuilder 渲染工具结果的前缀；出现即说明第一轮工具已执行完。 */
  static final String TOOL_RESULT_MARKER = "Tool[" + TOOL_NAME + "]:";

  /** 第二轮固定答复。 */
  static final String FINAL_REPLY = "（mock）已记住你提供的信息。";

  /** 找不到用户消息时的兜底事实。 */
  static final String DEFAULT_FACT = "mock 记忆";

  private static final String USER_MARKER = "User: ";
  private static final String TOOL_LINE_PREFIX = "\nTool[";
  private static final String REMEMBER_PREFIX_ZH = "记住：";
  private static final String REMEMBER_PREFIX_ASCII = "记住:";
  private static final String TOOL_CALL_ID = "mock-call-1";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public ChatResponse call(Prompt prompt) {
    String contents = (prompt == null) ? "" : prompt.getContents();
    if (contents != null && contents.contains(TOOL_RESULT_MARKER)) {
      return textResponse(FINAL_REPLY);
    }
    return toolCallResponse(extractFact(contents));
  }

  /** 取最后一条 {@code User:} 行的正文，并剥掉口语化的"记住："前缀；找不到时返回兜底事实。 */
  static String extractFact(String promptContents) {
    if (promptContents == null || promptContents.isBlank()) {
      return DEFAULT_FACT;
    }
    int start = promptContents.lastIndexOf(USER_MARKER);
    if (start < 0) {
      return DEFAULT_FACT;
    }
    String tail = promptContents.substring(start + USER_MARKER.length());
    int toolLine = tail.indexOf(TOOL_LINE_PREFIX);
    if (toolLine >= 0) {
      tail = tail.substring(0, toolLine);
    }
    String fact = stripRememberPrefix(tail.strip());
    return fact.isEmpty() ? DEFAULT_FACT : fact;
  }

  private static String stripRememberPrefix(String text) {
    if (text.startsWith(REMEMBER_PREFIX_ZH)) {
      return text.substring(REMEMBER_PREFIX_ZH.length()).strip();
    }
    if (text.startsWith(REMEMBER_PREFIX_ASCII)) {
      return text.substring(REMEMBER_PREFIX_ASCII.length()).strip();
    }
    return text;
  }

  private static ChatResponse toolCallResponse(String fact) {
    AssistantMessage message =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        TOOL_CALL_ID, "function", TOOL_NAME, argumentsJson(fact))))
            .build();
    return new ChatResponse(List.of(new Generation(message)), metadata());
  }

  private static ChatResponse textResponse(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata());
  }

  /** 给一条恒定的 usage，让 llm_calls 的 token 列在 mock 流程里也有值（不参与课程断言）。 */
  private static ChatResponseMetadata metadata() {
    return ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build();
  }

  private static String argumentsJson(String fact) {
    try {
      return MAPPER.writeValueAsString(Map.of("content", fact, "scope", "archival"));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("mock 工具入参序列化失败", e);
    }
  }
}
