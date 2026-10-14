package com.sableos.core.tool;

/**
 * 工具调用审计写入的前向契约（FR-010 / 宪法原则 VI：审计 day one 落库）：每次 {@code ToolExecutor.execute} 成败各一条。
 *
 * <p>逐参对齐 16 节 {@code LlmCallAuditor} 风格；实现（storage 的 {@code JpaToolInvocationAuditor}）fail-closed：
 * 写库失败即抛 {@link IllegalStateException}，绝不静默丢审计。{@code trace_id} 由实现内部取 {@code
 * TraceContext.traceId()}，不经参数传递。
 */
public interface ToolInvocationAuditor {

  /**
   * 记录一次工具调用。
   *
   * @param sessionId 所属会话标识
   * @param toolName 工具名（模型给出、未解析到也原样记录）
   * @param inputJson 入参原始 JSON 文本（不可用时为 null）
   * @param resultJson 结果内容（失败时为 null，原因走 {@code errorMessage}）
   * @param success 成功标识
   * @param errorMessage 失败原因（成功恒 null）
   * @param durationMs 执行耗时（毫秒，含解析与执行，不含审计写入）
   */
  void record(
      String sessionId,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      long durationMs);
}
