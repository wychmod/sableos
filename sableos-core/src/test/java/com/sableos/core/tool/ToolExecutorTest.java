package com.sableos.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileContext;
import com.sableos.core.provider.ToolCallRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 工具执行验收（FR-008/FR-009/FR-010）：按名解析 → 解析入参 → 执行 → 成败两态写审计；任何路径都返回结果、绝不向上抛。
 *
 * <p>白名单校验位（24 节）与 Sandbox 规则不在本节，只留调用位。
 */
@ExtendWith(MockitoExtension.class)
class ToolExecutorTest {

  private static final String SESSION_ID = "s-1";

  @Mock ToolCatalog toolCatalog;

  @Mock ToolInvocationAuditor auditor;

  private ToolExecutor executor() {
    return new ToolExecutor(toolCatalog, auditor);
  }

  @Test
  @DisplayName("执行期间置入当前 Agent 上下文，执行后清除（per-agent 记忆的前提）")
  void execute_exposesAgentContextDuringCall() {
    java.util.concurrent.atomic.AtomicReference<String> seen =
        new java.util.concurrent.atomic.AtomicReference<>();
    SableTool probe =
        new SableTool() {
          @Override
          public String getName() {
            return "probe";
          }

          @Override
          public String getDescription() {
            return "读取当前 Agent 上下文";
          }

          @Override
          public String getInputSchema() {
            return "{\"type\":\"object\"}";
          }

          @Override
          public ToolResult execute(com.fasterxml.jackson.databind.JsonNode input) {
            seen.set(com.sableos.core.agent.ToolExecutionContext.agentName());
            return ToolResult.success("ok");
          }
        };
    when(toolCatalog.find("probe")).thenReturn(probe);

    Profile profile =
        new Profile(
            "weather-daily",
            null,
            null,
            new Profile.ProviderRef("mock", "mock-model", null),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            new Profile.Settings(null, null));
    ProfileContext.set(profile);
    try {
      executor().execute(SESSION_ID, new ToolCallRequest("c-1", "probe", "{}"));
    } finally {
      ProfileContext.clear();
    }

    assertThat(seen.get()).isEqualTo("weather-daily");
    // 执行完毕即清除：线程复用时不会串到下一个 Agent
    assertThat(com.sableos.core.agent.ToolExecutionContext.agentName()).isNull();
  }

  @Test
  @DisplayName("工具成功写审计 success=true（结果内容落 resultJson）")
  void execute_success_auditsSuccessTrue() {
    StubTool tool = new StubTool("http_get", ToolResult.success("{\"temp\":20}"), null);
    when(toolCatalog.find("http_get")).thenReturn(tool);

    ToolResult result =
        executor().execute(SESSION_ID, new ToolCallRequest("c-1", "http_get", "{\"city\":\"bj\"}"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("{\"temp\":20}");
    verify(auditor)
        .record(
            eq(SESSION_ID),
            eq("http_get"),
            eq("{\"city\":\"bj\"}"),
            eq("{\"temp\":20}"),
            eq(true),
            isNull(),
            anyLong());
  }

  @Test
  @DisplayName("工具失败写审计 success=false 带原因且异常不吞")
  void execute_toolThrows_auditsFailureWithReason() {
    StubTool tool = new StubTool("http_get", null, new IllegalStateException("boom"));
    when(toolCatalog.find("http_get")).thenReturn(tool);

    ToolResult result =
        executor().execute(SESSION_ID, new ToolCallRequest("c-1", "http_get", "{}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("boom");
    verify(auditor)
        .record(
            eq(SESSION_ID),
            eq("http_get"),
            eq("{}"),
            isNull(),
            eq(false),
            contains("boom"),
            anyLong());
  }

  @Test
  @DisplayName("未注册工具名按失败处理、不抛异常（FR-008）")
  void execute_unknownToolName_failsWithoutThrowing() {
    when(toolCatalog.find("nope")).thenReturn(null);

    ToolResult result = executor().execute(SESSION_ID, new ToolCallRequest("c-1", "nope", "{}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("nope");
    verify(auditor)
        .record(
            eq(SESSION_ID), eq("nope"), eq("{}"), isNull(), eq(false), contains("nope"), anyLong());
  }

  @Test
  @DisplayName("入参非法 JSON 返回可重试失败并落审计")
  void execute_invalidJson_returnsRetryableFailure() {
    when(toolCatalog.find("http_get"))
        .thenReturn(new StubTool("http_get", ToolResult.success("unused"), null));

    ToolResult result =
        executor().execute(SESSION_ID, new ToolCallRequest("c-1", "http_get", "{not-json"));

    assertThat(result.success()).isFalse();
    assertThat(result.retryable()).isTrue();
    verify(auditor)
        .record(
            eq(SESSION_ID),
            eq("http_get"),
            eq("{not-json"),
            isNull(),
            eq(false),
            contains("解析失败"),
            anyLong());
  }

  @Test
  @DisplayName("入参为空视为空对象，不算解析失败")
  void execute_blankArguments_treatedAsEmptyObject() {
    StubTool tool = new StubTool("http_get", ToolResult.success("ok"), null);
    when(toolCatalog.find("http_get")).thenReturn(tool);

    ToolResult result =
        executor().execute(SESSION_ID, new ToolCallRequest("c-1", "http_get", "  "));

    assertThat(result.success()).isTrue();
    assertThat(tool.lastInput()).isNotNull();
    assertThat(tool.lastInput().isObject()).isTrue();
    assertThat(tool.lastInput().isEmpty()).isTrue();
  }

  @Test
  @DisplayName("合法 JSON 解析后传给工具")
  void execute_validJson_passesParsedInputToTool() {
    StubTool tool = new StubTool("http_get", ToolResult.success("ok"), null);
    when(toolCatalog.find("http_get")).thenReturn(tool);

    ToolResult result =
        executor().execute(SESSION_ID, new ToolCallRequest("c-1", "http_get", "{\"city\":\"bj\"}"));

    assertThat(result.success()).isTrue();
    assertThat(tool.lastInput().get("city").asText()).isEqualTo("bj");
  }

  /** 可编程测试替身：记录最后一次入参，按需返回结果或抛异常。 */
  private static final class StubTool implements SableTool {

    private final String name;
    private final ToolResult result;
    private final RuntimeException failure;
    private JsonNode lastInput;

    private StubTool(String name, ToolResult result, RuntimeException failure) {
      this.name = name;
      this.result = result;
      this.failure = failure;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getDescription() {
      return "测试工具";
    }

    @Override
    public String getInputSchema() {
      return "{\"type\":\"object\"}";
    }

    @Override
    public ToolResult execute(JsonNode input) {
      this.lastInput = input;
      if (failure != null) {
        throw failure;
      }
      return result;
    }

    private JsonNode lastInput() {
      return lastInput;
    }
  }
}
