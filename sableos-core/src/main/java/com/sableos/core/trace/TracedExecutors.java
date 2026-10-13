package com.sableos.core.trace;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/**
 * 把当前链路上下文带到异步任务的工具入口（FR-046）。
 *
 * <p>选用哪种包装：
 *
 * <ul>
 *   <li>Spring 的 {@code @Async} 与 {@code applicationTaskExecutor}：由容器里**唯一**的 {@code TaskDecorator}
 *       bean 覆盖 （Boot 的 {@code SimpleAsyncTaskExecutorBuilder} 用 {@code getIfUnique()} 取
 *       decorator，注册多个会静默失效）
 *   <li>自建 {@link ExecutorService}（含 {@code newVirtualThreadPerTaskExecutor()}）：用 {@link
 *       #wrap(ExecutorService)} 整体装饰，一次覆盖 execute / submit / invokeAll / invokeAny
 *   <li>{@code ThreadPoolTaskScheduler}：该类没有 {@code setTaskDecorator}，注册 trigger 前用 {@link
 *       #wrapRoot(Runnable, String)} 包装——每次触发是一个新链路
 *   <li>零散 {@code Runnable} / {@code Callable}：用 {@link #wrapRunnable(Runnable)} / {@link
 *       #wrapCallable(Callable)}
 *   <li>{@code CompletableFuture.xxxAsync} 不传 executor：**禁止**（走 {@code
 *       ForkJoinPool.commonPool}，共享平台线程且无法装饰）； 必须传入被包装的 executor
 *   <li>{@code parallelStream()}：不支持传播（fork/join 分割语义下同一段会被多个工作线程共享），需要链路的路径改显式 executor
 *   <li>线程由第三方 SDK 拥有（MCP 回调、子进程 IO）：提交处 {@link TraceContext#snapshot()} 捕获，回调里 {@link
 *       TraceSnapshot#openInCurrentThread()}
 * </ul>
 */
public final class TracedExecutors {

  private TracedExecutors() {}

  /** 装饰一个 {@link ExecutorService}，使其所有提交入口都自动传播提交时刻的链路上下文。重复包装直接返回原对象。 */
  public static ExecutorService wrap(ExecutorService delegate) {
    if (delegate == null || delegate instanceof TracedExecutorService) {
      return delegate;
    }
    return new TracedExecutorService(delegate);
  }

  /** 包装单个任务：捕获**调用时刻**的链路快照，执行时安装、结束后恢复目标线程原状态。 */
  public static Runnable wrapRunnable(Runnable task) {
    return TraceContext.snapshot().wrapRunnable(task);
  }

  /**
   * 同 {@link #wrapRunnable(Runnable)}，用于 {@code Callable}。
   *
   * <p>与 {@code wrapRunnable} 分开命名而非重载：一个「无法正常结束」的块 lambda（如末尾 {@code throw}）同时是 void-兼容与
   * value-兼容的，重载会让 javac 选中 Callable 版本并报类型推断错误。
   */
  public static <T> Callable<T> wrapCallable(Callable<T> task) {
    return TraceContext.snapshot().wrapCallable(task);
  }

  /**
   * 包装成「每次运行都开一个全新根链路」的任务，用于定时触发这类没有上游链路的入口。
   *
   * <p>注意：定时任务的**跳过分支**（上次未结束）必须单独开一个短链路记 WARN 后丢弃，绝不能复用上次执行的 traceId， 否则两次触发会被误绑成同一条链。
   *
   * @param task 原任务
   * @param channel 渠道标记，定时触发传 {@link TraceContext#CHANNEL_SCHEDULER}
   */
  public static Runnable wrapRoot(Runnable task, String channel) {
    return () -> {
      try (TraceContext.Scope scope = TraceContext.openRoot(channel)) {
        task.run();
      }
    };
  }
}
