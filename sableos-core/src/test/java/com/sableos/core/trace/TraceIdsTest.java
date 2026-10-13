package com.sableos.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TraceIdsTest {

  private static final int SAMPLE_SIZE = 1000;

  @Test
  @DisplayName("生成_traceId为32位小写hex")
  void newTraceId_isThirtyTwoLowerHex() {
    for (int i = 0; i < SAMPLE_SIZE; i++) {
      assertThat(TraceIds.newTraceId()).matches("[0-9a-f]{32}");
    }
  }

  @Test
  @DisplayName("生成_spanId为16位小写hex")
  void newSpanId_isSixteenLowerHex() {
    for (int i = 0; i < SAMPLE_SIZE; i++) {
      assertThat(TraceIds.newSpanId()).matches("[0-9a-f]{16}");
    }
  }

  @Test
  @DisplayName("生成_一万次无重复且非全零")
  void newTraceId_isUniqueAndNonZero() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      String traceId = TraceIds.newTraceId();
      seen.add(traceId);
      assertThat(traceId).isNotEqualTo("0".repeat(TraceIds.TRACE_ID_LENGTH));
    }
    assertThat(seen).hasSize(10_000);
  }

  @Test
  @DisplayName("生成_spanId不为全零")
  void newSpanId_isNonZero() {
    String allZero = "0".repeat(TraceIds.SPAN_ID_LENGTH);
    for (int i = 0; i < SAMPLE_SIZE; i++) {
      assertThat(TraceIds.newSpanId()).isNotEqualTo(allZero);
    }
  }

  @Test
  @DisplayName("校验_接受合法值")
  void validators_acceptLegalValues() {
    assertThat(TraceIds.isValidTraceId("4bf92f3577b34da6a3ce929d0e0e4736")).isTrue();
    assertThat(TraceIds.isValidSpanId("00f067aa0ba902b7")).isTrue();
    assertThat(TraceIds.isLowerHex("00", 2)).isTrue();
    assertThat(TraceIds.isLowerHex("ff", 2)).isTrue();
  }

  @Test
  @DisplayName("校验_拒绝大写hex")
  void validators_rejectUpperCase() {
    assertThat(TraceIds.isValidTraceId("4BF92F3577B34DA6A3CE929D0E0E4736")).isFalse();
    assertThat(TraceIds.isValidSpanId("00F067AA0BA902B7")).isFalse();
  }

  @Test
  @DisplayName("校验_拒绝全零")
  void validators_rejectAllZero() {
    assertThat(TraceIds.isValidTraceId("0".repeat(32))).isFalse();
    assertThat(TraceIds.isValidSpanId("0".repeat(16))).isFalse();
  }

  @Test
  @DisplayName("校验_拒绝长度错误与非法字符与null")
  void validators_rejectWrongLengthAndIllegalCharsAndNull() {
    assertThat(TraceIds.isValidTraceId("4bf92f3577b34da6")).isFalse();
    assertThat(TraceIds.isValidTraceId("4bf92f3577b34da6a3ce929d0e0e473g")).isFalse();
    assertThat(TraceIds.isValidTraceId("4bf92f3577b34da6a3ce929d0e0e47360")).isFalse();
    assertThat(TraceIds.isValidTraceId(null)).isFalse();
    assertThat(TraceIds.isValidSpanId(null)).isFalse();
    assertThat(TraceIds.isLowerHex(null, 2)).isFalse();
    assertThat(TraceIds.isLowerHex("0", 2)).isFalse();
    assertThat(TraceIds.isLowerHex("zz", 2)).isFalse();
  }
}
