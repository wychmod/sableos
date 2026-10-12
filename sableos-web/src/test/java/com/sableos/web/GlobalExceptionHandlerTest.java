package com.sableos.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sableos.web.error.AgentTimeoutException;
import com.sableos.web.error.ConflictException;
import com.sableos.web.error.ProviderUnavailableException;
import com.sableos.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 契约 http-api.md 的错误映射验收：每类异常映射到约定状态码，响应体都是统一信封， 且 500 绝不泄露内部细节。 */
class GlobalExceptionHandlerTest {

  /** ISO-8601 时刻形态，如 2026-10-05T15:00:00Z / 2026-10-05T15:00:00.123456Z。 */
  private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("非法参数_映射400且message可定位")
  void badRequestMaps400() throws Exception {
    mvc.perform(get("/throw/bad"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("provider 未注册: foo"))
        .andExpect(jsonPath("$.data", nullValue()))
        .andExpect(jsonPath("$.timestamp", matchesPattern(ISO_INSTANT)));
  }

  @Test
  @DisplayName("MVC层缺参_映射400且点名参数")
  void missingParameterMaps400() throws Exception {
    mvc.perform(get("/throw/missing-param"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message", containsString("profile_name")));
  }

  @Test
  @DisplayName("资源不存在_映射404且点名资源")
  void resourceNotFoundMaps404() throws Exception {
    mvc.perform(get("/throw/not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("会话不存在: abc-123"));
  }

  @Test
  @DisplayName("路径无handler_映射404")
  void noRouteMaps404() throws Exception {
    mvc.perform(get("/throw/no-route"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("Resource not found"));
  }

  @Test
  @DisplayName("名称多义冲突_映射409")
  void conflictMaps409() throws Exception {
    mvc.perform(get("/throw/conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("Agent 名称多义: demo"));
  }

  @Test
  @DisplayName("Provider故障_映射503")
  void providerUnavailableMaps503() throws Exception {
    mvc.perform(get("/throw/provider"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503))
        .andExpect(jsonPath("$.message").value("provider deepseek 连接失败"));
  }

  @Test
  @DisplayName("Agent调用超时_映射504")
  void agentTimeoutMaps504() throws Exception {
    mvc.perform(get("/throw/timeout"))
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.code").value(504))
        .andExpect(jsonPath("$.message").value("Agent 调用超过 60 秒"));
  }

  @Test
  @DisplayName("未捕获异常_映射500且不泄露内部细节")
  void unhandledMaps500WithoutLeakingDetails() throws Exception {
    mvc.perform(get("/throw/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.message").value("Internal server error"))
        .andExpect(content().string(not(containsString("secret internal detail"))));
  }

  @RestController
  static class ThrowingController {

    @GetMapping("/throw/bad")
    void bad() {
      throw new IllegalArgumentException("provider 未注册: foo");
    }

    @GetMapping("/throw/missing-param")
    void missingParam() throws MissingServletRequestParameterException {
      throw new MissingServletRequestParameterException("profile_name", "String");
    }

    @GetMapping("/throw/not-found")
    void notFound() {
      throw new ResourceNotFoundException("会话不存在: abc-123");
    }

    @GetMapping("/throw/no-route")
    void noRoute() throws NoResourceFoundException {
      throw new NoResourceFoundException(HttpMethod.GET, "/no-such-path");
    }

    @GetMapping("/throw/conflict")
    void conflict() {
      throw new ConflictException("Agent 名称多义: demo");
    }

    @GetMapping("/throw/provider")
    void provider() {
      throw new ProviderUnavailableException("provider deepseek 连接失败");
    }

    @GetMapping("/throw/timeout")
    void timeout() {
      throw new AgentTimeoutException("Agent 调用超过 60 秒");
    }

    @GetMapping("/throw/boom")
    void boom() {
      throw new RuntimeException("secret internal detail");
    }
  }
}
