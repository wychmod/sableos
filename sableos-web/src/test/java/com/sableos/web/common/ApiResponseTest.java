package com.sableos.web.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sableos.core.trace.TraceContext;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 契约见 AGENTS.md §5.1（响应信封 5 字段）：字段口径逐条验收。 */
class ApiResponseTest {

  @Test
  @DisplayName("成功_信封为code0_messageOk_时间为ISO8601")
  void okEnvelope() {
    ApiResponse<String> response = ApiResponse.ok("payload");

    assertThat(response.getCode()).isZero();
    assertThat(response.getMessage()).isEqualTo("ok");
    assertThat(response.getData()).isEqualTo("payload");
    assertThat(Instant.parse(response.getTimestamp())).isNotNull();
  }

  @Test
  @DisplayName("错误_数据恒为null")
  void errorEnvelope() {
    ApiResponse<Void> response = ApiResponse.error(404, "会话不存在: abc-123");

    assertThat(response.getCode()).isEqualTo(404);
    assertThat(response.getMessage()).isEqualTo("会话不存在: abc-123");
    assertThat(response.getData()).isNull();
    assertThat(Instant.parse(response.getTimestamp())).isNotNull();
  }

  @Test
  @DisplayName("traceId_无链路上下文时为null")
  void traceIdIsNullWithoutContext() {
    TraceContext.clear();

    assertThat(ApiResponse.ok("payload").getTraceId()).isNull();
    assertThat(ApiResponse.error(500, "boom").getTraceId()).isNull();
  }

  @Test
  @DisplayName("traceId_链路作用域内与TraceContext同值")
  void traceIdMatchesContextInsideScope() {
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      assertThat(ApiResponse.ok("payload").getTraceId()).isEqualTo(scope.traceId());
      assertThat(ApiResponse.error(500, "boom").getTraceId()).isEqualTo(scope.traceId());
    }
  }

  /** 信封形状是 5 字段契约：traceId 为 null 也必须出现在 JSON 里，因此不能加 {@code @JsonInclude(NON_NULL)}。 */
  @Test
  @DisplayName("信封_形状恒定5字段且traceId为null时仍被序列化")
  void envelopeAlwaysSerializesFiveFields() throws Exception {
    TraceContext.clear();

    String json = new ObjectMapper().writeValueAsString(ApiResponse.ok("payload"));

    assertThat(json).contains("\"traceId\":null");
    Map<String, Object> parsed = JsonPath.read(json, "$");
    assertThat(parsed).containsOnlyKeys("code", "message", "data", "timestamp", "traceId");
  }

  @Test
  @DisplayName("toString_含traceId便于日志排障")
  void toStringContainsTraceId() {
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      assertThat(ApiResponse.ok("payload").toString()).contains(scope.traceId());
    }
  }
}
