package com.sableos.web.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sableos.core.scheduler.AgentScheduler;
import com.sableos.core.scheduler.ScheduledTaskStore;
import com.sableos.core.scheduler.ScheduledTaskView;
import com.sableos.core.scheduler.TaskExecutionView;
import com.sableos.web.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 定时任务 v2/v1 管理端点契约（第 28 节）：列表、历史、立即执行、启停、兼容层 409。 */
class ScheduleApiControllerTest {

  private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

  private ScheduledTaskStore store;
  private AgentScheduler scheduler;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    store = mock(ScheduledTaskStore.class);
    scheduler = mock(AgentScheduler.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ScheduleApiController(store, scheduler),
                new ScheduleAgentRunController(store, scheduler),
                new LegacyScheduleApiController(store, scheduler))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("v2列表_返回任务状态")
  void v2List_returnsTasks() throws Exception {
    when(store.list()).thenReturn(List.of(task(1L, "demo", "daily", true, false)));

    mvc.perform(get("/api/v2/schedules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].scheduleId").value(1))
        .andExpect(jsonPath("$.data[0].profileName").value("demo"))
        .andExpect(jsonPath("$.data[0].scheduleKey").value("daily"))
        .andExpect(jsonPath("$.data[0].enabled").value(true));
  }

  @Test
  @DisplayName("v2执行历史_按任务返回")
  void v2Executions_returnsHistory() throws Exception {
    when(store.find(1L)).thenReturn(Optional.of(task(1L, "demo", "daily", true, false)));
    when(store.executions(1L)).thenReturn(List.of(execution(10L, 1L, true)));

    mvc.perform(get("/api/v2/schedules/1/executions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].id").value(10))
        .andExpect(jsonPath("$.data[0].success").value(true));
  }

  @Test
  @DisplayName("v2执行不存在任务_返回404")
  void v2Executions_missingTaskReturns404() throws Exception {
    when(store.find(99L)).thenReturn(Optional.empty());

    mvc.perform(get("/api/v2/schedules/99/executions"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("v2立即执行_返回执行记录")
  void v2Run_returnsExecution() throws Exception {
    when(store.find(1L)).thenReturn(Optional.of(task(1L, "demo", "daily", true, false)));
    when(scheduler.runNow(1L)).thenReturn(Optional.of(execution(11L, 1L, true)));

    mvc.perform(post("/api/v2/schedules/1/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(11))
        .andExpect(jsonPath("$.data.success").value(true));
  }

  @Test
  @DisplayName("v2立即执行_锁被占返回409")
  void v2Run_busyReturns409() throws Exception {
    when(store.find(1L)).thenReturn(Optional.of(task(1L, "demo", "daily", true, false)));
    when(scheduler.runNow(1L)).thenReturn(Optional.empty());

    mvc.perform(post("/api/v2/schedules/1/run"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));
  }

  @Test
  @DisplayName("v2启停_更新并返回新状态")
  void v2Update_togglesEnabled() throws Exception {
    when(store.setEnabled(1L, false)).thenReturn(true);
    when(store.find(1L)).thenReturn(Optional.of(task(1L, "demo", "daily", false, false)));

    mvc.perform(
            put("/api/v2/schedules/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false));

    verify(store).setEnabled(1L, false);
  }

  @Test
  @DisplayName("v2启停_缺enabled返回400且不写库")
  void v2Update_missingEnabledReturns400() throws Exception {
    mvc.perform(
            put("/api/v2/schedules/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isBadRequest());

    verify(store, never()).setEnabled(anyLong(), anyBoolean());
  }

  @Test
  @DisplayName("v2启停_任务不存在返回404")
  void v2Update_missingTaskReturns404() throws Exception {
    when(store.setEnabled(99L, true)).thenReturn(false);

    mvc.perform(
            put("/api/v2/schedules/99")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("v2按Agent加key立即执行_精确定位")
  void v2AgentScopedRun_resolvesByProfileAndKey() throws Exception {
    when(store.list()).thenReturn(List.of(task(2L, "agent-b", "daily", true, false)));
    when(scheduler.runNow(2L)).thenReturn(Optional.of(execution(12L, 2L, true)));

    mvc.perform(post("/api/v2/agents/agent-b/schedules/daily/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(12));
  }

  @Test
  @DisplayName("v2按Agent加key立即执行_未命中返回404")
  void v2AgentScopedRun_missingReturns404() throws Exception {
    when(store.list()).thenReturn(List.of());

    mvc.perform(post("/api/v2/agents/ghost/schedules/daily/run")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("v1列表_与v2同一份数据")
  void v1List_returnsSameTasks() throws Exception {
    when(store.list()).thenReturn(List.of(task(1L, "demo", "daily", true, false)));

    mvc.perform(get("/api/v1/schedules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].scheduleKey").value("daily"));
  }

  @Test
  @DisplayName("v1按key定位_多个Agent撞key返回409且不执行")
  void v1Run_duplicateKeyReturns409() throws Exception {
    when(store.list())
        .thenReturn(
            List.of(
                task(1L, "agent-a", "daily", true, false),
                task(2L, "agent-b", "daily", true, false)));

    mvc.perform(post("/api/v1/schedules/daily/run"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    verify(scheduler, never()).runNow(anyLong());
  }

  @Test
  @DisplayName("v1按key定位_唯一命中正常执行")
  void v1Run_singleMatchExecutes() throws Exception {
    when(store.list()).thenReturn(List.of(task(1L, "demo", "daily", true, false)));
    when(scheduler.runNow(1L)).thenReturn(Optional.of(execution(13L, 1L, true)));

    mvc.perform(post("/api/v1/schedules/daily/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(13));
  }

  @Test
  @DisplayName("v1按key定位_0条返回404")
  void v1Run_missingReturns404() throws Exception {
    when(store.list()).thenReturn(List.of());

    mvc.perform(post("/api/v1/schedules/ghost/run")).andExpect(status().isNotFound());
  }

  private static ScheduledTaskView task(
      long id, String profile, String key, boolean enabled, boolean retired) {
    return new ScheduledTaskView(
        id,
        profile,
        key,
        key,
        "0 0 8 * * *",
        "Asia/Shanghai",
        "到点执行",
        enabled,
        retired,
        NOW.plusSeconds(3600),
        null,
        null,
        0,
        NOW);
  }

  private static TaskExecutionView execution(long id, long scheduleId, boolean success) {
    return new TaskExecutionView(
        id, scheduleId, "scheduler:scheduler:demo", NOW, success, null, 12);
  }
}
