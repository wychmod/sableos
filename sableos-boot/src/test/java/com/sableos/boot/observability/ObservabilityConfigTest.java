package com.sableos.boot.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sableos.core.trace.TraceContext;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;

/**
 * 装配层验收。
 *
 * <p>其中「只有一个 TaskDecorator bean」这条是**专治静默故障**的：Boot 的 {@code SimpleAsyncTaskExecutorBuilder} 用
 * {@code getIfUnique()} 取装饰器，注册两个及以上会静默返回 null——链路不传播且不报错，只能靠断言提前钉住。
 */
class ObservabilityConfigTest {

  private static final String PROBE_LOGGER = "com.sableos.test.probe";
  private static final long AWAIT_SECONDS = 10L;

  private static final Logger PROBE = (Logger) LoggerFactory.getLogger(PROBE_LOGGER);

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
          .withUserConfiguration(ObservabilityConfig.class);

  @AfterEach
  void tearDown() {
    TraceContext.clear();
  }

  @Test
  @DisplayName("全容器只有一个TaskDecorator_bean")
  void registersExactlyOneTaskDecorator() {
    runner.run(
        context -> {
          assertThat(context.getBeanNamesForType(TaskDecorator.class)).hasSize(1);
          assertThat(context.getBean(TaskDecorator.class)).isInstanceOf(TraceTaskDecorator.class);
        });
  }

  @Test
  @DisplayName("虚拟线程开启时_装饰器确实挂到applicationTaskExecutor上")
  void decoratorIsAppliedToVirtualThreadExecutor() {
    runner
        .withPropertyValues("spring.threads.virtual.enabled=true")
        .run(
            context -> {
              AsyncTaskExecutor executor =
                  context.getBean("applicationTaskExecutor", AsyncTaskExecutor.class);
              assertThat(executor).isInstanceOf(SimpleAsyncTaskExecutor.class);

              AtomicReference<String> childTrace = new AtomicReference<>();
              CountDownLatch done = new CountDownLatch(1);
              try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
                executor.execute(
                    () -> {
                      childTrace.set(TraceContext.traceId());
                      done.countDown();
                    });
                assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                assertThat(childTrace.get()).isEqualTo(scope.traceId());
              }
            });
  }

  /**
   * 端到端断言：后台虚拟线程打的日志，其 MDC 五个键都在、traceId 与主线程同值、spanId 递进。
   *
   * <p>这条同时守住了 prod 档最容易静默出错的点——{@code LogstashEncoder} 的 {@code includeMdcKeyName} 是白名单， 漏一个键就在
   * JSON 里消失且 dev 档看不出来。
   */
  @Test
  @DisplayName("后台线程日志的MDC五键齐全且trace同值span递进")
  void childThreadLogsCarryAllFiveMdcKeys() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    PROBE.addAppender(appender);
    try {
      runner
          .withPropertyValues("spring.threads.virtual.enabled=true")
          .run(
              context -> {
                AsyncTaskExecutor executor =
                    context.getBean("applicationTaskExecutor", AsyncTaskExecutor.class);
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<String> parentTrace = new AtomicReference<>();
                AtomicReference<String> parentSpan = new AtomicReference<>();

                try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_WEB);
                    TraceContext.Scope ignored =
                        TraceContext.attachBusiness("sess-1", "daily-weather", null)) {
                  parentTrace.set(scope.traceId());
                  parentSpan.set(TraceContext.spanId());
                  executor.execute(
                      () -> {
                        LoggerFactory.getLogger(PROBE_LOGGER).info("probe from child thread");
                        done.countDown();
                      });
                  assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                }

                assertThat(appender.list).hasSize(1);
                Map<String, String> mdc = appender.list.get(0).getMDCPropertyMap();
                assertThat(mdc)
                    .containsKeys(
                        TraceContext.MDC_TRACE_ID,
                        TraceContext.MDC_SPAN_ID,
                        TraceContext.MDC_SESSION_ID,
                        TraceContext.MDC_PROFILE_NAME,
                        TraceContext.MDC_CHANNEL);
                assertThat(mdc)
                    .containsEntry(TraceContext.MDC_TRACE_ID, parentTrace.get())
                    .containsEntry(TraceContext.MDC_SESSION_ID, "sess-1")
                    .containsEntry(TraceContext.MDC_PROFILE_NAME, "daily-weather")
                    .containsEntry(TraceContext.MDC_CHANNEL, TraceContext.CHANNEL_WEB);
                assertThat(mdc.get(TraceContext.MDC_SPAN_ID)).isNotEqualTo(parentSpan.get());
              });
    } finally {
      PROBE.detachAppender(appender);
      appender.stop();
    }
  }
}
