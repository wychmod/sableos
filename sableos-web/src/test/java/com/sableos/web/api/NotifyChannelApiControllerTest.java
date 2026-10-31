package com.sableos.web.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sableos.core.notify.NotifyChannelStore;
import com.sableos.core.notify.NotifyChannelView;
import com.sableos.web.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 通知渠道 CRUD 契约（第 28 节）：列表、新建、更新、删除、重名 409、非法 400。 */
class NotifyChannelApiControllerTest {

  private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

  private NotifyChannelStore store;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    store = mock(NotifyChannelStore.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new NotifyChannelApiController(store))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("列表_返回全部渠道")
  void list_returnsChannels() throws Exception {
    when(store.list()).thenReturn(List.of(channel("team", "https://example.com/hook")));

    mvc.perform(get("/api/v1/notify-channels"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].name").value("team"))
        .andExpect(jsonPath("$.data[0].type").value("webhook"));
  }

  @Test
  @DisplayName("新建_合法请求返回渠道")
  void create_returnsChannel() throws Exception {
    NotifyChannelView created = channel("team", "https://example.com/hook");
    when(store.find("team")).thenReturn(Optional.empty(), Optional.of(created));

    mvc.perform(
            post("/api/v1/notify-channels").contentType(MediaType.APPLICATION_JSON).content(body()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("team"))
        .andExpect(jsonPath("$.data.url").value("https://example.com/hook"));

    verify(store).save(any(NotifyChannelView.class));
  }

  @Test
  @DisplayName("新建_重名返回409")
  void create_duplicateReturns409() throws Exception {
    when(store.find("team")).thenReturn(Optional.of(channel("team", "https://example.com/hook")));

    mvc.perform(
            post("/api/v1/notify-channels").contentType(MediaType.APPLICATION_JSON).content(body()))
        .andExpect(status().isConflict());

    verify(store, never()).save(any(NotifyChannelView.class));
  }

  @Test
  @DisplayName("新建_非webhook类型返回400")
  void create_nonWebhookReturns400() throws Exception {
    String payload = "{\"name\":\"team\",\"type\":\"email\",\"url\":\"https://example.com/hook\"}";

    mvc.perform(
            post("/api/v1/notify-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("新建_非法url返回400")
  void create_invalidUrlReturns400() throws Exception {
    String payload = "{\"name\":\"team\",\"type\":\"webhook\",\"url\":\"ftp://example.com\"}";

    mvc.perform(
            post("/api/v1/notify-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("新建_非法名称返回400")
  void create_invalidNameReturns400() throws Exception {
    String payload =
        "{\"name\":\"bad name\",\"type\":\"webhook\",\"url\":\"https://example.com/hook\"}";

    mvc.perform(
            post("/api/v1/notify-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("更新_保留创建时间并返回新值")
  void update_returnsUpdatedChannel() throws Exception {
    NotifyChannelView existing = channel("team", "https://old.example.com/hook");
    NotifyChannelView updated =
        new NotifyChannelView(
            "team",
            "webhook",
            "https://new.example.com/hook",
            "新地址",
            Map.of(),
            NOW,
            NOW.plusSeconds(1));
    when(store.find("team")).thenReturn(Optional.of(existing), Optional.of(updated));

    mvc.perform(
            put("/api/v1/notify-channels/team")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatedBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.url").value("https://new.example.com/hook"));

    verify(store).save(any(NotifyChannelView.class));
  }

  @Test
  @DisplayName("更新_不存在返回404")
  void update_missingReturns404() throws Exception {
    when(store.find("ghost")).thenReturn(Optional.empty());

    mvc.perform(
            put("/api/v1/notify-channels/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("删除_成功与不存在")
  void delete_successAndMissing() throws Exception {
    when(store.delete("team")).thenReturn(true);
    mvc.perform(delete("/api/v1/notify-channels/team")).andExpect(status().isOk());

    when(store.delete("ghost")).thenReturn(false);
    mvc.perform(delete("/api/v1/notify-channels/ghost")).andExpect(status().isNotFound());
  }

  private static NotifyChannelView channel(String name, String url) {
    return new NotifyChannelView(name, "webhook", url, null, Map.of(), NOW, NOW);
  }

  private static byte[] body() {
    return "{\"name\":\"team\",\"type\":\"webhook\",\"url\":\"https://example.com/hook\",\"description\":\"团队\",\"config\":{}}"
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] updatedBody() {
    return "{\"name\":\"team\",\"type\":\"webhook\",\"url\":\"https://new.example.com/hook\",\"description\":\"新地址\"}"
        .getBytes(StandardCharsets.UTF_8);
  }
}
