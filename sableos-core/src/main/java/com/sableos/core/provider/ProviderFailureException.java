package com.sableos.core.provider;

/**
 * 模型提供方调用失败（26 节）：网络不可达、鉴权失败、远端 5xx 等统一归入此类型，由 Web 层映射为 503。
 *
 * <p>放在 core 而不是 web：provider 实现模块不能反向依赖 web；core 是双方都能看到的唯一层。原始异常保留在 {@code cause}
 * 中供日志排查，对外响应只使用通用话术，不得回显 {@code message} 里的连接串/密钥等内部细节。
 */
public class ProviderFailureException extends RuntimeException {

  public ProviderFailureException(String message, Throwable cause) {
    super(message, cause);
  }
}
