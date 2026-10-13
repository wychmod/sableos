package com.sableos.core.trace;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * 链路标识的线程上下文门面（FR-037 / FR-046）。
 *
 * <p>一次调用一个 traceId，贯穿结构化日志（MDC）、响应头、响应信封与审计三表的 {@code trace_id} 列。四类入口 （HTTP / CLI / 定时 /
 * 异步）都从这里开作用域。
 *
 * <p><b>使用纪律</b>：入口 {@code open*}、出口 {@code finally} 关闭，一律用 try-with-resources。虚拟线程每任务新建天然不串号，
 * 但平台线程池会复用线程，漏关就会串号。
 *
 * <p><b>实现约束</b>：全链路无锁——只用 {@code ThreadLocal} 与不可变 Map 的整体替换。JDK 21 下在 {@code synchronized}
 * 里阻塞会钉住载体线程（AGENTS.md §5.2），本类处于所有请求的热路径上，绝不能引入锁。
 *
 * <p><b>MDC 只投影 5 个键</b>：内部状态存在 {@link TraceSnapshot}（因为出站注入需要 sampled / tracestate 这类结构信息， MDC
 * 存不下），MDC 仅作为日志投影。清理时只移除本类管理的 5 个键，不碰其他代码放进 MDC 的内容。
 */
public final class TraceContext {

  /** MDC 键：链路 ID，与 logback 预埋的 {@code %X{traceId}} 对齐。 */
  public static final String MDC_TRACE_ID = "traceId";

  /** MDC 键：本段 ID。 */
  public static final String MDC_SPAN_ID = "spanId";

  /** MDC 键：会话 ID（排障第二主键）。 */
  public static final String MDC_SESSION_ID = "sessionId";

  /** MDC 键：Agent 运行配置名。 */
  public static final String MDC_PROFILE_NAME = "profileName";

  /** MDC 键：触发渠道（web / cli / scheduler）。 */
  public static final String MDC_CHANNEL = "channel";

  /** 渠道取值：HTTP 入口。 */
  public static final String CHANNEL_WEB = "web";

  /** 渠道取值：命令行交互入口。 */
  public static final String CHANNEL_CLI = "cli";

  /** 渠道取值：定时触发（与 data-model 中 sessions.user_id 的 {@code scheduler} 口径一致）。 */
  public static final String CHANNEL_SCHEDULER = "scheduler";

  private static final String[] MDC_KEYS = {
    MDC_TRACE_ID, MDC_SPAN_ID, MDC_SESSION_ID, MDC_PROFILE_NAME, MDC_CHANNEL
  };

  private static final ThreadLocal<TraceSnapshot> CURRENT = new ThreadLocal<>();

  private TraceContext() {}

  /**
   * 开一个全新的根链路。
   *
   * @param channel 触发渠道（{@link #CHANNEL_WEB} / {@link #CHANNEL_CLI} / {@link
   *     #CHANNEL_SCHEDULER}），可为 null
   */
  public static Scope openRoot(String channel) {
    TraceParent root = TraceParent.newRoot();
    return push(
        new TraceSnapshot(
            root.traceId(), root.spanId(), true, null, business(null, null, channel)));
  }

  /**
   * 以入站 {@code traceparent} 为上游开一段新链路：沿用其 trace-id，本地生成新 span-id。
   *
   * @param inbound 入站解析结果，为 null 时等价于 {@link #openRoot(String)}（非法头一律降级，不报错）
   * @param channel 触发渠道，可为 null
   */
  public static Scope open(TraceParent inbound, String channel) {
    TraceParent parent = (inbound == null) ? TraceParent.newRoot() : inbound.withNewSpan();
    return push(
        new TraceSnapshot(
            parent.traceId(),
            parent.spanId(),
            parent.sampled(),
            parent.tracestate(),
            business(null, null, channel)));
  }

  /**
   * 兜底入口：当前线程已有链路则原样复用（嵌套安全，close 不影响外层），否则新开根链路。
   *
   * <p>典型用法是内层服务（如 {@code AgentService.process}）——它既可能被已开作用域的 HTTP 入口调用，也可能被 CLI / 定时直接调用。
   */
  public static Scope openIfAbsent() {
    TraceSnapshot current = CURRENT.get();
    if (current != null && !current.isEmpty()) {
      return new Scope(current, current);
    }
    return openRoot(null);
  }

  /** 在同一 trace 内递进一个新的 span-id（用于工具执行段、循环迭代段等）；无上下文时退化为开根链路。 */
  public static Scope openSpan() {
    TraceSnapshot current = CURRENT.get();
    if (current == null || current.isEmpty()) {
      return openRoot(null);
    }
    return push(current.withSpan(TraceIds.newSpanId()));
  }

  /**
   * 附加业务上下文（null 与空白值忽略），close 时恢复外层上下文。
   *
   * <p>由 {@code AgentService} 在放入 {@code ProfileContext} 的同一处调用，并在同一个 {@code finally} 里关闭。
   *
   * <p><b>userId 不在其列</b>：它可由 HTTP 请求参数任意指定，入 MDC 等于让外部污染日志字段；且已落 {@code sessions} 表，凭 traceId 一次
   * join 即可反查（见 research.md D-14）。
   */
  public static Scope attachBusiness(String sessionId, String profileName, String channel) {
    Map<String, String> additional = business(sessionId, profileName, channel);
    TraceSnapshot current = CURRENT.get();
    if (current == null || current.isEmpty()) {
      TraceParent root = TraceParent.newRoot();
      return push(new TraceSnapshot(root.traceId(), root.spanId(), true, null, additional));
    }
    return push(current.withBusiness(additional));
  }

