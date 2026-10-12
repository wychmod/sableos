package com.sableos.web.common;

import java.time.Instant;

/**
 * 统一 REST 响应信封（契约 http-api.md）：成功与错误共用同一形状。
 *
 * <p>code 为 0 表示成功（message 恒为 ok），非 0 取标准 HTTP 状态值；data 在错误时恒为 null；
 * timestamp 为 ISO-8601 时刻（如 2026-10-05T15:00:00Z）。
 *
 * @param <T> 业务数据类型
 */
public class ApiResponse<T> {

  private final int code;
  private final String message;
  private final T data;
  private final String timestamp;

  private ApiResponse(int code, String message, T data) {
    this.code = code;
    this.message = message;
    this.data = data;
    this.timestamp = Instant.now().toString();
  }

  /** 成功响应：code 0、message ok。 */
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "ok", data);
  }

  /** 错误响应：message 为可定位原因。 */
  public static <T> ApiResponse<T> error(int code, String message) {
    return new ApiResponse<>(code, message, null);
  }

  public int getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }

  public T getData() {
    return data;
  }

  public String getTimestamp() {
    return timestamp;
  }

  @Override
  public String toString() {
    return "ApiResponse{code=" + code + ", message='" + message + "', timestamp='" + timestamp + "'}";
  }
}
