package com.sableos.core.prompt;

import com.sableos.core.context.ContextLoader;
import com.sableos.core.memory.MemoryProvider;
import com.sableos.core.profile.Profile;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.provider.ToolCallRequest;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionMessage;
import com.sableos.core.tool.SableTool;
import com.sableos.core.tool.ToolCatalog;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 本轮上下文的组装器（FR-006 固定顺序）：system prompt（末尾附当前日期时间）→ 长期记忆（无则跳过）→ 会话历史（最近 N 条）→ 当前可用工具列表。
 *
 * <p>输出为第 16 节中立 DTO {@link ProviderRequest}：多轮历史**拍平成一段文本**放进 {@code content} （工具走 {@code
 * availableTools} 的原生 schema），第 16 节 Provider 契约零改动（research D-02）。
 *
 * <p>截断只作用于会话历史（最近 N 条，默认 20，一条消息计一轮），system prompt 与记忆段不参与截断（FR-007）。
 */
public final class PromptBuilder {

  private static final String LINE_BREAK = "\n";
  private static final String SECTION_SEPARATOR = "\n\n";
  private static final String TIME_PREFIX = "当前时间：";
  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final ContextLoader contextLoader;
  private final ToolCatalog toolCatalog;
  private final MemoryProvider memoryProvider;

  /**
   * @param contextLoader 读取 system prompt 的来源加载器
   * @param toolCatalog 本次 Profile 可用工具的目录
   * @param memoryProvider 长期记忆段来源；可为 null（整段省略）
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：ContextLoader 是无状态的只读协作者（final 类，仅持三个 final Path 字段，无任何 mutator），"
              + "此处按依赖注入保存其引用，并非把可变内部表示暴露出去（PromptBuilder 无返回该字段的 getter）。"
              + "依赖注入本就必须持有协作者引用，不引入多余抽象接口即可规避 EI_EXPOSE_REP2 属 SpotBugs 对"
              + "「注入具体类」的固有误判。撤销条件：一旦 ContextLoader 变为可变（新增 setter / 非 final 可变字段）"
              + "或其引用被对外暴露，须重新评估并移除本抑制。")
  public PromptBuilder(
      ContextLoader contextLoader, ToolCatalog toolCatalog, MemoryProvider memoryProvider) {
    this.contextLoader = contextLoader;
    this.toolCatalog = toolCatalog;
    this.memoryProvider = memoryProvider;
  }

  /**
   * 组装本轮上下文。
   *
   * <p>调用前 {@link com.sableos.core.react.ReActLoop} 已把当前用户消息 append 进 {@code session}，故历史尾部即当前用户消息，
   * 本方法不重复追加。
   */
  public ProviderRequest build(Session session, Profile profile) {
    StringBuilder content = new StringBuilder();

    String systemPrompt = contextLoader.systemPrompt(profile);
    content.append(systemPrompt);
    content.append(LINE_BREAK).append(TIME_PREFIX).append(LocalDateTime.now().format(TIME_FORMAT));

    String memory = (memoryProvider == null) ? null : memoryProvider.memorySection(profile);
    if (memory != null && !memory.isBlank()) {
      content.append(SECTION_SEPARATOR).append(memory.strip());
    }

    for (SessionMessage message : tail(session.messages(), profile.settings().maxHistoryTurns())) {
      content.append(SECTION_SEPARATOR).append(render(message));
    }

    List<SableTool> availableTools = toolCatalog.toolsFor(profile);
    return new ProviderRequest(content.toString(), availableTools);
  }

  /** 只保留最近 {@code limit} 条消息（总数不足则全取）；limit<=0 时按 0 处理。 */
  static List<SessionMessage> tail(List<SessionMessage> messages, int limit) {
    int size = messages.size();
    int from = Math.max(0, size - Math.max(0, limit));
    return List.copyOf(messages.subList(from, size));
  }

  static String render(SessionMessage message) {
    switch (message.role()) {
      case USER:
        return "User: " + text(message.content());
      case ASSISTANT:
        return renderAssistant(message);
      case TOOL:
        return "Tool[" + text(message.toolName()) + "]: " + text(message.content());
      default:
        throw new IllegalStateException("未知的消息角色：" + message.role());
    }
  }

  private static String renderAssistant(SessionMessage message) {
    StringBuilder rendered = new StringBuilder("Assistant: ").append(text(message.content()));
    if (!message.toolCalls().isEmpty()) {
      String names =
          message.toolCalls().stream().map(ToolCallRequest::name).collect(Collectors.joining(", "));
      rendered.append(LINE_BREAK).append("(请求调用工具: ").append(names).append(")");
    }
    return rendered.toString();
  }

  private static String text(String value) {
    return (value == null) ? "" : value;
  }
}
