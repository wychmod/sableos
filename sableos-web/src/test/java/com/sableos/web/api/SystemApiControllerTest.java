package com.sableos.web.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sableos.core.provider.ProviderHealthProbe;
import com.sableos.core.provider.ProviderStatus;
import com.sableos.web.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 系统状态端点：health 不依赖外部服务；info 原样映射提供方探测结果。 */
class SystemApiControllerTest {

  private ProviderHealthProbe probe;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    probe = mock(ProviderHealthProbe.class);
    SystemApiController controller = new SystemApiController(probe, "SableOS", "0.1.0-test");
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("健康检查_返回ok")
  void healthReturnsOk() throws Exception {
    mvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("ok"));
  }

  @Test
  @DisplayName("系统信息_映射应用信息与提供方状态")
  void infoMapsApplicationAndProviderStatus() throws Exception {
    when(probe.probe())
        .thenReturn(
            List.of(
                new ProviderStatus("deepseek", "openai", true, 12L, null),
                new ProviderStatus(
                    "qwen", "dashscope", false, 3000L, "ConnectException: refused")));

    mvc.perform(get("/api/v1/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.app").value("SableOS"))
        .andExpect(jsonPath("$.data.version").value("0.1.0-test"))
        .andExpect(jsonPath("$.data.providers[0].name").value("deepseek"))
        .andExpect(jsonPath("$.data.providers[0].reachable").value(true))
        .andExpect(jsonPath("$.data.providers[1].name").value("qwen"))
        .andExpect(jsonPath("$.data.providers[1].reachable").value(false))
        .andExpect(jsonPath("$.data.providers[1].error").value("ConnectException: refused"));
  }

  @Test
  @DisplayName("未配置提供方_返回空数组而不是错误")
  void infoWithoutProvidersReturnsEmptyArray() throws Exception {
    when(probe.probe()).thenReturn(List.of());

    mvc.perform(get("/api/v1/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.providers").isArray())
        .andExpect(jsonPath("$.data.providers").isEmpty());
  }
}
