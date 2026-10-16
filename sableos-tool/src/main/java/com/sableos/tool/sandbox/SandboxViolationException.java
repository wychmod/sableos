package com.sableos.tool.sandbox;

/** 安全校验违规（技术方案 §6.7）：由 {@link Sandbox#enforce} 抛出，上抛穿出工具方法、由执行层统一收敛为失败结果。 */
public class SandboxViolationException extends RuntimeException {

  public SandboxViolationException(String message) {
    super(message);
  }
}
