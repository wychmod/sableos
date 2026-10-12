package com.sableos.web.error;

/** 领域资源不存在（会话、Agent 等）→ 404。 */
public class ResourceNotFoundException extends RuntimeException {

  public ResourceNotFoundException(String message) {
    super(message);
  }
}
