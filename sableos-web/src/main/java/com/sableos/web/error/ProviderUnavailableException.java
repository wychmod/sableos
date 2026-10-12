package com.sableos.web.error;

/** 模型提供方故障 → 503（核心阶段不做 fallback，直接暴露）。 */
public class ProviderUnavailableException extends RuntimeException {

  public ProviderUnavailableException(String message) {
    super(message);
  }

  public ProviderUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
