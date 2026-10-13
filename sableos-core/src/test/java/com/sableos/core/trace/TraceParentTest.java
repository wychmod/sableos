package com.sableos.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TraceParentTest {

  private static final String VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String VALID_SPAN_ID = "00f067aa0ba902b7";
  private static final String VALID_HEADER = "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01";

  @Test
  @DisplayName("解析_标准00版本四段")
  void parse_acceptsStandardVersion00() {
    Optional<TraceParent> parsed = TraceParent.parse(VALID_HEADER);

    assertThat(parsed).isPresent();
    assertThat(parsed.get().version()).isEqualTo("00");
    assertThat(parsed.get().traceId()).isEqualTo(VALID_TRACE_ID);
    assertThat(parsed.get().spanId()).isEqualTo(VALID_SPAN_ID);
    assertThat(parsed.get().sampled()).isTrue();
    assertThat(parsed.get().tracestate()).isNull();
  }

  @Test
  @DisplayName("解析_sampled标志为00时不采样")
  void parse_readsSampledFlag() {
    Optional<TraceParent> notSampled =
        TraceParent.parse("00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-00");
    Optional<TraceParent> otherFlags =
        TraceParent.parse("00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-03");

    assertThat(notSampled).isPresent();
    assertThat(notSampled.get().sampled()).isFalse();
    assertThat(otherFlags).isPresent();
    assertThat(otherFlags.get().sampled()).isTrue();
  }

  @Test
  @DisplayName("解析_高版本取前四段")
  void parse_acceptsHigherVersionWithExtraFields() {
    Optional<TraceParent> parsed =
        TraceParent.parse("01-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01-extra");

    assertThat(parsed).isPresent();
    assertThat(parsed.get().version()).isEqualTo("01");
    assertThat(parsed.get().traceId()).isEqualTo(VALID_TRACE_ID);
  }

  @Test
  @DisplayName("解析_00版本字段数不等于四则拒绝")
  void parse_rejectsVersion00WithWrongFieldCount() {
    assertThat(TraceParent.parse("00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01-extra"))
        .isEmpty();
  }

  @Test
  @DisplayName("解析_版本ff拒绝")
  void parse_rejectsVersionFf() {
    assertThat(TraceParent.parse("ff-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01")).isEmpty();
  }

  @Test
  @DisplayName("解析_版本非法拒绝")
  void parse_rejectsIllegalVersion() {
    assertThat(TraceParent.parse("zz-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01")).isEmpty();
    assertThat(TraceParent.parse("0-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01")).isEmpty();
    assertThat(TraceParent.parse("000-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01")).isEmpty();
  }

  @Test
  @DisplayName("解析_段数不足拒绝")
  void parse_rejectsTooFewFields() {
    assertThat(TraceParent.parse("00-" + VALID_TRACE_ID)).isEmpty();
    assertThat(TraceParent.parse("00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID)).isEmpty();
    assertThat(TraceParent.parse("00")).isEmpty();
  }

  @Test
  @DisplayName("解析_全零traceId或spanId拒绝")
  void parse_rejectsAllZeroIds() {
    assertThat(TraceParent.parse("00-" + "0".repeat(32) + "-" + VALID_SPAN_ID + "-01")).isEmpty();
    assertThat(TraceParent.parse("00-" + VALID_TRACE_ID + "-" + "0".repeat(16) + "-01")).isEmpty();
  }

  @Test
  @DisplayName("解析_大写hex与非法flags拒绝")
  void parse_rejectsUpperCaseAndIllegalFlags() {
    assertThat(
            TraceParent.parse("00-" + VALID_TRACE_ID.toUpperCase() + "-" + VALID_SPAN_ID + "-01"))
        .isEmpty();
    assertThat(TraceParent.parse("00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-zz")).isEmpty();
    assertThat(TraceParent.parse("00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-1")).isEmpty();
  }

  @Test
  @DisplayName("解析_null与空串与空白返回empty")
  void parse_returnsEmptyForBlankInput() {
    assertThat(TraceParent.parse(null)).isEmpty();
    assertThat(TraceParent.parse("")).isEmpty();
    assertThat(TraceParent.parse("   ")).isEmpty();
  }

  @Test
  @DisplayName("解析_首尾空白被容忍")
  void parse_toleratesSurroundingWhitespace() {
    assertThat(TraceParent.parse("  " + VALID_HEADER + "  ")).isPresent();
  }

  @Test
  @DisplayName("格式化_解析往返一致")
  void toHeaderValue_roundTrips() {
    TraceParent original = TraceParent.parse(VALID_HEADER).orElseThrow();

    assertThat(original.toHeaderValue()).isEqualTo(VALID_HEADER);
    assertThat(TraceParent.parse(original.toHeaderValue())).contains(original);
  }

  @Test
  @DisplayName("格式化_不采样时flags为00")
  void toHeaderValue_rendersNotSampled() {
    TraceParent notSampled = new TraceParent("00", VALID_TRACE_ID, VALID_SPAN_ID, false, null);

    assertThat(notSampled.toHeaderValue()).endsWith("-00");
  }

  @Test
  @DisplayName("newRoot_生成合法且采样的根链路")
  void newRoot_generatesSampledValidRoot() {
    TraceParent root = TraceParent.newRoot();

    assertThat(root.version()).isEqualTo("00");
    assertThat(TraceIds.isValidTraceId(root.traceId())).isTrue();
    assertThat(TraceIds.isValidSpanId(root.spanId())).isTrue();
    assertThat(root.sampled()).isTrue();
    assertThat(TraceParent.parse(root.toHeaderValue())).contains(root);
  }

  @Test
  @DisplayName("withNewSpan_同trace不同span且保留sampled与tracestate")
  void withNewSpan_keepsTraceAndFlags() {
    TraceParent original =
        TraceParent.parse(VALID_HEADER).orElseThrow().withTracestate("vendor=opaque");

    TraceParent child = original.withNewSpan();

    assertThat(child.traceId()).isEqualTo(original.traceId());
    assertThat(child.spanId()).isNotEqualTo(original.spanId());
    assertThat(TraceIds.isValidSpanId(child.spanId())).isTrue();
    assertThat(child.sampled()).isEqualTo(original.sampled());
    assertThat(child.tracestate()).isEqualTo("vendor=opaque");
  }

  @Test
  @DisplayName("withTracestate_原样保留不解析")
  void withTracestate_keepsValueVerbatim() {
    TraceParent parent =
        TraceParent.newRoot().withTracestate("rojo=00f067aa0ba902b7:1,congo=t61rcWkgMzE");

    assertThat(parent.tracestate()).isEqualTo("rojo=00f067aa0ba902b7:1,congo=t61rcWkgMzE");
  }
}
