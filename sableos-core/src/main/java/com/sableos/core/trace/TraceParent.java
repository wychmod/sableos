package com.sableos.core.trace;

import java.util.Optional;

/**
 * W3C trace-context 的 {@code traceparent} 头，外加只透传的 {@code tracestate}。
 *
 * <p>解析采取「严格校验、非法即降级」策略：任何不合规的入站头都返回 {@link Optional#empty()}，由调用方生成新的根链路， 绝不因为一个坏头把请求判为 400。
 *
 * <p>{@code tracestate} 只透传、不解析、不生成——SableOS 无自有 vendor key 也无消费方，解析需处理 32 条上限、OWS
 * 折叠与优先级重排，成本高收益零（见 research.md D-14）。
 *
 * @param version 版本号，当前恒为 {@value #VERSION}
 * @param traceId 32 位小写 hex 的链路 ID
 * @param spanId 16 位小写 hex 的本段 ID
 * @param sampled 上游采样标志；本地新生成恒为 true
 * @param tracestate 原样透传的上游值，可为 null
 */
public record TraceParent(
    String version, String traceId, String spanId, boolean sampled, String tracestate) {

  /** W3C 当前版本号。 */
  public static final String VERSION = "00";

  private static final String INVALID_VERSION = "ff";
  private static final String FIELD_SEPARATOR = "-";
  private static final int FIELD_COUNT = 4;
  private static final int VERSION_LENGTH = 2;
  private static final int FLAGS_LENGTH = 2;
  private static final int FLAGS_RADIX = 16;
  private static final int FLAGS_SAMPLED_MASK = 0x01;
  private static final String FLAGS_SAMPLED = "01";
  private static final String FLAGS_NOT_SAMPLED = "00";

  /** 生成全新的根链路：新 trace-id + 新 span-id，sampled 为 true，无 tracestate。 */
  public static TraceParent newRoot() {
    return new TraceParent(VERSION, TraceIds.newTraceId(), TraceIds.newSpanId(), true, null);
  }

  /**
   * 解析 {@code traceparent} 头。
   *
   * <p>拒绝的情形：null / 空串、字段数不足 4、version 非 2 位小写 hex 或为 {@code ff}、version 为 {@code 00} 时字段数不等于
   * 4、trace-id 与 span-id 非法、flags 非 2 位小写 hex。version 高于 {@code 00} 时允许字段更多，只取前 4 段（W3C 的前向兼容规则）。
   *
   * @param header 入站头原值，可为 null
   * @return 合法则返回解析结果，否则 {@link Optional#empty()}
   */
  public static Optional<TraceParent> parse(String header) {
    if (header == null) {
      return Optional.empty();
    }
    String value = header.trim();
    if (value.isEmpty()) {
      return Optional.empty();
    }
    String[] fields = value.split(FIELD_SEPARATOR);
    if (fields.length < FIELD_COUNT) {
      return Optional.empty();
    }
    String version = fields[0];
    if (!TraceIds.isLowerHex(version, VERSION_LENGTH) || INVALID_VERSION.equals(version)) {
      return Optional.empty();
    }
    if (VERSION.equals(version) && fields.length != FIELD_COUNT) {
      return Optional.empty();
    }
    String traceId = fields[1];
    String spanId = fields[2];
    String flags = fields[3];
    if (!TraceIds.isValidTraceId(traceId)
        || !TraceIds.isValidSpanId(spanId)
        || !TraceIds.isLowerHex(flags, FLAGS_LENGTH)) {
      return Optional.empty();
    }
    boolean sampled = (Integer.parseInt(flags, FLAGS_RADIX) & FLAGS_SAMPLED_MASK) != 0;
    return Optional.of(new TraceParent(version, traceId, spanId, sampled, null));
  }

  /** 保留同一 trace-id，换一个新 span-id（用于「接续上游链路、本段是新的一段」）。 */
  public TraceParent withNewSpan() {
    return new TraceParent(version, traceId, TraceIds.newSpanId(), sampled, tracestate);
  }

  /** 附加（或覆盖）只透传的 tracestate。 */
  public TraceParent withTracestate(String value) {
    return new TraceParent(version, traceId, spanId, sampled, value);
  }

  /** 序列化为 {@code traceparent} 头值，形如 {@code 00-<trace-id>-<span-id>-01}。 */
  public String toHeaderValue() {
    return version
        + FIELD_SEPARATOR
        + traceId
        + FIELD_SEPARATOR
        + spanId
        + FIELD_SEPARATOR
        + (sampled ? FLAGS_SAMPLED : FLAGS_NOT_SAMPLED);
  }
}
