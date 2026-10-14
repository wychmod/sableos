package com.sableos.core.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.core.provider.ToolCallRequest;

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

    // ---- 对外动作白名单校验位（FR-009；具体规则归 24 节）----
    // TODO(24 节): Sandbox.enforce(...) 接线处——本节只留调用位，不实现规则
    // （Sandbox 接口五件归 20 节课件、WhitelistSandbox 实现归 24 节；本节不新建 Sandbox 类型）

    JsonNode input;
    try {
      input = parseArguments(call.argumentsJson());
    } catch (JsonProcessingException e) {
      ToolResult result = ToolResult.failure(PARSE_FAILURE_PREFIX + e.getOriginalMessage(), true);
      audit(sessionId, call, result, call.argumentsJson(), start);
      return result;
    }

    try {
      ToolResult result = tool.execute(input);
      audit(sessionId, call, result, call.argumentsJson(), start);
      return result;
    } catch (RuntimeException e) {
      ToolResult result = ToolResult.failure(EXECUTION_FAILURE_PREFIX + e.getMessage(), true);
      audit(sessionId, call, result, call.argumentsJson(), start);
      return result;
    }
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
