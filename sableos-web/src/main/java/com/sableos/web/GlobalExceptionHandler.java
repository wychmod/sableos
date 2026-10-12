package com.sableos.web;

import com.sableos.web.common.ApiResponse;
import com.sableos.web.error.AgentTimeoutException;
import com.sableos.web.error.ConflictException;
import com.sableos.web.error.ProviderUnavailableException;
import com.sableos.web.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 全局异常翻译：未捕获异常一律转成统一信封（契约 http-api.md §统一响应信封）， 状态码只覆盖核心阶段六个：400 / 404 / 409 / 500 / 503 / 504。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 400 — 业务层可归因于请求的参数错误（缺参、格式非法、超长、provider 未注册等）。 */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException ex) {
    LOG.warn("Bad request: {}", sanitize(ex.getMessage()));
    return badRequest(ex.getMessage());
  }

  /** 400 — MVC 层客户端错误：请求体缺失 / JSON 语法错、缺必填参数、参数类型不匹配、Bean Validation 失败。 */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    MethodArgumentNotValidException.class
  })
  public ResponseEntity<ApiResponse<Void>> handleClientError(Exception ex) {
    LOG.warn("Client error: {}", sanitize(ex.getMessage()));
    return badRequest(readableClientMessage(ex));
  }

  /** 404 — 请求路径没有任何 handler / 静态资源可匹配。 */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException ex) {
    LOG.warn("Not found: {}", sanitize(ex.getMessage()));
    return error(HttpStatus.NOT_FOUND, "Resource not found");
  }

  /** 404 — 领域资源（会话、Agent 等）不存在，message 点名资源。 */
  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
    LOG.warn("Resource not found: {}", sanitize(ex.getMessage()));
    return error(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  /** 409 — 定位冲突：同一名称匹配多个实体等。 */
  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex) {
    LOG.warn("Conflict: {}", sanitize(ex.getMessage()));
    return error(HttpStatus.CONFLICT, ex.getMessage());
  }

  /** 503 — 模型提供方故障（核心阶段不做 fallback，直接暴露）。 */
  @ExceptionHandler(ProviderUnavailableException.class)
  public ResponseEntity<ApiResponse<Void>> handleProviderUnavailable(
      ProviderUnavailableException ex) {
    LOG.error("Provider unavailable: {}", sanitize(ex.getMessage()));
    return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
  }

  /** 504 — Agent 调用超过 60 秒上限。 */
  @ExceptionHandler(AgentTimeoutException.class)
  public ResponseEntity<ApiResponse<Void>> handleTimeout(AgentTimeoutException ex) {
    LOG.error("Agent call timed out: {}", sanitize(ex.getMessage()));
    return error(HttpStatus.GATEWAY_TIMEOUT, ex.getMessage());
  }

  /** 500 — 兜底：内部细节只进日志，不出响应。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleInternalError(Exception ex) {
    LOG.error("Unhandled exception", ex);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
  }

  private static ResponseEntity<ApiResponse<Void>> badRequest(String message) {
    return error(
        HttpStatus.BAD_REQUEST, message == null || message.isBlank() ? "Bad request" : message);
  }

  private static ResponseEntity<ApiResponse<Void>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(ApiResponse.error(status.value(), message));
  }

  private static String readableClientMessage(Exception ex) {
    if (ex instanceof MethodArgumentNotValidException validEx) {
      FieldError fieldError = validEx.getBindingResult().getFieldError();
      return fieldError == null
          ? "Bad request"
          : fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
    String message = ex.getMessage();
    return message == null || message.isBlank() ? "Bad request" : message;
  }

  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    // findsecbugs 的 CRLF 注入规则只认可 String.replaceAll 形态的净化，勿改用 Pattern/Matcher
    return value.replaceAll("[\r\n]", "_");
  }
}
