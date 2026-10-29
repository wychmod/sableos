package com.sableos.core.sandbox;

/**
 * 白名单条目不存在 → HTTP 404。
 *
 * <p>不复用 web 模块的 {@code ResourceNotFoundException}：本异常定义在 core（{@link SandboxWhitelistAdmin}
 * 契约层），web 只做状态码映射，保持 {@code web → core} 单向依赖。message 只含路径参数 id，不含其他调用方输入。
 */
public class WhitelistEntryNotFoundException extends RuntimeException {

  public WhitelistEntryNotFoundException(long id) {
    super("白名单条目不存在: " + id);
  }
}
