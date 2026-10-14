package com.sableos.storage;

import com.sableos.core.provider.LlmCallAuditor;
import com.sableos.core.provider.Usage;
import com.sableos.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * llm_calls 落库实现（审计 day one 落库，宪法原则 VI）。
 *
 * <p>{@code trace_id} 取当前链路上下文写入（无上下文为 null；禁 UUID.randomUUID()，AGENTS.md §5.2）。 写库失败
 * fail-closed：净化后记日志并抛 {@link IllegalStateException}，由 ProviderService 的编排决定兜底 （失败路径原异常优先、成功路径
 * fail-open）。
 */
public class JpaLlmCallAuditor implements LlmCallAuditor {

  private static final Logger LOG = LoggerFactory.getLogger(JpaLlmCallAuditor.class);

  private final LlmCallRepository repository;

  public JpaLlmCallAuditor(LlmCallRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(
      String sessionId,
      String provider,
      String model,
      Usage usage,
      boolean success,
      String errorMessage,
      long durationMs) {
    LlmCall call = new LlmCall();
    call.setSessionId(sessionId);
    call.setProvider(provider);
    call.setModel(model);
    if (usage != null) {
      call.setPromptTokens(usage.promptTokens());
      call.setCompletionTokens(usage.completionTokens());
      call.setTotalTokens(usage.totalTokens());
    }
    call.setTraceId(TraceContext.traceId());
    call.setSuccess(success);
    call.setErrorMessage(errorMessage);
    call.setDurationMs(durationMs);
    try {
      repository.save(call);
    } catch (RuntimeException e) {
      LOG.error(
          "llm_calls 审计写入失败：sessionId={} provider={} error={}",
          safe(sessionId),
          safe(provider),
          safe(e.getMessage()));
      throw new IllegalStateException("llm_calls 审计写入失败", e);
    }
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法）。 */
  private static String safe(String value) {
    return (value == null) ? null : value.replaceAll("[\r\n]", "_");
  }
}
