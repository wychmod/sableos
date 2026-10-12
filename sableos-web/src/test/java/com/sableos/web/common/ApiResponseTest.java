package com.sableos.web.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 契约 http-api.md §统一响应信封：字段口径逐条验收。 */
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
}
