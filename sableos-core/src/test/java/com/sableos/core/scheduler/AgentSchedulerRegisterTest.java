package com.sableos.core.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sableos.core.agent.AgentService;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * 课件《第29节》验收 harness：AgentSchedulerRegisterTest——registerProfile 后句柄表有该项、 cron/时区来自
 * Profile.schedules、重复登记取消旧句柄（第 30 节注销/更新的前提）。
 */
class AgentSchedulerRegisterTest {

  private static final String PROFILE_NAME = "ops";
  private static final String ZONE = "Asia/Shanghai";

  private TaskScheduler taskScheduler;
  private SessionManager sessionManager;
  private AgentService agentService;
  private ProfileRegistry profileRegistry;
  private InMemoryScheduledTaskStore store;

  @BeforeEach
  void setUp() {
    taskScheduler = mock(TaskScheduler.class);
    sessionManager = mock(SessionManager.class);
    agentService = mock(AgentService.class);
    profileRegistry = new ProfileRegistry();
    store = new InMemoryScheduledTaskStore();
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString()))
        .thenReturn(
            new Session(
                "scheduler:scheduler:" + PROFILE_NAME, PROFILE_NAME, "scheduler", "scheduler"));
    when(agentService.process(any(), any())).thenReturn("ok");
  }

  private AgentScheduler scheduler() {
    return new AgentScheduler(
        taskScheduler,
        profileRegistry,
        agentService,
        sessionManager,
        store,
        Runnable::run,
        Duration.ofSeconds(60));
  }

  private static Profile profile(String... scheduleIds) {
    List<Profile.ScheduleConfig> schedules =
        List.of(scheduleIds).stream()
            .map(id -> new Profile.ScheduleConfig(id, "0 0 9 * * *", ZONE, "到点"))
            .toList();
    return new Profile(
        PROFILE_NAME,
        "运维 Agent",
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        schedules,
        List.of(),
        new Profile.Settings(null, null));
  }

  @Test
  @DisplayName("registerProfile 后每条定时都有可查询句柄")
  void registerProfile_exposesHandlePerSchedule() {
    Profile profile = profile("morning", "noon");
    profileRegistry.register(profile);
    AgentScheduler scheduler = scheduler();
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));

    scheduler.registerProfile(profile);

    List<Long> ids = store.list().stream().map(ScheduledTaskView::scheduleId).toList();
    assertThat(ids).hasSize(2);
    assertThat(ids).allMatch(scheduler::hasScheduledTask);
  }

  @Test
  @DisplayName("cron 与时区取自 Profile.schedules")
  void registerProfile_usesCronAndZoneFromProfile() {
    Profile profile = profile("morning");
    AgentScheduler scheduler = scheduler();
    doReturn(mock(ScheduledFuture.class))
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Trigger.class));

    scheduler.registerProfile(profile);

    ArgumentCaptor<Trigger> triggers = ArgumentCaptor.forClass(Trigger.class);
    verify(taskScheduler).schedule(any(Runnable.class), triggers.capture());
    Trigger trigger = triggers.getValue();
    assertThat(trigger).isInstanceOf(CronTrigger.class);
    assertThat(((CronTrigger) trigger).getExpression()).isEqualTo("0 0 9 * * *");

    // CronTrigger 的时区不外露，故用「下一次触发时刻」行为断言：
    // 2026-10-29T00:00:00Z 是上海时间 08:00，同一 cron 在 UTC 下会得到 09:00Z。
    Instant lastRun = Instant.parse("2026-10-29T00:00:00Z");
    assertThat(trigger.nextExecution(new SimpleTriggerContext(lastRun, lastRun, lastRun)))
        .isEqualTo(Instant.parse("2026-10-29T01:00:00Z"));
  }

  @Test
  @DisplayName("重复登记同一任务：句柄仍为 1，旧句柄被取消")
  void registerProfile_twice_cancelsPreviousHandle() {
    Profile profile = profile("morning");
    AgentScheduler scheduler = scheduler();
    ScheduledFuture<?> first = mock(ScheduledFuture.class);
    ScheduledFuture<?> second = mock(ScheduledFuture.class);
    doReturn(first)
        .doReturn(second)
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Trigger.class));

    scheduler.registerProfile(profile);
    scheduler.registerProfile(profile);

    long scheduleId = store.list().get(0).scheduleId();
    assertThat(scheduler.hasScheduledTask(scheduleId)).isTrue();
    verify(first, times(1)).cancel(false);
    verify(second, times(0)).cancel(any(Boolean.class));
    // 句柄表只有一条：再次登记没有产生第二个 scheduleId
    assertThat(store.list()).hasSize(1);
  }

  @Test
  @DisplayName("单条配置非法只跳过该条，同 Agent 其余定时仍登记")
  void registerProfile_invalidSchedule_skipsOnlyThatOne() {
    Profile profile =
        new Profile(
            PROFILE_NAME,
            "运维 Agent",
            null,
            new Profile.ProviderRef("deepseek", "deepseek-chat", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new Profile.ScheduleConfig("bad", "not-a-cron", ZONE, "到点"),
                new Profile.ScheduleConfig("good", "0 0 9 * * *", ZONE, "到点")),
            List.of(),
            new Profile.Settings(null, null));
    AgentScheduler scheduler = scheduler();
    doReturn(mock(ScheduledFuture.class))
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Trigger.class));

    scheduler.registerProfile(profile);

    assertThat(store.list()).hasSize(1);
    assertThat(store.list().get(0).scheduleKey()).isEqualTo("good");
  }

  @Test
  @DisplayName("没有定时的 Agent 登记后不产生任何句柄")
  void registerProfile_withoutSchedules_registersNothing() {
    Profile profile =
        new Profile(
            PROFILE_NAME,
            "无定时 Agent",
            null,
            new Profile.ProviderRef("deepseek", "deepseek-chat", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(null, null));
    AgentScheduler scheduler = scheduler();

    scheduler.registerProfile(profile);

    assertThat(store.list()).isEmpty();
  }

  // ---------------------------------------------------------------- 第 30 节：注销

  @Test
  @DisplayName("unregisterProfile 后句柄消失、任务退役")
  void unregisterProfile_cancelsHandlesAndRetires() {
    Profile profile = profile("morning", "noon");
    AgentScheduler scheduler = scheduler();
    ScheduledFuture<?> morning = mock(ScheduledFuture.class);
    ScheduledFuture<?> noon = mock(ScheduledFuture.class);
    doReturn(morning)
        .doReturn(noon)
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Trigger.class));

    scheduler.registerProfile(profile);
    List<Long> ids = store.list().stream().map(ScheduledTaskView::scheduleId).toList();
    assertThat(ids).hasSize(2).allMatch(scheduler::hasScheduledTask);

    scheduler.unregisterProfile(profile);

    assertThat(ids).noneMatch(scheduler::hasScheduledTask);
    assertThat(store.list()).isEmpty(); // list 只返回未退役项
    verify(morning).cancel(false);
    verify(noon).cancel(false);
  }

  @Test
  @DisplayName("unregisterProfile 只影响该 Agent 的任务")
  void unregisterProfile_onlyTouchesItsOwnAgent() {
    Profile mine = profile("mine");
    Profile other =
        new Profile(
            "other",
            "另一个 Agent",
            null,
            new Profile.ProviderRef("deepseek", "deepseek-chat", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(new Profile.ScheduleConfig("theirs", "0 0 9 * * *", ZONE, "到点")),
            List.of(),
            new Profile.Settings(null, null));
    AgentScheduler scheduler = scheduler();
    doReturn(mock(ScheduledFuture.class))
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Trigger.class));

    scheduler.registerProfile(mine);
    scheduler.registerProfile(other);
    long mineId =
        store.list().stream()
            .filter(v -> v.profileName().equals(PROFILE_NAME))
            .findFirst()
            .orElseThrow()
            .scheduleId();
    long otherId =
        store.list().stream()
            .filter(v -> v.profileName().equals("other"))
            .findFirst()
            .orElseThrow()
            .scheduleId();

    scheduler.unregisterProfile(mine);

    assertThat(scheduler.hasScheduledTask(mineId)).isFalse();
    assertThat(scheduler.hasScheduledTask(otherId)).isTrue();
  }

  @Test
  @DisplayName("unregisterProfile 对未注册的 Agent 幂等")
  void unregisterProfile_unknownAgent_isIdempotent() {
    AgentScheduler scheduler = scheduler();
    scheduler.unregisterProfile(profile("never"));
    scheduler.unregisterProfile(null);
    assertThat(store.list()).isEmpty();
  }
}
