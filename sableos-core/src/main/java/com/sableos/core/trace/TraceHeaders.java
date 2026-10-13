package com.sableos.core.trace;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 链路标识的头读写，用函数式接口而非 servlet API，因此可被 HTTP 入口、出站 HTTP 客户端与 MCP 客户端共用。
 *
 * <p>入站优先级：{@code traceparent}（W3C）&gt; {@code X-Trace-Id}（便捷头，仅当为合法 32 位小写 hex 时采纳）&gt;
 * 由调用方生成新的根链路。非法值一律降级，绝不因此拒绝请求。
 */
public final class TraceHeaders {

  /** W3C trace-context 标准头。 */
  public static final String TRACEPARENT = "traceparent";

  /** W3C vendor 扩展头，本项目只透传不解析。 */
  public static final String TRACESTATE = "tracestate";

  /** 便捷头：便于人工排障与不支持 W3C 的调用方。 */
  public static final String TRACE_ID = "X-Trace-Id";

  private static final int TRACESTATE_MAX_LENGTH = 512;
  private static final char PRINTABLE_MIN = ' ';
  private static final char PRINTABLE_MAX = '~';

  private TraceHeaders() {}

  /**
   * 从入站头解析链路上下文。
   *
   * @param getter 按名取头的函数（HTTP 侧传 {@code request::getHeader}）
   * @return 解析结果；无合法入站头时返回 {@link Optional#empty()}，调用方应生成新链路
   */
  public static Optional<TraceParent> extract(Function<String, String> getter) {
    if (getter == null) {
      return Optional.empty();
    }
    String tracestate = sanitizeTracestate(getter.apply(TRACESTATE));
    Optional<TraceParent> parsed = TraceParent.parse(getter.apply(TRACEPARENT));
    if (parsed.isPresent()) {
      return Optional.of(parsed.get().withTracestate(tracestate));
    }
    String legacyTraceId = getter.apply(TRACE_ID);
    if (TraceIds.isValidTraceId(legacyTraceId)) {
      return Optional.of(
          new TraceParent(TraceParent.VERSION, legacyTraceId, TraceIds.newSpanId(), true, null));
    }
    return Optional.empty();
  }

  /**
   * 把链路上下文注入出站头（响应头或下游请求头）。
   *
   * @param parent 当前链路，可为 null（此时不做任何事）
   * @param setter 按名写头的消费者（HTTP 侧传 {@code response::setHeader}）
   */
  public static void inject(TraceParent parent, BiConsumer<String, String> setter) {
    if (parent == null || setter == null) {
      return;
    }
    setter.accept(TRACE_ID, parent.traceId());
    setter.accept(TRACEPARENT, parent.toHeaderValue());
    String tracestate = parent.tracestate();
    if (tracestate != null) {
      setter.accept(TRACESTATE, tracestate);
    }
  }

  /**
   * tracestate 是自由文本且会被原样回写进响应头，必须防响应头注入：含任何控制字符（CR / LF 等）或非 ASCII 可打印字符即整体丢弃； 超过 W3C 上限 512
   * 也丢弃。宁可丢一个 vendor 扩展值，也不给头注入留口子。
   */
  static String sanitizeTracestate(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty() || trimmed.length() > TRACESTATE_MAX_LENGTH) {
      return null;
    }
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c < PRINTABLE_MIN || c > PRINTABLE_MAX) {
        return null;
      }
    }
    return trimmed;
  }
}
