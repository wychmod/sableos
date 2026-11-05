package com.sableos.core.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.core.agent.ToolExecutionContext;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileContext;
import com.sableos.core.provider.ToolCallRequest;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具执行与审计的唯一收敛处（FR-002 / FR-010）：按名解析 → 白名单校验位 → 解析入参 → 执行 → 成败两态写审计。
 *
 * <p>执行权唯一在此——Provider 层绝不调用 {@link SableTool#execute}；{@link com.sableos.core.react.ReActLoop}
 * 只调本类。
 *
 * <p><b>任何路径都返回 {@link ToolResult}，不向上抛</b>（FR-009「异常不得被吞掉」= 原因必须留痕于结果与审计、不静默丢弃； research
 * D-07）：未注册、入参非法、工具执行异常一律收敛为失败结果回填，循环据此继续、由轮数上限兜底（FR-008）。
 */
public final class ToolExecutor {

  private static final String UNKNOWN_TOOL_PREFIX = "未注册的工具：";
  private static final String PARSE_FAILURE_PREFIX = "工具入参解析失败：";
  private static final String EXECUTION_FAILURE_PREFIX = "工具执行异常：";

  /** 线程安全只读实例，全类共用一个（research 附录 A）。 */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);

  private final ToolCatalog toolCatalog;
  private final ToolInvocationAuditor auditor;

  public ToolExecutor(ToolCatalog toolCatalog, ToolInvocationAuditor auditor) {
    this.toolCatalog = toolCatalog;
    this.auditor = auditor;
  }

  /**
   * 执行一次模型要求的工具调用。
   *
   * @param sessionId 会话标识（审计落 {@code tool_invocations.session_id}）
   * @param call 模型给出的工具调用请求
   * @return 成功/失败两态结果（失败带原因）；任何路径都不抛异常
   */
  public ToolResult execute(String sessionId, ToolCallRequest call) {
    long start = System.currentTimeMillis();

    SableTool tool = toolCatalog.find(call.name());
    if (tool == null) {
      ToolResult result = ToolResult.failure(UNKNOWN_TOOL_PREFIX + call.name(), false);
      audit(sessionId, call, result, call.argumentsJson(), start);
      return result;
    }

    // 对外动作白名单校验在工具入口完成（20 节接线、24 节 WhitelistSandbox 实现）：
    // 文件 / Shell / HTTP / 通知各自在执行前调用 Sandbox.enforce，本层只负责把失败收敛为审计结果

    JsonNode input;
    try {
      input = parseArguments(call.argumentsJson());
    } catch (JsonProcessingException e) {
      ToolResult result = ToolResult.failure(PARSE_FAILURE_PREFIX + e.getOriginalMessage(), true);
      audit(sessionId, call, result, call.argumentsJson(), start);
      return result;
    }

    try {
      ToolResult result = withAgentContext(() -> tool.execute(input));
      audit(sessionId, call, result, call.argumentsJson(), start);
      logOutcome(call.name(), result, start);
      return result;
    } catch (RuntimeException e) {
      ToolResult result = ToolResult.failure(EXECUTION_FAILURE_PREFIX + e.getMessage(), true);
      audit(sessionId, call, result, call.argumentsJson(), start);
      logOutcome(call.name(), result, start);
      return result;
    }
  }

  /**
   * 在「当前 Agent」上下文里执行工具：{@code save_memory} / {@code recall_memory} 据此落到该 Agent 自己的记忆文件（第 30 节）。
   *
   * <p>取值来自 {@link ProfileContext#current()}——{@code AgentService}
   * 在进入处理链时已置入。执行完（含异常）一律清除，避免线程复用串台。
   */
  private static ToolResult withAgentContext(Supplier<ToolResult> action) {
    Profile profile = ProfileContext.current();
    ToolExecutionContext.setAgentName(profile == null ? null : profile.name());
    try {
      return action.get();
    } finally {
      ToolExecutionContext.clear();
    }
  }

  /**
   * 每次工具调用留一行主线日志：会话 id 走 MDC（由 {@code AgentService} 投影），这里只补工具名、成败与耗时， 使「一次触发」能在众多行里被串起来（课件 28 节
   * §2.4）。
   */
  private static void logOutcome(String toolName, ToolResult result, long start) {
    long elapsedMs = System.currentTimeMillis() - start;
    if (result.success()) {
      LOG.info("工具调用成功：tool={} 耗时={}ms", safe(toolName), elapsedMs);
    } else {
      LOG.warn(
          "工具调用失败：tool={} 耗时={}ms reason={}",
          safe(toolName),
          elapsedMs,
          safe(result.errorMessage()));
    }
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法；勿改成 Pattern/Matcher）。 */
  private static String safe(String value) {
    return (value == null) ? null : value.replaceAll("[\r\n]", "_");
  }

  /** 入参为空/空白视为空对象（工具自行处理空入参），不算解析失败。 */
  private static JsonNode parseArguments(String argumentsJson) throws JsonProcessingException {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return MAPPER.createObjectNode();
    }
    return MAPPER.readTree(argumentsJson);
  }

  private void audit(
      String sessionId, ToolCallRequest call, ToolResult result, String inputJson, long start) {
    String resultJson = result.success() ? result.content() : null;
    String errorMessage = result.success() ? null : result.errorMessage();
    auditor.record(
        sessionId,
        call.name(),
        inputJson,
        resultJson,
        result.success(),
        errorMessage,
        System.currentTimeMillis() - start);
  }
}
