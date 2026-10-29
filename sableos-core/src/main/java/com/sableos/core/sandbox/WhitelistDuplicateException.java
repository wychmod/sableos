package com.sableos.core.sandbox;

/**
 * 同一类型下该值已存在 → HTTP 409。
 *
 * <p>语义是"显式失败"而非静默幂等：管理台需要明确告知运营方"这条已经在列表里了"，而不是返回成功却什么也没发生。
 *
 * <p>message 中的 value 是**归一后**的形态（已通过校验、已存在于库中），不构成注入面。
 */
public class WhitelistDuplicateException extends RuntimeException {

  public WhitelistDuplicateException(WhitelistType type, String normalizedValue) {
    super("白名单条目已存在: " + type + " " + normalizedValue);
  }
}
