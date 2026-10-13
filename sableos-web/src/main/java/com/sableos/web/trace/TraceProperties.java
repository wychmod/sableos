package com.sableos.web.trace;

import com.sableos.core.trace.TraceHeaders;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 链路标识的对外行为配置（{@code sableos.observability.trace.*}）。
 *
 * <p>三个键都有真实用途：{@code enabled} 供极端场景整体关停响应头回写；{@code responseHeader} 应对网关改写头名； {@code propagateW3c}
 * 允许只暴露便捷头而不发 {@code traceparent}。
 *
 * @param enabled 是否回写链路响应头（MDC 与信封字段不受此开关影响）
 * @param responseHeader 便捷响应头名，缺省 {@code X-Trace-Id}
 * @param propagateW3c 是否同时回写 W3C 的 {@code traceparent} 与 {@code tracestate}
 */
@ConfigurationProperties(prefix = "sableos.observability.trace")
public record TraceProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue(TraceHeaders.TRACE_ID) String responseHeader,
    @DefaultValue("true") boolean propagateW3c) {

  /** 全默认配置，供测试与非 Spring 环境直接构造。 */
  public static TraceProperties defaults() {
    return new TraceProperties(true, TraceHeaders.TRACE_ID, true);
  }
}
