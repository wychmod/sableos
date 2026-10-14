package com.sableos.storage;

import com.sableos.core.tool.ToolInvocationAuditor;
import com.sableos.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * tool_invocations 落库实现（审计 day one 落库，宪法原则 VI）。
 *
 * <p>{@code trace_id} 取当前链路上下文写入（无上下文为 null；禁 UUID.randomUUID()，AGENTS.md §5.2）。 写库失败
 * fail-closed：净化后记日志并抛 {@link IllegalStateException}，绝不静默丢审计。
 */
public class JpaToolInvocationAuditor implements ToolInvocationAuditor {

  private static final Logger LOG = LoggerFactory.getLogger(JpaToolInvocationAuditor.class);

  private final ToolInvocationRepository repository;

  public JpaToolInvocationAuditor(ToolInvocationRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(
      String sessionId,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      long durationMs) {
    ToolInvocation invocation = new ToolInvocation();
    invocation.setSessionId(sessionId);
    invocation.setToolName(toolName);
    invocation.setInputJson(inputJson);
    invocation.setResultJson(resultJson);
    invocation.setSuccess(success);
    invocation.setErrorMessage(errorMessage);
    invocation.setDurationMs(durationMs);
    invocation.setTraceId(TraceContext.traceId());
    try {
      repository.save(invocation);
    } catch (RuntimeException e) {
      LOG.error(
          "tool_invocations 审计写入失败：sessionId={} toolName={} error={}",
          safe(sessionId),
          safe(toolName),
          safe(e.getMessage()));
      throw new IllegalStateException("tool_invocations 审计写入失败", e);
    }
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法）。 */
  private static String safe(String value) {
    return (value == null) ? null : value.replaceAll("[\r\n]", "_");
  }
}
