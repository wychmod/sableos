package com.sableos.web.common;

import com.sableos.core.trace.TraceContext;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 统一 REST 响应信封（契约 http-api.md）：成功与错误共用同一形状。
 *
 * <p>code 为 0 表示成功（message 恒为 ok），非 0 取标准 HTTP 状态值；data 在错误时恒为 null； timestamp 为 ISO-8601 时刻（如
 * 2026-10-05T15:00:00Z）；traceId 为本次调用的链路标识。
 *
 * <p><b>信封形状恒定为这 5 个字段</b>：traceId 为 null 时同样输出，因此不能加 {@code @JsonInclude(NON_NULL)}。 增删字段必须同步
 * {@code contracts/http-api.md} 与 {@code ApiResponseTest}，否则 {@code /speckit-analyze} 会报漂移。
 *
 * @param <T> 业务数据类型
 */
public class ApiResponse<T> {

  private final int code;
  private final String message;
  private final T data;
  private final String timestamp;
  private final String traceId;

  private ApiResponse(int code, String message, T data) {
    this.code = code;
    this.message = message;
    this.data = data;
    this.timestamp = Instant.now().toString();
    // 与 Filter 同一线程，此时链路作用域仍然打开；无上下文（内部构造、单测）时为 null
    this.traceId = TraceContext.traceId();
  }

  /** 成功响应：code 0、message ok。 */
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "ok", data);
  }

  /** 错误响应：message 为可定位原因。 */
  public static <T> ApiResponse<T> error(int code, String message) {
    return new ApiResponse<>(code, message, null);
  }

  @Schema(description = "0 表示成功；非 0 取标准 HTTP 状态值", example = "0")
  public int getCode() {
    return code;
  }

  @Schema(description = "人类可读信息；成功恒为 ok，错误时为可定位原因", example = "ok")
  public String getMessage() {
    return message;
  }

  @Schema(description = "成功时为业务数据，错误时为 null")
  public T getData() {
    return data;
  }

  @Schema(description = "ISO-8601 时刻", example = "2026-10-05T15:00:00Z")
  public String getTimestamp() {
    return timestamp;
  }

  @Schema(
      description =
          "本次调用的链路标识（W3C trace-id，32 位小写 hex），与响应头 X-Trace-Id、日志 MDC.traceId、"
              + "审计表 trace_id 同值；无链路上下文时为 null",
      example = "4bf92f3577b34da6a3ce929d0e0e4736",
      nullable = true)
  public String getTraceId() {
    return traceId;
  }

  @Override
  public String toString() {
    return "ApiResponse{code="
        + code
        + ", message='"
        + message
        + "', timestamp='"
        + timestamp
        + "', traceId='"
        + traceId
        + "'}";
  }
}
