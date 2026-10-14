package com.sableos.provider;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.sableos.core.profile.Profile;
import com.sableos.core.provider.LlmCallAuditor;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.provider.ToolCallRequest;
import com.sableos.core.provider.Usage;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * ProviderService 的 Spring AI 实现：显式路由 → 工具翻译（只翻译不执行）→ 单次调用 → 审计（成败两态）→ 原样返回。
 *
 * <p>审计编排：失败路径先落 {@code success=false} 再抛原异常（审计写失败不遮蔽原异常，addSuppressed）； 成功路径
 * fail-open（审计写失败仅记日志，不改变业务结果）。
 */
public class SpringAiProviderServiceImpl implements ProviderService {

  private static final Logger LOG = LoggerFactory.getLogger(SpringAiProviderServiceImpl.class);

  private final Map<String, ChatModel> models;
  private final ProvidersProperties properties;
  private final ToolSchemaAdapter toolSchemaAdapter;
  private final LlmCallAuditor auditor;

  public SpringAiProviderServiceImpl(
      Map<String, ChatModel> models,
      ProvidersProperties properties,
      ToolSchemaAdapter toolSchemaAdapter,
      LlmCallAuditor auditor) {
    this.models = Map.copyOf(models);
    this.properties = properties;
    this.toolSchemaAdapter = toolSchemaAdapter;
    this.auditor = auditor;
  }

  @Override
  public ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request) {
    Profile.ProviderRef ref = profile.provider();
    ChatModel model = models.get(ref.name());
    if (model == null) {
      throw new ProviderNotFoundException(ref.name());
    }

    long startedAtNanos = System.nanoTime();
    List<ToolCallback> tools = toolSchemaAdapter.toSpringAiTools(request.availableTools());
    ChatOptions options = buildOptions(ref, tools);

    ChatResponse response;
    try {
      response = model.call(new Prompt(List.of(new UserMessage(request.content())), options));
    } catch (RuntimeException callError) {
      long durationMs = elapsedMs(startedAtNanos);
      try {
        auditor.record(
            sessionId, ref.name(), ref.model(), null, false, callError.getMessage(), durationMs);
      } catch (RuntimeException auditError) {
        LOG.error(
            "失败路径审计写入失败：sessionId={} error={}", safe(sessionId), safe(auditError.getMessage()));
        callError.addSuppressed(auditError);
      }
      throw callError;
    }

    long durationMs = elapsedMs(startedAtNanos);
    Usage usage = mapUsage(response);
    try {
      auditor.record(sessionId, ref.name(), ref.model(), usage, true, null, durationMs);
    } catch (RuntimeException auditError) {
      LOG.error(
          "成功路径审计写入失败（fail-open，不影响业务结果）：sessionId={} error={}",
          safe(sessionId),
          safe(auditError.getMessage()));
    }
    return mapResponse(response, usage);
  }

  private ChatOptions buildOptions(Profile.ProviderRef ref, List<ToolCallback> tools) {
    if (ProvidersProperties.TYPE_DASHSCOPE.equals(providerType(ref.name()))) {
      DashScopeChatOptions.DashScopeChatOptionsBuilder builder =
          DashScopeChatOptions.builder()
              .withModel(ref.model())
              .withInternalToolExecutionEnabled(false);
      if (ref.temperature() != null) {
        builder.withTemperature(ref.temperature());
      }
      if (!tools.isEmpty()) {
        builder.withToolCallbacks(tools);
      }
      return builder.build();
    }
    OpenAiChatOptions.Builder builder =
        OpenAiChatOptions.builder().model(ref.model()).internalToolExecutionEnabled(false);
    if (ref.temperature() != null) {
      builder.temperature(ref.temperature());
    }
    if (!tools.isEmpty()) {
      builder.toolCallbacks(tools);
    }
    return builder.build();
  }

  private String providerType(String providerName) {
    for (ProvidersProperties.Provider provider : properties.providers()) {
      if (provider.name().equals(providerName)) {
        return provider.effectiveType();
      }
    }
    return ProvidersProperties.TYPE_OPENAI;
  }

  private static Usage mapUsage(ChatResponse response) {
    // getMetadata() 在 Spring AI 侧标注非空（SpotBugs 亦按该契约判定）；usage 可能缺席，由调用方判 null
    org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
    if (usage == null) {
      return null;
    }
    return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
  }

  private static ProviderResponse mapResponse(ChatResponse response, Usage usage) {
    AssistantMessage message = response.getResult().getOutput();
    List<ToolCallRequest> toolCalls =
        message.getToolCalls().stream()
            .map(
                toolCall ->
                    new ToolCallRequest(toolCall.id(), toolCall.name(), toolCall.arguments()))
            .toList();
    return new ProviderResponse(message.getText(), toolCalls, usage);
  }

  private static long elapsedMs(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L;
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法）。 */
  private static String safe(String value) {
    return (value == null) ? null : value.replaceAll("[\r\n]", "_");
  }
}
