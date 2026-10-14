package com.sableos.core.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sableos.core.profile.Profile;
import com.sableos.core.prompt.PromptBuilder;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.provider.ToolCallRequest;
import com.sableos.core.session.MessageRole;
import com.sableos.core.session.Session;
import com.sableos.core.tool.ToolExecutor;
import com.sableos.core.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 自实现 ReAct 循环验收：无工具调用收尾、工具结果回填累积、一轮多工具顺序执行、转满最大轮数强制收尾（默认 10 / Profile 覆盖）。 */
@ExtendWith(MockitoExtension.class)
class ReActLoopTest {

  private static final String SESSION_ID = "s-1";
  private static final ProviderRequest ASSEMBLED = new ProviderRequest("assembled", List.of());

  @Mock PromptBuilder promptBuilder;

  @Mock ToolExecutor toolExecutor;

  @BeforeEach
  void stubPromptBuilder() {
    when(promptBuilder.build(any(), any())).thenReturn(ASSEMBLED);
  }

  private static Profile profile(Integer maxIterations, Integer maxHistoryTurns) {
    return new Profile(
        "ops-agent",
        "测试 Agent",
        new Profile.Identity("ops-agent", "你是运维助手。"),
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(maxIterations, maxHistoryTurns));
  }

  private static Session session() {
    return new Session(SESSION_ID, "ops-agent", "cli", "u-1");
  }

  private static ProviderResponse reply(String content) {
    return new ProviderResponse(content, List.of(), null);
  }

  private static ProviderResponse wantsTools(ToolCallRequest... calls) {
    return new ProviderResponse(null, List.of(calls), null);
  }

  private ReActLoop loop(ProviderService provider) {
    return new ReActLoop(provider, promptBuilder, toolExecutor);
  }

  @Test
  @DisplayName("无工具调用即返回该轮文本收尾")
  void run_modelStopsRequestingTools_returnsFinalText() {
    ScriptedProvider provider = new ScriptedProvider(reply("最终答复"));
    Session session = session();

    String result = loop(provider).run(session, "你好", profile(null, null));

    assertThat(result).isEqualTo("最终答复");
    assertThat(provider.calls()).isEqualTo(1);
    verifyNoInteractions(toolExecutor);
    assertThat(session.messages()).hasSize(2);
    assertThat(session.messages().get(0).role()).isEqualTo(MessageRole.USER);
    assertThat(session.messages().get(1).role()).isEqualTo(MessageRole.ASSISTANT);
  }

  @Test
  @DisplayName("每轮响应与工具结果都累积进会话")
  void run_toolResultsAccumulate_intoSessionHistory() {
    ToolCallRequest call = new ToolCallRequest("c-1", "http_get", "{}");
    ScriptedProvider provider = new ScriptedProvider(wantsTools(call), reply("最终答复"));
    when(toolExecutor.execute(eq(SESSION_ID), any())).thenReturn(ToolResult.success("晴 25 度"));
    Session session = session();

    String result = loop(provider).run(session, "今天穿什么", profile(null, null));

    assertThat(result).isEqualTo("最终答复");
    assertThat(session.messages()).hasSize(4);
    assertThat(session.messages().get(0).role()).isEqualTo(MessageRole.USER);
    assertThat(session.messages().get(1).role()).isEqualTo(MessageRole.ASSISTANT);
    assertThat(session.messages().get(1).toolCalls()).containsExactly(call);
    assertThat(session.messages().get(2).role()).isEqualTo(MessageRole.TOOL);
    assertThat(session.messages().get(2).toolName()).isEqualTo("http_get");
    assertThat(session.messages().get(2).content()).isEqualTo("晴 25 度");
    assertThat(session.messages().get(3).role()).isEqualTo(MessageRole.ASSISTANT);
  }

  @Test
  @DisplayName("一轮多工具按顺序逐个执行")
  void run_oneRoundMultipleTools_executesSequentially() {
    ToolCallRequest first = new ToolCallRequest("c-1", "http_get", "{}");
    ToolCallRequest second = new ToolCallRequest("c-2", "db_query", "{}");
    ScriptedProvider provider = new ScriptedProvider(wantsTools(first, second), reply("done"));
    when(toolExecutor.execute(eq(SESSION_ID), any())).thenReturn(ToolResult.success("ok"));

    loop(provider).run(session(), "q", profile(null, null));

    InOrder inOrder = inOrder(toolExecutor);
    inOrder.verify(toolExecutor).execute(SESSION_ID, first);
    inOrder.verify(toolExecutor).execute(SESSION_ID, second);
  }

  @Test
  @DisplayName("模型每轮都要调工具时恰好转满最大轮数强制停并返回终止结果")
  void run_modelAlwaysRequestsTools_stopsExactlyAtMaxIterations() {
    ScriptedProvider provider =
        new ScriptedProvider(wantsTools(new ToolCallRequest("c-1", "http_get", "{}")));
    when(toolExecutor.execute(eq(SESSION_ID), any())).thenReturn(ToolResult.success("ok"));
    Session session = session();

    String result = loop(provider).run(session, "q", profile(4, null));

    assertThat(provider.calls()).isEqualTo(4);
    assertThat(result).isEqualTo(ReActLoop.MAX_ITERATIONS_REPLY);
  }

  @Test
  @DisplayName("Profile 覆盖迭代上限时按声明值转满")
  void run_profileOverridesMaxIterations_usesDeclaredValue() {
    ScriptedProvider provider =
        new ScriptedProvider(wantsTools(new ToolCallRequest("c-1", "http_get", "{}")));
    when(toolExecutor.execute(eq(SESSION_ID), any())).thenReturn(ToolResult.success("ok"));

    String result = loop(provider).run(session(), "q", profile(3, null));

    assertThat(provider.calls()).isEqualTo(3);
    assertThat(result).isEqualTo(ReActLoop.MAX_ITERATIONS_REPLY);
  }

  @Test
  @DisplayName("未覆盖时默认上限为 10")
  void run_noOverride_usesDefaultMaxIterationsTen() {
    ScriptedProvider provider =
        new ScriptedProvider(wantsTools(new ToolCallRequest("c-1", "http_get", "{}")));
    when(toolExecutor.execute(eq(SESSION_ID), any())).thenReturn(ToolResult.success("ok"));

    String result = loop(provider).run(session(), "q", profile(null, null));

    assertThat(provider.calls()).isEqualTo(10);
    assertThat(result).isEqualTo(ReActLoop.MAX_ITERATIONS_REPLY);
  }

  /** 按脚本依次返回响应的 Provider 替身；超出脚本后重复最后一条（配合「每轮都要求调工具」）。 */
  private static final class ScriptedProvider implements ProviderService {

    private final List<ProviderResponse> script;
    private int calls;

    private ScriptedProvider(ProviderResponse... responses) {
      this.script = List.of(responses);
    }

    @Override
    public ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request) {
      ProviderResponse response = script.get(Math.min(calls, script.size() - 1));
      calls++;
      return response;
    }

    private int calls() {
      return calls;
    }
  }
}
