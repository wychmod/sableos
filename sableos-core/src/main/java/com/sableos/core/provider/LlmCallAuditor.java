package com.sableos.core.provider;

/**
 * LLM 调用审计契约（实现见 storage 的 JpaLlmCallAuditor）：每次调用成败各落一条 llm_calls。
 *
 * <p>七参签名与课件验收 harness 的失败审计测试逐参对齐。实现 fail-closed：写库异常上抛， 由 {@link ProviderService}
 * 的编排决定兜底——失败路径原异常优先、成功路径 fail-open。
 */
public interface LlmCallAuditor {

  /**
   * 落一条调用审计。
   *
   * @param sessionId 会话标识
   * @param provider provider 名（Profile 声明值）
   * @param model 模型名（Profile 声明值）
   * @param usage token 用量；失败或供应商未返回时为 null
   * @param success 成功标识
   * @param errorMessage 失败原因（成功时为 null）
   * @param durationMs 调用耗时（毫秒）
   */
  void record(
      String sessionId,
      String provider,
      String model,
      Usage usage,
      boolean success,
      String errorMessage,
      long durationMs);
}
