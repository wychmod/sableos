package com.sableos.web.error;

/** 定位冲突（如同一名称匹配多个实体）→ 409。 */
public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }
}
