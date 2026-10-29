package com.sableos.web.sandbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sableos.core.sandbox.SandboxWhitelistAdmin;
import com.sableos.core.sandbox.WhitelistCommand;
import com.sableos.core.sandbox.WhitelistDuplicateException;
import com.sableos.core.sandbox.WhitelistEntry;
import com.sableos.core.sandbox.WhitelistEntryNotFoundException;
import com.sableos.core.sandbox.WhitelistType;
import com.sableos.core.sandbox.WhitelistValidationException;
import com.sableos.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP 契约测试：路由、动词、参数绑定、信封形状与错误码翻译。
 *
 * <p><b>为何用 standaloneSetup 而非 {@code @WebMvcTest}</b>：{@code @WebMvcTest} 会向上查找
 * {@code @SpringBootConfiguration}，而 sableos-web 模块内没有（真正的装配根在 sableos-cli，且 web 不依赖它）。本模块既有的
 * {@code GlobalExceptionHandlerTest} 同样用 standaloneSetup。
 *
 * <p>本类**不**验证 {@code @ConditionalOnProperty} 开关——那是装配层行为，standaloneSetup 看不到；开关的验证在 boot 模块的集成测试里。
 */
class SandboxWhitelistControllerTest {

  private SandboxWhitelistAdmin admin;
  private MockMvc mvc;

  private static final WhitelistEntry SAMPLE =
      new WhitelistEntry(
          7L,
          WhitelistType.HTTP_DOMAIN,
          "api.open-meteo.com",
          "天气 Demo",
          Instant.parse("2026-10-27T10:00:00Z"),
          Instant.parse("2026-10-27T10:00:00Z"));

  @BeforeEach
  void setUp() {
    admin = mock(SandboxWhitelistAdmin.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new SandboxWhitelistController(admin))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("列表返回 5 字段信封_按类型过滤")
  void list_returnsEnvelopeWithFiveFields() throws Exception {
    when(admin.list(WhitelistType.HTTP_DOMAIN)).thenReturn(List.of(SAMPLE));

    mvc.perform(get("/api/v1/sandbox/whitelist").param("type", "HTTP_DOMAIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.message").value("ok"))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.traceId").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data[0].id").value(7))
        .andExpect(jsonPath("$.data[0].type").value("HTTP_DOMAIN"))
        .andExpect(jsonPath("$.data[0].value").value("api.open-meteo.com"));
  }

  @Test
  @DisplayName("缺省 type 时查询全部")
  void list_withoutType_queriesAll() throws Exception {
    when(admin.list(null)).thenReturn(List.of(SAMPLE));

    mvc.perform(get("/api/v1/sandbox/whitelist"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", Matchers.hasSize(1)));
  }

  @Test
  @DisplayName("单条详情返回 200")
  void get_returnsEntry() throws Exception {
    when(admin.get(7L)).thenReturn(SAMPLE);

    mvc.perform(get("/api/v1/sandbox/whitelist/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.value").value("api.open-meteo.com"));
  }

  @Test
  @DisplayName("单条不存在返回 404")
  void get_missing_returns404() throws Exception {
    when(admin.get(42L)).thenThrow(new WhitelistEntryNotFoundException(42L));

    mvc.perform(get("/api/v1/sandbox/whitelist/42"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("白名单条目不存在: 42"));
  }

  @Test
  @DisplayName("新增成功返回 201")
  void add_returns201() throws Exception {
    when(admin.add(any(WhitelistCommand.class))).thenReturn(SAMPLE);

    mvc.perform(
            post("/api/v1/sandbox/whitelist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"HTTP_DOMAIN\",\"value\":\"api.open-meteo.com\",\"description\":\"天气 Demo\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.id").value(7));
  }

  @Test
  @DisplayName("重复新增返回 409")
  void add_duplicate_returns409() throws Exception {
    when(admin.add(any(WhitelistCommand.class)))
        .thenThrow(new WhitelistDuplicateException(WhitelistType.HTTP_DOMAIN, "api.example.com"));

    mvc.perform(
            post("/api/v1/sandbox/whitelist")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"HTTP_DOMAIN\",\"value\":\"api.example.com\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("白名单条目已存在: HTTP_DOMAIN api.example.com"));
  }

  @Test
  @DisplayName("非法值返回 400")
  void add_invalid_returns400() throws Exception {
    when(admin.add(any(WhitelistCommand.class)))
        .thenThrow(new WhitelistValidationException("HTTP_DOMAIN 格式非法：只接受主机名或 *.主机名"));

    mvc.perform(
            post("/api/v1/sandbox/whitelist")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"HTTP_DOMAIN\",\"value\":\"http://x.com\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("400 响应不回显调用方原始输入")
  void validationError_doesNotEchoInput() throws Exception {
    when(admin.add(any(WhitelistCommand.class)))
        .thenThrow(new WhitelistValidationException("HTTP_DOMAIN 格式非法：只接受主机名或 *.主机名"));

    mvc.perform(
            post("/api/v1/sandbox/whitelist")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"HTTP_DOMAIN\",\"value\":\"<img src=x onerror=alert(1)>\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(Matchers.not(Matchers.containsString("onerror"))));
  }

  @Test
  @DisplayName("修改成功返回 200_请求体里的 type 被忽略")
  void update_ignoresType() throws Exception {
    when(admin.update(eq(7L), eq(".sableos/skills"), eq("改值"))).thenReturn(SAMPLE);

    mvc.perform(
            put("/api/v1/sandbox/whitelist/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"FILE_PATH\",\"value\":\".sableos/skills\",\"description\":\"改值\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(7));
  }

  @Test
  @DisplayName("删除返回 200_且 data 为 null")
  void delete_returns200WithNullData() throws Exception {
    mvc.perform(delete("/api/v1/sandbox/whitelist/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data").value(Matchers.nullValue()));
  }

  @Test
  @DisplayName("删除不存在的条目返回 404")
  void delete_missing_returns404() throws Exception {
    org.mockito.Mockito.doThrow(new WhitelistEntryNotFoundException(42L)).when(admin).delete(42L);

    mvc.perform(delete("/api/v1/sandbox/whitelist/42")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("内部错误返回 500 且不泄漏细节")
  void internalError_doesNotLeakMessage() throws Exception {
    when(admin.list(null)).thenThrow(new IllegalStateException("jdbc:sqlite:/data/x.db"));

    mvc.perform(get("/api/v1/sandbox/whitelist"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("Internal server error"))
        .andExpect(content().string(Matchers.not(Matchers.containsString("jdbc:sqlite"))));
  }
}
