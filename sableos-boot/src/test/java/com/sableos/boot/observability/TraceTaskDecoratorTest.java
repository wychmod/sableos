package com.sableos.boot.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sableos.core.trace.TraceContext;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceTaskDecoratorTest {

  private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(10);

  private final TraceTaskDecorator decorator = new TraceTaskDecorator();

  @AfterEach
  void tearDown() {
    TraceContext.clear();
  }

  @Test
  @DisplayName("无上下文时_直通不包装")
  void decorateIsPassThroughWithoutContext() {
    Runnable task = () -> {};

    assertThat(decorator.decorate(task)).isSameAs(task);
  }

  @Test
  @DisplayName("捕获提交时刻的上下文_而非执行时刻")
  void decorateCapturesContextAtSubmitTime() {
    AtomicReference<String> seen = new AtomicReference<>();
    Runnable decorated;
    String submitTimeTrace;
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      submitTimeTrace = scope.traceId();
      decorated = decorator.decorate(() -> seen.set(TraceContext.traceId()));
    }

    decorated.run();
    assertThat(seen.get()).isEqualTo(submitTimeTrace);

    try (TraceContext.Scope later = TraceContext.openRoot(TraceContext.CHANNEL_CLI)) {
      decorated.run();

      assertThat(seen.get()).isEqualTo(submitTimeTrace);
      assertThat(later.traceId()).isNotEqualTo(submitTimeTrace);
      assertThat(TraceContext.traceId()).isEqualTo(later.traceId());
    }
  }

  @Test
  @DisplayName("执行后恢复目标线程原状态_而非清空")
  void decorateRestoresPreExistingState() {
    Runnable decorated;
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      decorated = decorator.decorate(() -> {});
    }

    try (TraceContext.Scope outer = TraceContext.openRoot(TraceContext.CHANNEL_SCHEDULER)) {
      decorated.run();

      assertThat(TraceContext.traceId()).isEqualTo(outer.traceId());
      assertThat(MDC.get(TraceContext.MDC_CHANNEL)).isEqualTo(TraceContext.CHANNEL_SCHEDULER);
    }
    assertThat(TraceContext.traceId()).isNull();
  }

  @Test
  @DisplayName("跨虚拟线程执行_trace同值且span递进_子线程结束后不留残留")
  void decoratePropagatesTraceAndAdvancesSpan() throws Exception {
    AtomicReference<String> childTrace = new AtomicReference<>();
    AtomicReference<String> childSpan = new AtomicReference<>();
    AtomicReference<String> childMdc = new AtomicReference<>();
    String parentTrace;
    String parentSpan;
    Runnable decorated;

    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB);
        TraceContext.Scope ignored = TraceContext.attachBusiness("sess-1", "daily-weather", null)) {
      parentTrace = scope.traceId();
      parentSpan = TraceContext.spanId();
      decorated =
          decorator.decorate(
              () -> {
                childTrace.set(TraceContext.traceId());
                childSpan.set(TraceContext.spanId());
                childMdc.set(MDC.get(TraceContext.MDC_SESSION_ID));
              });
    }

    Thread child = Thread.ofVirtual().start(decorated);
    child.join(JOIN_TIMEOUT);

    assertThat(childTrace.get()).isEqualTo(parentTrace);
    assertThat(childSpan.get()).isNotEqualTo(parentSpan);
    assertThat(childMdc.get()).isEqualTo("sess-1");
    assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
  }
}
