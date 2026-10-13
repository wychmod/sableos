package com.sableos.web.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sableos.core.trace.TraceContext;
import com.sableos.core.trace.TraceHeaders;
import com.sableos.web.GlobalExceptionHandler;
import com.sableos.web.common.ApiResponse;
import com.sableos.web.error.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 契约「链路标识」小节与 C-08 ~ C-10 的验收：响应头与信封同值、合法 traceparent 被沿用、非法值降级且请求仍成功。
 *
 * <p>MockMvc standaloneSetup 与过滤器在同一线程执行，因此还能顺带验证「请求结束后当前线程无 MDC 残留」。
 */
class TraceFilterTest {

  private static final String TRACE_ID_HEX_32 = "[0-9a-f]{32}";
  private static final String UPSTREAM_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String UPSTREAM_SPAN_ID = "00f067aa0ba902b7";
  private static final String UPSTREAM_TRACEPARENT =
      "00-" + UPSTREAM_TRACE_ID + "-" + UPSTREAM_SPAN_ID + "-01";

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = build(TraceProperties.defaults());
  }

  @AfterEach
  void tearDown() {
    TraceContext.clear();
  }

  private static MockMvc build(TraceProperties properties) {
    return MockMvcBuilders.standaloneSetup(new TraceProbeController())
        .addFilters(new TraceFilter(properties))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  @DisplayName("C08_响应头与信封traceId同值且为32位hex")
  void responseHeaderAndEnvelopeCarrySameTraceId() throws Exception {
    MvcResult result =
        mvc.perform(get("/probe/ok"))
            .andExpect(status().isOk())
            .andExpect(header().string(TraceHeaders.TRACE_ID, matchesPattern(TRACE_ID_HEX_32)))
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();

    String headerTraceId = result.getResponse().getHeader(TraceHeaders.TRACE_ID);
    String envelopeTraceId = readTraceId(result);
    assertThat(envelopeTraceId).isEqualTo(headerTraceId);
  }

  @Test
  @DisplayName("C08_出站traceparent与本地span一致")
  void responseCarriesTraceparent() throws Exception {
    MvcResult result =
        mvc.perform(get("/probe/ok"))
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string(
                        TraceHeaders.TRACEPARENT,
                        matchesPattern("00-[0-9a-f]{32}-[0-9a-f]{16}-01")))
            .andReturn();

    String traceparent = result.getResponse().getHeader(TraceHeaders.TRACEPARENT);
    assertThat(traceparent).contains(result.getResponse().getHeader(TraceHeaders.TRACE_ID));
  }

  @Test
  @DisplayName("C09_入站合法traceparent被沿用且span递进")
  void inboundTraceparentIsContinued() throws Exception {
    MvcResult result =
        mvc.perform(get("/probe/ok").header(TraceHeaders.TRACEPARENT, UPSTREAM_TRACEPARENT))
            .andExpect(status().isOk())
            .andExpect(header().string(TraceHeaders.TRACE_ID, UPSTREAM_TRACE_ID))
            .andReturn();

    String traceparent = result.getResponse().getHeader(TraceHeaders.TRACEPARENT);
    assertThat(traceparent).startsWith("00-" + UPSTREAM_TRACE_ID + "-").endsWith("-01");
    assertThat(traceparent).isNotEqualTo(UPSTREAM_TRACEPARENT);
    assertThat(traceparent).doesNotContain(UPSTREAM_SPAN_ID);
  }

  @Test
  @DisplayName("C10_入站非法traceparent降级新生成且请求仍200")
  void invalidTraceparentDegradesToNewTrace() throws Exception {
    MvcResult result =
        mvc.perform(get("/probe/ok").header(TraceHeaders.TRACEPARENT, "garbage"))
            .andExpect(status().isOk())
            .andExpect(header().string(TraceHeaders.TRACE_ID, matchesPattern(TRACE_ID_HEX_32)))
            .andReturn();

    assertThat(result.getResponse().getHeader(TraceHeaders.TRACE_ID)).isNotEqualTo("garbage");
    assertThat(readTraceId(result))
        .isEqualTo(result.getResponse().getHeader(TraceHeaders.TRACE_ID));
  }

  @Test
  @DisplayName("入站X_Trace_Id被采纳并生成新span")
  void legacyTraceIdHeaderIsAccepted() throws Exception {
    mvc.perform(get("/probe/ok").header(TraceHeaders.TRACE_ID, UPSTREAM_TRACE_ID))
        .andExpect(status().isOk())
        .andExpect(header().string(TraceHeaders.TRACE_ID, UPSTREAM_TRACE_ID))
        .andExpect(
            header()
                .string(
                    TraceHeaders.TRACEPARENT,
                    matchesPattern("00-" + UPSTREAM_TRACE_ID + "-[0-9a-f]{16}-01")));
  }

  @Test
  @DisplayName("入站非法X_Trace_Id被忽略")
  void invalidLegacyTraceIdIsIgnored() throws Exception {
    mvc.perform(get("/probe/ok").header(TraceHeaders.TRACE_ID, "NOT-A-TRACE-ID"))
        .andExpect(status().isOk())
        .andExpect(header().string(TraceHeaders.TRACE_ID, matchesPattern(TRACE_ID_HEX_32)));
  }

  @Test
  @DisplayName("tracestate原样透传")
  void tracestateIsPassedThroughVerbatim() throws Exception {
    String tracestate = "rojo=00f067aa0ba902b7:1,congo=t61rcWkgMzE";

    mvc.perform(
            get("/probe/ok")
                .header(TraceHeaders.TRACEPARENT, UPSTREAM_TRACEPARENT)
                .header(TraceHeaders.TRACESTATE, tracestate))
        .andExpect(status().isOk())
        .andExpect(header().string(TraceHeaders.TRACESTATE, tracestate));
  }

  @Test
  @DisplayName("tracestate含换行时整体丢弃防头注入")
  void tracestateWithCrlfIsDropped() throws Exception {
    MvcResult result =
        mvc.perform(
                get("/probe/ok")
                    .header(TraceHeaders.TRACEPARENT, UPSTREAM_TRACEPARENT)
                    .header(TraceHeaders.TRACESTATE, "ok=1\r\nX-Injected: pwned"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getHeader(TraceHeaders.TRACESTATE)).isNull();
    assertThat(result.getResponse().getHeader("X-Injected")).isNull();
  }

  @Test
  @DisplayName("MDC在处理器内可见且请求结束后被清理")
  void mdcIsVisibleInHandlerAndClearedAfter() throws Exception {
    MvcResult result = mvc.perform(get("/probe/mdc")).andExpect(status().isOk()).andReturn();

    Map<String, String> seenInHandler =
        JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data");
    assertThat(seenInHandler)
        .containsEntry(
            TraceContext.MDC_TRACE_ID, result.getResponse().getHeader(TraceHeaders.TRACE_ID))
        .containsEntry(TraceContext.MDC_CHANNEL, TraceContext.CHANNEL_WEB)
        .containsKey(TraceContext.MDC_SPAN_ID);

    assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
    assertThat(TraceContext.traceId()).isNull();
  }

  @Test
  @DisplayName("异常路径同样带头与信封traceId")
  void errorResponseAlsoCarriesTraceId() throws Exception {
    MvcResult result =
        mvc.perform(get("/probe/boom"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(header().string(TraceHeaders.TRACE_ID, matchesPattern(TRACE_ID_HEX_32)))
            .andReturn();

    assertThat(readTraceId(result))
        .isEqualTo(result.getResponse().getHeader(TraceHeaders.TRACE_ID));
  }

  @Test
  @DisplayName("关闭propagateW3c时只回写便捷头")
  void propagateW3cDisabledOmitsTraceparent() throws Exception {
    MockMvc w3cOff = build(new TraceProperties(true, TraceHeaders.TRACE_ID, false));

    w3cOff
        .perform(get("/probe/ok"))
        .andExpect(status().isOk())
        .andExpect(header().string(TraceHeaders.TRACE_ID, matchesPattern(TRACE_ID_HEX_32)))
        .andExpect(header().string(TraceHeaders.TRACEPARENT, nullValue()));
  }

  @Test
  @DisplayName("关闭enabled时不回写任何链路头但信封仍带traceId")
  void disabledOmitsHeadersButKeepsEnvelopeField() throws Exception {
    MockMvc disabled = build(new TraceProperties(false, TraceHeaders.TRACE_ID, true));

    MvcResult result =
        disabled
            .perform(get("/probe/ok"))
            .andExpect(status().isOk())
            .andExpect(header().string(TraceHeaders.TRACE_ID, nullValue()))
            .andExpect(header().string(TraceHeaders.TRACEPARENT, nullValue()))
            .andReturn();

    assertThat(readTraceId(result)).matches(TRACE_ID_HEX_32);
  }

  @Test
  @DisplayName("自定义响应头名生效")
  void customResponseHeaderNameIsHonoured() throws Exception {
    MockMvc renamed = build(new TraceProperties(true, "X-Request-Id", true));

    renamed
        .perform(get("/probe/ok"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Request-Id", matchesPattern(TRACE_ID_HEX_32)))
        .andExpect(header().string(TraceHeaders.TRACE_ID, nullValue()));
  }

  private static String readTraceId(MvcResult result) throws java.io.IOException {
    return JsonPath.read(
        result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.traceId");
  }

  @RestController
  static class TraceProbeController {

    @GetMapping("/probe/ok")
    ApiResponse<String> ok() {
      return ApiResponse.ok("pong");
    }

    /** 把处理器内看到的 MDC 快照回传，用于断言 Filter 的上下文确实传到了业务层。 */
    @GetMapping("/probe/mdc")
    ApiResponse<Map<String, String>> mdc() {
      return ApiResponse.ok(MDC.getCopyOfContextMap());
    }

    @GetMapping("/probe/boom")
    ApiResponse<String> boom() {
      throw new ResourceNotFoundException("probe not found");
    }
  }
}
