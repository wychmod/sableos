package com.sableos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.sableos.core.profile.Profile;
import com.sableos.core.provider.LlmCallAuditor;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.provider.Usage;
import com.sableos.core.tool.SableTool;
import com.sableos.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** ProviderService 验收：按故事追加（US1 路由 / US2 审计 / US3 工具翻译）。 关键回归测试断言逻辑逐条对齐课件验收 harness。 */
class ProviderServiceTest {

  private final LlmCallAuditor audit = mock(LlmCallAuditor.class);

  private ProviderService serviceWith(Map<String, ChatModel> models, Set<String> providerNames) {
    List<ProvidersProperties.Provider> providers =
        providerNames.stream()
            .map(
                name ->
                    new ProvidersProperties.Provider(
                        name, "openai", "test-key", "https://example.com"))
            .toList();
    return new SpringAiProviderServiceImpl(
        models, new ProvidersProperties(providers), new ToolSchemaAdapter(), audit);
  }

  private static Profile profileUsing(String providerName) {
    return new Profile(
        "agent-" + providerName,
        null,
        null,
        new Profile.ProviderRef(providerName, providerName + "-model", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null);
  }

  private static ProviderRequest request(String content) {
    return new ProviderRequest(content, List.of());
  }

  private static ChatResponse responseWithText(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private static ChatResponse responseWithText(
      String text, int promptTokens, int completionTokens) {
    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder()
            .usage(new DefaultUsage(promptTokens, completionTokens))
            .build();
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
  }

  @Test
  @DisplayName("按名路由：两个 provider 不串台")
  void routeByName_twoProvidersDoNotCross() {
    ChatModel deepseek = mock(ChatModel.class);
    ChatModel kimi = mock(ChatModel.class);
    when(kimi.call(any(Prompt.class))).thenReturn(responseWithText("kimi 的响应"));
    ProviderService service =
        serviceWith(Map.of("deepseek", deepseek, "kimi", kimi), Set.of("deepseek", "kimi"));

    ProviderResponse response = service.chat("s-1", profileUsing("kimi"), request("你好"));

    verify(kimi, times(1)).call(any(Prompt.class)); // 调的是 kimi
    verify(deepseek, never()).call(any(Prompt.class)); // deepseek 一次都没被碰——"不串台"的直接证据
    assertThat(response.content()).isEqualTo("kimi 的响应");
  }

  @Test
  @DisplayName("未登记 provider：抛点名异常且不发任何调用")
  void unknownProvider_throwsProviderNotFound() {
    ChatModel deepseek = mock(ChatModel.class);
    ProviderService service = serviceWith(Map.of("deepseek", deepseek), Set.of("deepseek"));

    assertThatThrownBy(() -> service.chat("s-1", profileUsing("kimi"), request("你好")))
        .isInstanceOf(ProviderNotFoundException.class)
        .hasMessageContaining("kimi");
    verify(deepseek, never()).call(any(Prompt.class));
  }

  @Test
  @DisplayName("响应文本原样透传")
  void responseContent_isReturnedVerbatim() {
    ChatModel chatModel = mock(ChatModel.class);
    String text = "第一行\n第二行——多行文本原样返回 ✓";
    when(chatModel.call(any(Prompt.class))).thenReturn(responseWithText(text));
    ProviderService service = serviceWith(Map.of("deepseek", chatModel), Set.of("deepseek"));

    ProviderResponse response = service.chat("s-1", profileUsing("deepseek"), request("你好"));

    assertThat(response.content()).isEqualTo(text);
  }

  @Test
  @DisplayName("调用失败：审计必须留下 success=false 的记录且异常继续上抛")
  void failedCall_auditRecordsFailureAndRethrows() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("connect timeout"));
    ProviderService service = serviceWith(Map.of("deepseek", chatModel), Set.of("deepseek"));

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> service.chat("s-1", profileUsing("deepseek"), request("你好"))); // 异常继续上抛

