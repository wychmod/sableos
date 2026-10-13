package com.sableos.boot.observability;

import com.sableos.core.trace.TraceContext;
import org.springframework.core.task.TaskDecorator;

/**
 * 把提交时刻的链路上下文带进 Spring 管理的异步执行器（FR-046）。
 *
 * <p>覆盖范围：{@code applicationTaskExecutor}、{@code @Async}、以及任何显式挂了本装饰器的 {@code
 * ThreadPoolTaskExecutor}。Boot 开启虚拟线程时 {@code applicationTaskExecutor} 是 {@code
 * SimpleAsyncTaskExecutor}， 其 builder 会无条件调用 {@code taskDecorator.getIfUnique()}，因此本装饰器在虚拟线程路径同样生效。
 *
 * <p>覆盖不到的路径（{@code ThreadPoolTaskScheduler}、自建 executor、{@code CompletableFuture} 默认池、SDK 回调线程）见
 * {@code TracedExecutors} 的类注释。
 */
public class TraceTaskDecorator implements TaskDecorator {

  @Override
  public Runnable decorate(Runnable runnable) {
    return TraceContext.snapshot().wrapRunnable(runnable);
  }
}
