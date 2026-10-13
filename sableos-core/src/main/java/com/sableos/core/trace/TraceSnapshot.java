package com.sableos.core.trace;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 跨线程传播用的不可变链路快照。
 *
 * <p>为什么必须显式传播：已用字节码核验 logback 1.5.34 的 {@code LogbackMDCAdapter} 内部是普通 {@code ThreadLocal} （非
 * {@code InheritableThreadLocal}，1.5.x 已移除 {@code copyOnInheritThreadLocal}），因此子线程——包括虚拟线程—— 的 MDC
 * 必然为空。统一姿势是「提交时捕获 → 运行时安装 → finally 恢复目标线程原状态」。
 *
 * <p>恢复而非清空是刻意的：被包装的任务可能跑在**平台线程池**上，该线程可能已有自己的上下文，无条件清空会破坏它； 在虚拟线程上「恢复为 null」与「清空」等价。
 *
 * @param traceId 32 位小写 hex，为 null 表示空快照
 * @param spanId 16 位小写 hex
 * @param sampled 采样标志
 * @param tracestate 只透传的上游值，可为 null
 * @param business 业务上下文（sessionId / profileName / channel），恒为不可变 Map
 */
public record TraceSnapshot(
    String traceId,
    String spanId,
    boolean sampled,
    String tracestate,
    Map<String, String> business) {

  /** 空快照：无链路上下文时的返回值，包装任务时直接放行不做任何事。 */
  public static final TraceSnapshot EMPTY = new TraceSnapshot(null, null, false, null, Map.of());

  /** 紧凑构造器：把业务上下文固化成不可变副本，避免共享可变 Map（也因此全链路无需加锁）。 */
  public TraceSnapshot {
    business = (business == null || business.isEmpty()) ? Map.of() : Map.copyOf(business);
  }

  /** 是否为空快照（无 traceId）。 */
  public boolean isEmpty() {
    return traceId == null;
  }

  /** 保留同一 trace 与业务上下文，换一个 span-id。 */
  public TraceSnapshot withSpan(String newSpanId) {
    return new TraceSnapshot(traceId, newSpanId, sampled, tracestate, business);
  }

  /** 合并业务上下文（null 值忽略），返回新快照。 */
  public TraceSnapshot withBusiness(Map<String, String> additional) {
    if (additional == null || additional.isEmpty()) {
      return this;
    }
    return new TraceSnapshot(
        traceId, spanId, sampled, tracestate, TraceContext.merge(business, additional));
  }

  /** 转为可注入出站请求的 traceparent 载体；空快照返回 null。 */
  public TraceParent toTraceParent() {
    if (isEmpty()) {
      return null;
    }
    return new TraceParent(TraceParent.VERSION, traceId, spanId, sampled, tracestate);
  }

  /**
   * 包装任务：执行前把本快照安装到目标线程，执行后恢复目标线程原状态。空快照原样返回，零开销。
   *
   * <p>命名刻意区分 {@code wrapRunnable} / {@code wrapCallable} 而不做重载：一个「无法正常结束」的块 lambda（如末尾 {@code
   * throw}）同时是 void-兼容与 value-兼容的，重载会让 javac 选中 Callable 版本并报类型推断错误。
   */
  public Runnable wrapRunnable(Runnable task) {
    if (isEmpty()) {
      return task;
    }
    return () -> {
      // span 在**运行时**递进而非包装时：同一个被包装任务反复执行（如定时触发）时，每次都是独立的一段
      TraceSnapshot previous = TraceContext.install(withSpan(TraceIds.newSpanId()));
      try {
        task.run();
      } finally {
        TraceContext.restore(previous);
      }
    };
  }

  /** 同 {@link #wrapRunnable(Runnable)}，用于 {@code submit} / {@code invokeAll} 等接受 Callable 的场景。 */
  public <T> Callable<T> wrapCallable(Callable<T> task) {
    if (isEmpty()) {
      return task;
    }
    return () -> {
      TraceSnapshot previous = TraceContext.install(withSpan(TraceIds.newSpanId()));
      try {
        return task.call();
      } finally {
        TraceContext.restore(previous);
      }
    };
  }

  /**
   * 在当前线程安装本快照（span 递进为新的一段）并返回作用域，用于**无法装饰线程**的场景（如 MCP SDK 的回调线程、子进程 IO 线程）：在提交处 {@code
   * TraceContext.snapshot()} 捕获，在回调里 try-with-resources 打开本方法。
   */
  public TraceContext.Scope openInCurrentThread() {
    return TraceContext.openSnapshot(isEmpty() ? this : withSpan(TraceIds.newSpanId()));
  }
}