    assertThat(thrown).hasMessageContaining("connect timeout");
    verify(audit)
        .record(
            eq("s-1"),
            eq("deepseek"),
            any(),
            isNull(),
            eq(false),
            contains("timeout"),
            anyLong()); // 但审计先落了：success=false + 原因
  }

  @Test
  @DisplayName("调用成功：审计落 success=true 且 usage 透传")
  void successfulCall_auditsSuccessWithUsage() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class))).thenReturn(responseWithText("ok", 11, 22));
    ProviderService service = serviceWith(Map.of("deepseek", chatModel), Set.of("deepseek"));

    ProviderResponse response = service.chat("s-1", profileUsing("deepseek"), request("你好"));

    assertThat(response.usage()).isEqualTo(new Usage(11, 22, 33));
    ArgumentCaptor<Usage> usageCaptor = ArgumentCaptor.forClass(Usage.class);
    verify(audit)
        .record(
            eq("s-1"),
            eq("deepseek"),
            eq("deepseek-model"),
            usageCaptor.capture(),
            eq(true),
            isNull(),
            anyLong());
    assertThat(usageCaptor.getValue()).isEqualTo(new Usage(11, 22, 33));
  }

  @Test
  @DisplayName("成功路径审计写失败：fail-open 返回业务结果")
  void successAuditWriteFailure_returnsResponseAnyway() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class))).thenReturn(responseWithText("ok", 1, 2));
    ProviderService service = serviceWith(Map.of("deepseek", chatModel), Set.of("deepseek"));
    doThrow(new IllegalStateException("audit down"))
        .when(audit)
        .record(
            anyString(), anyString(), anyString(), any(Usage.class), eq(true), isNull(), anyLong());

    ProviderResponse response = service.chat("s-1", profileUsing("deepseek"), request("你好"));

    assertThat(response.content()).isEqualTo("ok");
    verify(audit)
        .record(
            anyString(), anyString(), anyString(), any(Usage.class), eq(true), isNull(), anyLong());
  }

  @Test
  @DisplayName("带工具 schema 调用：请求里关闭了自动执行")
  void toolSchemaCall_disablesInternalToolExecution() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class))).thenReturn(responseWithText("ok"));
    ProviderService service = serviceWith(Map.of("deepseek", chatModel), Set.of("deepseek"));

    service.chat(
        "s-1", profileUsing("deepseek"), new ProviderRequest("你好", List.of(stubTool("http_get"))));

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(captor.capture());
    ChatOptions captured = captor.getValue().getOptions();
    assertThat(captured).isInstanceOf(ToolCallingChatOptions.class);
    ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) captured;
    assertThat(toolOptions.getInternalToolExecutionEnabled()).isFalse(); // 坑二的回归测试：一旦有人改回自动执行，这里立刻红
    assertThat(toolOptions.getToolCallbacks()).hasSize(1); // 翻译过的 schema 确实带上了
    assertThat(toolOptions.getToolCallbacks().get(0).getToolDefinition().name())
        .isEqualTo("http_get");
    assertThat(captured.getModel()).isEqualTo("deepseek-model");
  }

  @Test
  @DisplayName("模型返回工具请求：原样交回且零执行")
  void modelToolCalls_returnedVerbatimWithoutExecution() {
    ChatModel chatModel = mock(ChatModel.class);
    AssistantMessage message =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        "call-1", "function", "http_get", "{\"url\":\"https://example.com\"}")))
            .build();
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(message))));
    ProviderService service = serviceWith(Map.of("deepseek", chatModel), Set.of("deepseek"));

    ProviderResponse response =
        service.chat(
            "s-1",
            profileUsing("deepseek"),
            new ProviderRequest("你好", List.of(stubTool("http_get"))));

    assertThat(response.toolCalls()).hasSize(1);
    assertThat(response.toolCalls().get(0).id()).isEqualTo("call-1");
    assertThat(response.toolCalls().get(0).name()).isEqualTo("http_get");
    assertThat(response.toolCalls().get(0).argumentsJson())
        .isEqualTo("{\"url\":\"https://example.com\"}");
  }

  private static SableTool stubTool(String name) {
    return new SableTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return "测试工具 " + name;
      }

      @Override
      public String getInputSchema() {
        return "{\"type\":\"object\"}";
      }

      @Override
      public ToolResult execute(JsonNode input) {
        throw new UnsupportedOperationException("测试桩不应被执行");
      }
    };
  }
}
