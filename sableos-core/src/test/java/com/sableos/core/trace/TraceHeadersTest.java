package com.sableos.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TraceHeadersTest {

  private static final String VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String VALID_SPAN_ID = "00f067aa0ba902b7";
  private static final String VALID_HEADER = "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01";

  private static Function<String, String> getterOf(Map<String, String> headers) {
    return headers::get;
  }

  @Test
  @DisplayName("extract_优先采用合法的traceparent")
  void extract_prefersTraceparent() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TraceHeaders.TRACEPARENT, VALID_HEADER);
    headers.put(TraceHeaders.TRACE_ID, "0af7651916cd43dd8448eb211c80319c");

    Optional<TraceParent> extracted = TraceHeaders.extract(getterOf(headers));

    assertThat(extracted).isPresent();
    assertThat(extracted.get().traceId()).isEqualTo(VALID_TRACE_ID);
    assertThat(extracted.get().spanId()).isEqualTo(VALID_SPAN_ID);
  }

  @Test
  @DisplayName("extract_traceparent非法时回落到X_Trace_Id")
  void extract_fallsBackToLegacyHeader() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TraceHeaders.TRACEPARENT, "garbage");
    headers.put(TraceHeaders.TRACE_ID, "0af7651916cd43dd8448eb211c80319c");

    Optional<TraceParent> extracted = TraceHeaders.extract(getterOf(headers));

    assertThat(extracted).isPresent();
    assertThat(extracted.get().traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    assertThat(TraceIds.isValidSpanId(extracted.get().spanId())).isTrue();
    assertThat(extracted.get().sampled()).isTrue();
  }

  @Test
  @DisplayName("extract_X_Trace_Id非法时返回empty由调用方生成新链路")
  void extract_returnsEmptyWhenNothingValid() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TraceHeaders.TRACE_ID, "not-a-trace-id");

    assertThat(TraceHeaders.extract(getterOf(headers))).isEmpty();
    assertThat(TraceHeaders.extract(getterOf(new HashMap<>()))).isEmpty();
    assertThat(TraceHeaders.extract(null)).isEmpty();
  }

  @Test
  @DisplayName("extract_tracestate随traceparent一并带出")
  void extract_carriesTracestate() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TraceHeaders.TRACEPARENT, VALID_HEADER);
    headers.put(TraceHeaders.TRACESTATE, "rojo=00f067aa0ba902b7:1");

    Optional<TraceParent> extracted = TraceHeaders.extract(getterOf(headers));

    assertThat(extracted).isPresent();
    assertThat(extracted.get().tracestate()).isEqualTo("rojo=00f067aa0ba902b7:1");
  }

  @Test
  @DisplayName("sanitize_含CR或LF或其他控制字符时整体丢弃防头注入")
  void sanitizeTracestate_rejectsControlCharacters() {
    assertThat(TraceHeaders.sanitizeTracestate("ok=value")).isEqualTo("ok=value");
    assertThat(TraceHeaders.sanitizeTracestate("ok=value\r\nX-Injected: 1")).isNull();
    assertThat(TraceHeaders.sanitizeTracestate("ok=value\nX-Injected: 1")).isNull();
    assertThat(TraceHeaders.sanitizeTracestate("ok=val\u0000ue")).isNull();
    assertThat(TraceHeaders.sanitizeTracestate("中文值")).isNull();
  }

  @Test
  @DisplayName("sanitize_空值与超长值丢弃且首尾空白被裁剪")
  void sanitizeTracestate_rejectsBlankAndOversized() {
    assertThat(TraceHeaders.sanitizeTracestate(null)).isNull();
    assertThat(TraceHeaders.sanitizeTracestate("")).isNull();
    assertThat(TraceHeaders.sanitizeTracestate("   ")).isNull();
    assertThat(TraceHeaders.sanitizeTracestate("a".repeat(513))).isNull();
    assertThat(TraceHeaders.sanitizeTracestate("a".repeat(512))).hasSize(512);
    assertThat(TraceHeaders.sanitizeTracestate("  ok=value  ")).isEqualTo("ok=value");
  }

  @Test
  @DisplayName("inject_写出X_Trace_Id与traceparent且tracestate非空才写")
  void inject_writesBothHeaders() {
    Map<String, String> out = new HashMap<>();
    TraceParent parent = TraceParent.parse(VALID_HEADER).orElseThrow();

    TraceHeaders.inject(parent, out::put);

    assertThat(out).containsEntry(TraceHeaders.TRACE_ID, VALID_TRACE_ID);
    assertThat(out).containsEntry(TraceHeaders.TRACEPARENT, VALID_HEADER);
    assertThat(out).doesNotContainKey(TraceHeaders.TRACESTATE);
  }

  @Test
  @DisplayName("inject_tracestate非空时一并写出")
  void inject_writesTracestateWhenPresent() {
    Map<String, String> out = new HashMap<>();
    TraceParent parent = TraceParent.parse(VALID_HEADER).orElseThrow().withTracestate("vendor=x");

    TraceHeaders.inject(parent, out::put);

    assertThat(out).containsEntry(TraceHeaders.TRACESTATE, "vendor=x");
  }

  @Test
  @DisplayName("inject_parent或setter为null时不做任何事")
  void inject_isNullSafe() {
    Map<String, String> out = new HashMap<>();

    TraceHeaders.inject(null, out::put);
    TraceHeaders.inject(TraceParent.newRoot(), null);

    assertThat(out).isEmpty();
  }
}
