package com.sableos.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TracedExecutorsTest {

  private static final long AWAIT_SECONDS = 10L;

  @AfterEach
  void tearDown() {
    TraceContext.restore(null);
  }

  @Test
  @DisplayName("包装虚拟线程Executor_子线程的ThreadLocal与MDC都拿到同一条链路")
  void wrappedVirtualThreadExecutor_propagatesToChild() throws Exception {
    try (ExecutorService executor =
        TracedExecutors.wrap(Executors.newVirtualThreadPerTaskExecutor())) {
      AtomicReference<String> childTrace = new AtomicReference<>();
      AtomicReference<String> childSpan = new AtomicReference<>();
      AtomicReference<String> childMdcTrace = new AtomicReference<>();
      AtomicReference<String> childMdcChannel = new AtomicReference<>();
      AtomicReference<Boolean> childIsVirtual = new AtomicReference<>();
      CountDownLatch done = new CountDownLatch(1);

      try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
        executor.execute(
            () -> {
              childTrace.set(TraceContext.traceId());
              childSpan.set(TraceContext.spanId());
              childMdcTrace.set(MDC.get(TraceContext.MDC_TRACE_ID));
              childMdcChannel.set(MDC.get(TraceContext.MDC_CHANNEL));
              childIsVirtual.set(Thread.currentThread().isVirtual());
              done.countDown();
            });

        assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(childTrace.get()).isEqualTo(scope.traceId());
        assertThat(childSpan.get()).isEqualTo(scope.spanId());
      }

      assertThat(childIsVirtual.get()).isTrue();
      assertThat(childMdcTrace.get()).isEqualTo(childTrace.get());
      assertThat(childMdcChannel.get()).isEqualTo(TraceContext.CHANNEL_WEB);
    }
  }

  @Test
  @DisplayName("包装Runnable_执行完毕后当前线程状态被恢复而非残留")
  void wrappedRunnable_restoresCallerThreadState() {
    Runnable wrapped;
    String capturedTrace;
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_CLI)) {
      capturedTrace = scope.traceId();
      wrapped =
          TracedExecutors.wrapRunnable(
              () -> {
                assertThat(TraceContext.traceId()).isEqualTo(capturedTrace);
                assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isEqualTo(capturedTrace);
              });
    }

    assertThat(TraceContext.traceId()).isNull();
    wrapped.run();
    assertThat(TraceContext.traceId()).isNull();
    assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
  }

  @Test
  @DisplayName("包装Runnable_执行时不破坏目标线程已有的上下文")
  void wrappedRunnable_restoresPreExistingTargetState() {
    Runnable wrapped;
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      wrapped =
          TracedExecutors.wrapRunnable(
              () -> {
                assertThat(TraceContext.traceId()).isEqualTo(scope.traceId());
              });
    }

    try (TraceContext.Scope outer = TraceContext.openRoot(TraceContext.CHANNEL_SCHEDULER)) {
      wrapped.run();

      assertThat(TraceContext.traceId()).isEqualTo(outer.traceId());
      assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isEqualTo(outer.traceId());
      assertThat(MDC.get(TraceContext.MDC_CHANNEL)).isEqualTo(TraceContext.CHANNEL_SCHEDULER);
    }
  }

  @Test
  @DisplayName("同一平台线程连续两任务_第二个任务看不到第一个的链路")
  void singlePlatformThread_noCrossTalkBetweenTasks() throws Exception {
    Callable<String> readTraceId = TraceContext::traceId;
    Callable<String> readMdcTraceId = () -> MDC.get(TraceContext.MDC_TRACE_ID);

    try (ExecutorService executor = TracedExecutors.wrap(Executors.newSingleThreadExecutor())) {
      String firstTrace;
      try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
        firstTrace = scope.traceId();
        assertThat(executor.submit(readTraceId).get(AWAIT_SECONDS, TimeUnit.SECONDS))
            .isEqualTo(firstTrace);
      }

      assertThat(executor.submit(readTraceId).get(AWAIT_SECONDS, TimeUnit.SECONDS)).isNull();
      assertThat(executor.submit(readMdcTraceId).get(AWAIT_SECONDS, TimeUnit.SECONDS)).isNull();
    }
  }

  @Test
  @DisplayName("包装ExecutorService_submit与invokeAll与invokeAny都传播")
  void wrappedExecutorService_propagatesOnAllSubmitPaths() throws Exception {
    Callable<String> readTraceId = TraceContext::traceId;

    try (ExecutorService executor = TracedExecutors.wrap(Executors.newFixedThreadPool(2));
        TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {

      assertThat(executor.submit(readTraceId).get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isEqualTo(scope.traceId());

      Runnable noOp = () -> {};
      assertThat(executor.submit(noOp, scope.traceId()).get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isEqualTo(scope.traceId());

      List<Future<String>> all = executor.invokeAll(List.of(readTraceId, readTraceId));
      assertThat(all).hasSize(2);
      for (Future<String> future : all) {
        assertThat(future.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(scope.traceId());
      }

      assertThat(executor.invokeAny(List.of(readTraceId, readTraceId))).isEqualTo(scope.traceId());
    }
  }

  @Test
  @DisplayName("wrapRoot_每次运行都开一条新链路并带渠道")
  void wrapRoot_opensFreshTracePerRun() {
    AtomicReference<String> seen = new AtomicReference<>();
    Runnable task =
        TracedExecutors.wrapRoot(
            () -> {
              seen.set(TraceContext.traceId());
              assertThat(MDC.get(TraceContext.MDC_CHANNEL))
                  .isEqualTo(TraceContext.CHANNEL_SCHEDULER);
            },
            TraceContext.CHANNEL_SCHEDULER);

    task.run();
    String first = seen.get();
    task.run();
    String second = seen.get();

    assertThat(TraceIds.isValidTraceId(first)).isTrue();
    assertThat(TraceIds.isValidTraceId(second)).isTrue();
    assertThat(first).isNotEqualTo(second);
    assertThat(TraceContext.traceId()).isNull();
  }

  @Test
  @DisplayName("无上下文时_wrap为直通不产生额外对象")
  void wrap_isPassThroughWithoutContext() {
    Runnable task = () -> {};
    ExecutorService raw = Executors.newSingleThreadExecutor();
    try {
      assertThat(TracedExecutors.wrapRunnable(task)).isSameAs(task);
      assertThat(TracedExecutors.wrap(raw)).isNotNull();
      assertThat(TracedExecutors.wrap((ExecutorService) null)).isNull();
    } finally {
      raw.close();
    }
  }

  @Test
  @DisplayName("重复包装_不会套两层")
  void wrap_isIdempotent() {
    ExecutorService raw = Executors.newSingleThreadExecutor();
    ExecutorService once = TracedExecutors.wrap(raw);
    try {
      assertThat(TracedExecutors.wrap(once)).isSameAs(once);
    } finally {
      once.close();
    }
  }

  @Test
  @DisplayName("任务抛异常_finally仍恢复线程状态")
  void wrappedRunnable_restoresEvenWhenTaskThrows() {
    Runnable wrapped;
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      wrapped =
          TracedExecutors.wrapRunnable(
              () -> {
                throw new IllegalStateException("boom");
              });
    }

    try {
      wrapped.run();
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessage("boom");
    }

    assertThat(TraceContext.traceId()).isNull();
    assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
  }
}