  /** 当前 trace-id；无上下文返回 null。 */
  public static String traceId() {
    TraceSnapshot current = CURRENT.get();
    return (current == null) ? null : current.traceId();
  }

  /** 当前 span-id；无上下文返回 null。 */
  public static String spanId() {
    TraceSnapshot current = CURRENT.get();
    return (current == null) ? null : current.spanId();
  }

  /** 当前业务上下文值；无上下文或键不存在返回 null。 */
  public static String business(String key) {
    TraceSnapshot current = CURRENT.get();
    return (current == null) ? null : current.business().get(key);
  }

  /** 供出站注入使用的 traceparent 载体；无上下文返回 null。 */
  public static TraceParent currentParent() {
    TraceSnapshot current = CURRENT.get();
    return (current == null) ? null : current.toTraceParent();
  }

  /** 捕获当前状态用于跨线程传播；无上下文返回 {@link TraceSnapshot#EMPTY}（其 wrap 为直通）。 */
  public static TraceSnapshot snapshot() {
    TraceSnapshot current = CURRENT.get();
    return (current == null) ? TraceSnapshot.EMPTY : current;
  }

  /**
   * 彻底清理当前线程的链路上下文（ThreadLocal 与本类管理的那 5 个 MDC 键）。
   *
   * <p>正常路径不需要它——{@link Scope#close()} 已经收口。它面向两类场景：入口代码在 try-with-resources 之外兜底清理， 以及测试收尾防止用例间串味。
   */
  public static void clear() {
    restore(null);
  }

  /** 供 {@link TraceSnapshot#openInCurrentThread()} 使用：安装给定快照并返回作用域。 */
  static Scope openSnapshot(TraceSnapshot snapshot) {
    if (snapshot == null || snapshot.isEmpty()) {
      return new Scope(TraceSnapshot.EMPTY, CURRENT.get());
    }
    return new Scope(snapshot, install(snapshot));
  }

  /** 安装快照到当前线程（ThreadLocal + MDC 双写），返回安装前的状态供 finally 恢复。 */
  static TraceSnapshot install(TraceSnapshot next) {
    TraceSnapshot previous = CURRENT.get();
    CURRENT.set(next);
    writeMdc(next);
    return previous;
  }

  /** 恢复到给定状态；previous 为 null 表示彻底清理（虚拟线程与一次性入口的正常路径）。 */
  static void restore(TraceSnapshot previous) {
    if (previous == null) {
      CURRENT.remove();
      clearMdc();
    } else {
      CURRENT.set(previous);
      writeMdc(previous);
    }
  }

  /** 合并业务上下文，忽略 null 与空白值，返回不可变 Map。 */
  static Map<String, String> merge(Map<String, String> base, Map<String, String> additional) {
    Map<String, String> merged = new HashMap<>(base);
    additional.forEach(
        (key, value) -> {
          if (value != null && !value.isBlank()) {
            merged.put(key, value);
          }
        });
    return Map.copyOf(merged);
  }

  private static Scope push(TraceSnapshot next) {
    return new Scope(next, install(next));
  }

  private static Map<String, String> business(
      String sessionId, String profileName, String channel) {
    Map<String, String> values = new HashMap<>(MDC_KEYS.length);
    putIfPresent(values, MDC_SESSION_ID, sessionId);
    putIfPresent(values, MDC_PROFILE_NAME, profileName);
    putIfPresent(values, MDC_CHANNEL, channel);
    return Map.copyOf(values);
  }

  private static void putIfPresent(Map<String, String> target, String key, String value) {
    if (value != null && !value.isBlank()) {
      target.put(key, value);
    }
  }

  private static void writeMdc(TraceSnapshot snapshot) {
    clearMdc();
    putMdc(MDC_TRACE_ID, snapshot.traceId());
    putMdc(MDC_SPAN_ID, snapshot.spanId());
    snapshot.business().forEach(TraceContext::putMdc);
  }

  private static void clearMdc() {
    for (String key : MDC_KEYS) {
      MDC.remove(key);
    }
  }

  private static void putMdc(String key, String value) {
    if (value != null) {
      MDC.put(key, value);
    }
  }

  /**
   * 链路作用域：close 时把线程恢复到打开前的状态。
   *
   * <p>必须用 try-with-resources 或 finally 关闭。虚拟线程每任务新建，不关只是浪费；平台线程池复用线程，不关就会串号。
   */
  public static final class Scope implements AutoCloseable {

    private final TraceSnapshot current;
    private final TraceSnapshot previous;

    private Scope(TraceSnapshot current, TraceSnapshot previous) {
      this.current = current;
      this.previous = previous;
    }

    /** 本作用域的 trace-id。 */
    public String traceId() {
      return current.traceId();
    }

    /** 本作用域的 span-id。 */
    public String spanId() {
      return current.spanId();
    }

    /** 是否由本作用域创建链路（false 表示复用外层，close 只是恢复外层状态）。 */
    public boolean isOwner() {
      return previous == null || previous.isEmpty();
    }

    /** 可直接写入响应头或出站请求的 {@code traceparent} 值。 */
    public String traceparentHeader() {
      return current.toTraceParent().toHeaderValue();
    }

    @Override
    public void close() {
      restore(previous);
    }
  }
}
