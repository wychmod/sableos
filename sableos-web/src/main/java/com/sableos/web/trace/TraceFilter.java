package com.sableos.web.trace;

import com.sableos.core.trace.TraceContext;
import com.sableos.core.trace.TraceHeaders;
import com.sableos.core.trace.TraceParent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP 入口的链路标识过滤器（FR-046、契约「链路标识」小节）。
 *
 * <p>每个请求一个虚拟线程，这里开一个链路作用域：入站 {@code traceparent} 合法则沿用其 trace-id 并生成新的本地 span-id，否则新生成；出站回写 {@code
 * X-Trace-Id} 与（可选）{@code traceparent} / {@code tracestate}。
 *
 * <p><b>为什么是 Filter 而不是 {@code HandlerInterceptor}</b>：① 拦截器在 DispatcherServlet 内部，静态资源、404、 {@code
 * /actuator/**}、{@code /swagger-ui/**} 这些不经 handler mapping 的路径覆盖不到；② {@code afterCompletion}
 * 可能在响应已提交后执行，{@code setHeader} 会静默失效；③ filter chain 中抛出的容器级 IOException 拦截器完全看不见。 本过滤器在 {@code
 * chain.doFilter} **之前**写头，此时响应必然未提交。
 *
 * <p><b>不做路径排除</b>：MDC 写入本身不产日志，成本可忽略；actuator 带上 traceId 反而便于与指标对齐。
 */
public class TraceFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(TraceFilter.class);

  /** 入站头写入日志时的长度上限，防止超长头刷爆日志。 */
  private static final int MAX_LOGGED_HEADER_LENGTH = 200;

  private static final String ELLIPSIS = "...";

  private final TraceProperties properties;

  public TraceFilter(TraceProperties properties) {
    this.properties = (properties == null) ? TraceProperties.defaults() : properties;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    warnIfTraceparentUnusable(request);
    TraceParent inbound = TraceHeaders.extract(request::getHeader).orElse(null);
    try (TraceContext.Scope scope = TraceContext.open(inbound, TraceContext.CHANNEL_WEB)) {
      writeResponseHeaders(response, scope);
      chain.doFilter(request, response);
    }
  }

  /** 头必须在 {@code doFilter} 之前写：响应一旦提交，{@code setHeader} 就是静默失效。 */
  private void writeResponseHeaders(HttpServletResponse response, TraceContext.Scope scope) {
    if (!properties.enabled() || response.isCommitted()) {
      return;
    }
    response.setHeader(properties.responseHeader(), scope.traceId());
    if (!properties.propagateW3c()) {
      return;
    }
    TraceParent parent = TraceContext.currentParent();
    if (parent == null) {
      return;
    }
    response.setHeader(TraceHeaders.TRACEPARENT, parent.toHeaderValue());
    if (parent.tracestate() != null) {
      response.setHeader(TraceHeaders.TRACESTATE, parent.tracestate());
    }
  }

  /** 非法 {@code traceparent} 只降级、不报错（请求照常处理），但要在 debug 档留下可定位线索。 */
  private void warnIfTraceparentUnusable(HttpServletRequest request) {
    String raw = request.getHeader(TraceHeaders.TRACEPARENT);
    if (raw != null && TraceParent.parse(raw).isEmpty()) {
      LOG.debug("Ignoring invalid traceparent header, starting a new trace: {}", toLogSafe(raw));
    }
  }

  /**
   * 用户可控字符串入日志前必须净化换行（CRLF 注入）。
   *
   * <p>净化必须保持 {@code replaceAll} 这个字面形态且是**最后一步**——findsecbugs 只认这种写法，换成 {@code Pattern}/{@code
   * Matcher} 或在净化后再做截断都会被判违规（AGENTS.md §5.3）。
   */
  private static String toLogSafe(String value) {
    String truncated =
        (value.length() > MAX_LOGGED_HEADER_LENGTH)
            ? value.substring(0, MAX_LOGGED_HEADER_LENGTH) + ELLIPSIS
            : value;
    return truncated.replaceAll("[\r\n]", "_");
  }
}
