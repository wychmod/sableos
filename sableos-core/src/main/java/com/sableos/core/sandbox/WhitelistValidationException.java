package com.sableos.core.sandbox;

/**
 * 白名单值校验失败（格式非法、为空、超长等）→ HTTP 400。
 *
 * <p>**刻意不继承 {@code IllegalArgumentException}**：既有的 400 映射会把 {@code ex.getMessage()}
 * 原样写进响应体，若本异常参与该映射，就必须保证 message 不含调用方输入；而继承关系本身还会让"我方校验拒绝"与"JDK 解析失败"在 handler 层无法区分。独立异常 + 精确
 * handler 才能把两者分开。
 *
 * <p>约定：抛本异常时 message 只描述**字段与违反的规则**，不含原始输入值。
 */
public class WhitelistValidationException extends RuntimeException {

  public WhitelistValidationException(String message) {
    super(message);
  }
}
