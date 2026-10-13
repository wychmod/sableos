package com.sableos.core.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceContextTest {

  private static final String VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String VALID_SPAN_ID = "00f067aa0ba902b7";

  @AfterEach
  void tearDown() {
    TraceContext.restore(null);
  }

  @Test
  @DisplayName("无上下文_读取均为null且快照为EMPTY")
  void noContext_returnsNullAndEmptySnapshot() {
    assertThat(TraceContext.traceId()).isNull();
    assertThat(TraceContext.spanId()).isNull();
    assertThat(TraceContext.currentParent()).isNull();
    assertThat(TraceContext.snapshot()).isSameAs(TraceSnapshot.EMPTY);
    assertThat(TraceContext.snapshot().isEmpty()).isTrue();
    assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
  }

  @Test
  @DisplayName("openRoot_生成合法id并双写ThreadLocal与MDC")
  void openRoot_writesBothThreadLocalAndMdc() {
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      assertThat(scope.traceId()).matches("[0-9a-f]{32}");
      assertThat(scope.spanId()).matches("[0-9a-f]{16}");
      assertThat(scope.isOwner()).isTrue();
      assertThat(TraceContext.traceId()).isEqualTo(scope.traceId());
      assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isEqualTo(scope.traceId());
      assertThat(MDC.get(TraceContext.MDC_SPAN_ID)).isEqualTo(scope.spanId());
      assertThat(MDC.get(TraceContext.MDC_CHANNEL)).isEqualTo("web");
      assertThat(MDC.get(TraceContext.MDC_SESSION_ID)).isNull();
    }
  }

  @Test
  @DisplayName("openRoot_channel为null时不写该键")
  void openRoot_skipsNullChannel() {
    try (TraceContext.Scope scope = TraceContext.openRoot(null)) {
      assertThat(scope.traceId()).isNotNull();
      assertThat(MDC.get(TraceContext.MDC_CHANNEL)).isNull();
    }
  }

  @Test
  @DisplayName("close后_ThreadLocal与MDC均被清空")
  void close_clearsThreadLocalAndMdc() {
    TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_CLI);
    scope.close();

    assertThat(TraceContext.traceId()).isNull();
    assertThat(TraceContext.snapshot()).isSameAs(TraceSnapshot.EMPTY);
    assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
    assertThat(MDC.get(TraceContext.MDC_SPAN_ID)).isNull();
    assertThat(MDC.get(TraceContext.MDC_CHANNEL)).isNull();
  }

  @Test
  @DisplayName("close_不清理其他代码放进MDC的键")
  void close_leavesForeignMdcKeysAlone() {
    MDC.put("custom-key", "custom-value");
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      assertThat(MDC.get("custom-key")).isEqualTo("custom-value");
    }

    assertThat(MDC.get("custom-key")).isEqualTo("custom-value");
    MDC.remove("custom-key");
  }

  @Test
  @DisplayName("open_沿用上游traceId并生成新spanId")
  void open_continuesUpstreamTraceWithNewSpan() {
    TraceParent inbound =
        new TraceParent(TraceParent.VERSION, VALID_TRACE_ID, VALID_SPAN_ID, false, "vendor=x");

    try (TraceContext.Scope scope = TraceContext.open(inbound, TraceContext.CHANNEL_WEB)) {
      assertThat(scope.traceId()).isEqualTo(VALID_TRACE_ID);
      assertThat(scope.spanId()).isNotEqualTo(VALID_SPAN_ID);
      assertThat(TraceIds.isValidSpanId(scope.spanId())).isTrue();
      assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isEqualTo(VALID_TRACE_ID);
      assertThat(scope.traceparentHeader())
          .startsWith("00-" + VALID_TRACE_ID + "-")
          .endsWith("-00");
    }
  }

  @Test
  @DisplayName("open_入站为null时等价于开根链路")
  void open_fallsBackToRootWhenInboundNull() {
    try (TraceContext.Scope scope = TraceContext.open(null, TraceContext.CHANNEL_WEB)) {
      assertThat(scope.traceId()).matches("[0-9a-f]{32}");
      assertThat(scope.isOwner()).isTrue();
    }
  }

  @Test
  @DisplayName("openIfAbsent_已有上下文时复用同值且owner为false")
  void openIfAbsent_reusesExistingContext() {
    try (TraceContext.Scope outer = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      try (TraceContext.Scope inner = TraceContext.openIfAbsent()) {
        assertThat(inner.traceId()).isEqualTo(outer.traceId());
        assertThat(inner.spanId()).isEqualTo(outer.spanId());
        assertThat(inner.isOwner()).isFalse();
      }
      assertThat(TraceContext.traceId()).isEqualTo(outer.traceId());
      assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isEqualTo(outer.traceId());
    }
  }

  @Test
  @DisplayName("openIfAbsent_无上下文时开根链路且owner为true")
  void openIfAbsent_opensRootWhenAbsent() {
    try (TraceContext.Scope scope = TraceContext.openIfAbsent()) {
      assertThat(scope.isOwner()).isTrue();
      assertThat(scope.traceId()).matches("[0-9a-f]{32}");
    }
  }

  @Test
  @DisplayName("openSpan_trace不变span递进且close后恢复外层span")
  void openSpan_advancesSpanWithinSameTrace() {
    try (TraceContext.Scope outer = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      String outerSpan = outer.spanId();
      try (TraceContext.Scope inner = TraceContext.openSpan()) {
        assertThat(inner.traceId()).isEqualTo(outer.traceId());
        assertThat(inner.spanId()).isNotEqualTo(outerSpan);
        assertThat(MDC.get(TraceContext.MDC_SPAN_ID)).isEqualTo(inner.spanId());
      }
      assertThat(TraceContext.spanId()).isEqualTo(outerSpan);
      assertThat(MDC.get(TraceContext.MDC_SPAN_ID)).isEqualTo(outerSpan);
    }
  }

  @Test
  @DisplayName("attachBusiness_写入业务键并在close后恢复外层")
  void attachBusiness_writesAndRestores() {
    try (TraceContext.Scope outer = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      try (TraceContext.Scope inner =
          TraceContext.attachBusiness("sess-1", "daily-weather", TraceContext.CHANNEL_CLI)) {
        assertThat(inner.traceId()).isEqualTo(outer.traceId());
        assertThat(MDC.get(TraceContext.MDC_SESSION_ID)).isEqualTo("sess-1");
        assertThat(MDC.get(TraceContext.MDC_PROFILE_NAME)).isEqualTo("daily-weather");
        assertThat(MDC.get(TraceContext.MDC_CHANNEL)).isEqualTo("cli");
        assertThat(TraceContext.business(TraceContext.MDC_SESSION_ID)).isEqualTo("sess-1");
      }
      assertThat(MDC.get(TraceContext.MDC_SESSION_ID)).isNull();
      assertThat(MDC.get(TraceContext.MDC_PROFILE_NAME)).isNull();
      assertThat(MDC.get(TraceContext.MDC_CHANNEL)).isEqualTo("web");
    }
  }

  @Test
  @DisplayName("attachBusiness_忽略null与空白值")
  void attachBusiness_ignoresBlankValues() {
    try (TraceContext.Scope scope = TraceContext.attachBusiness("sess-1", "  ", null)) {
      assertThat(MDC.get(TraceContext.MDC_SESSION_ID)).isEqualTo("sess-1");
      assertThat(MDC.get(TraceContext.MDC_PROFILE_NAME)).isNull();
      assertThat(MDC.get(TraceContext.MDC_CHANNEL)).isNull();
      assertThat(scope.traceId()).matches("[0-9a-f]{32}");
    }
  }

  @Test
  @DisplayName("attachBusiness_无上下文时也开根链路")
  void attachBusiness_opensRootWhenAbsent() {
    try (TraceContext.Scope scope = TraceContext.attachBusiness("sess-1", null, null)) {
      assertThat(scope.isOwner()).isTrue();
      assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isEqualTo(scope.traceId());
    }
  }

  @Test
  @DisplayName("snapshot_携带全部状态且业务Map不可变")
  void snapshot_carriesFullState() {
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB);
        TraceContext.Scope ignored = TraceContext.attachBusiness("sess-1", "daily-weather", null)) {
      TraceSnapshot snapshot = TraceContext.snapshot();

      assertThat(snapshot.traceId()).isEqualTo(scope.traceId());
      assertThat(snapshot.isEmpty()).isFalse();
      assertThat(snapshot.business()).containsEntry("sessionId", "sess-1");
      assertThat(snapshot.business()).containsEntry("channel", "web");
      assertThat(snapshot.toTraceParent()).isNotNull();
      assertThat(snapshot.toTraceParent().toHeaderValue())
          .isEqualTo("00-" + scope.traceId() + "-" + scope.spanId() + "-01");
      assertThatThrownBy(() -> snapshot.business().put("k", "v"))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Test
  @DisplayName("snapshot_空快照的wrap为直通且不改动线程状态")
  void emptySnapshot_wrapIsPassThrough() {
    TraceSnapshot empty = TraceContext.snapshot();
    Runnable task = () -> assertThat(TraceContext.traceId()).isNull();

    assertThat(empty.wrapRunnable(task)).isSameAs(task);
    try (TraceContext.Scope scope = empty.openInCurrentThread()) {
      assertThat(scope.isOwner()).isTrue();
      assertThat(TraceContext.traceId()).isNull();
    }
  }

  @Test
  @DisplayName("currentParent_反映当前trace与span")
  void currentParent_reflectsCurrentState() {
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      TraceParent parent = TraceContext.currentParent();

      assertThat(parent).isNotNull();
      assertThat(parent.traceId()).isEqualTo(scope.traceId());
      assertThat(parent.spanId()).isEqualTo(scope.spanId());
      assertThat(parent.sampled()).isTrue();
    }
  }
}
