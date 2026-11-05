package com.sableos.core.provider;

/**
 * 「能力未配置」而非「调用失败」（第 30 节）：例如一句话生成 Agent 时 {@code sableos.author.model} 为空。
 *
 * <p>与 {@link ProviderFailureException} 分开：那是「配了但这次调用失败」，这里是「压根没配」。web 层对两者都给 503，但 message 不同——本类
 * message 由系统构造、不含上游响应体，可安全回显。
 */
public class ProviderNotConfiguredException extends RuntimeException {

  public ProviderNotConfiguredException(String message) {
    super(message);
  }
}
