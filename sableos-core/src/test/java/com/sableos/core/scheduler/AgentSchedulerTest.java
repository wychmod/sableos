package com.sableos.core.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sableos.core.agent.AgentService;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.core.trace.TraceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * 定时任务验收（课件第 25 / 28 节 harness）：注册参数、重叠跳过、失败隔离、启用检查、执行记录、立即执行与退役。
 *
 * <p>测试诀窍：不真等时间——{@code runOnce} / {@code runNow} 是独立方法，直接调即可覆盖全部行为；cron 触发本身是框架的事， 这里只验「注册参数传对了」。
 */
@ExtendWith(MockitoExtension.class)
class AgentSchedulerTest {

  private static final String PROFILE_NAME = "demo";
  private static final String TASK_ID = "task-1";
  private static final String MESSAGE = "报告今天的进展";

  @Mock TaskScheduler taskScheduler;

  @Mock AgentService agentService;

  @Mock SessionManager sessionManager;

  private final ProfileRegistry profileRegistry = new ProfileRegistry();
  private final InMemoryScheduledTaskStore store = new InMemoryScheduledTaskStore();

  @AfterEach
  void tearDown() {
    TraceContext.clear();
  }

  private AgentScheduler scheduler() {
    return scheduler(Duration.ofSeconds(60));
  }

  private AgentScheduler scheduler(Duration callTimeout) {
    return new AgentScheduler(
        taskScheduler,
        profileRegistry,
        agentService,
        sessionManager,
        store,
        Runnable::run,
        callTimeout);
  }

  private long taskId(Profile profile, Profile.ScheduleConfig schedule) {
    profileRegistry.register(profile);
    return store
        .reconcile(
            profile.name(),
            schedule.id(),
            schedule.id(),
            schedule.cron(),
            schedule.zone(),
            schedule.message(),
            Instant.now().plusSeconds(3600))
        .scheduleId();
  }

  @Test
  @DisplayName("注册时 CronTrigger 带上了配置的 cron 和时区")
  void registerAll_registersEveryScheduleWithCronAndZone() {
    profileRegistry.register(
        profile(
            schedule(TASK_ID, "0 0 9 * * *", "Asia/Shanghai", MESSAGE),
            schedule("task-2", "0 30 8 * * *", "Asia/Shanghai", MESSAGE)));

    scheduler().registerAll();

    ArgumentCaptor<Trigger> triggers = ArgumentCaptor.forClass(Trigger.class);
    verify(taskScheduler, times(2)).schedule(any(Runnable.class), triggers.capture());

    // CronTrigger 不暴露时区且 equals 只比表达式，故用「下一次触发时刻」行为断言：
    // 2026-10-29T00:00:00Z 是上海时间 08:00，同一 cron 在 UTC 下会得到 09:00Z，差 8 小时。
    CronTrigger first = (CronTrigger) triggers.getAllValues().get(0);
    assertThat(first.getExpression()).isEqualTo("0 0 9 * * *");
    Instant lastRun = Instant.parse("2026-10-29T00:00:00Z");
    assertThat(first.nextExecution(new SimpleTriggerContext(lastRun, lastRun, lastRun)))
        .isEqualTo(Instant.parse("2026-10-29T01:00:00Z"));
  }

  @Test
  @DisplayName("两次触发拿到同一 Session，三元组固定 scheduler/scheduler/profileName")
  void runOnce_usesSchedulerIdentityAndReusesSession() {
    Profile profile = profile(schedule(TASK_ID, "0 9 * * * *", "Asia/Shanghai", MESSAGE));
    Profile.ScheduleConfig schedule = profile.schedules().get(0);
    long id = taskId(profile, schedule);
    Session session =
        new Session("scheduler:scheduler:" + PROFILE_NAME, PROFILE_NAME, "scheduler", "scheduler");
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

    AgentScheduler scheduler = scheduler();
    scheduler.runOnce(profile, schedule, id);
    scheduler.runOnce(profile, schedule, id);

    verify(sessionManager, times(2)).getOrCreate("scheduler", "scheduler", PROFILE_NAME);
    verify(agentService, times(2)).process(session, MESSAGE);
    assertThat(store.find(id)).get().extracting(ScheduledTaskView::runCount).isEqualTo(2L);
  }

  @Test
  @DisplayName("上一次还没跑完，本次触发直接跳过")
  void runOnce_whenPreviousStillRunning_skipsThisTrigger() throws InterruptedException {
    Profile profile = profile(schedule(TASK_ID, "0 0 9 * * *", "Asia/Shanghai", MESSAGE));
    Profile.ScheduleConfig schedule = profile.schedules().get(0);
    long id = taskId(profile, schedule);
    AgentScheduler scheduler = scheduler();
    LockHolder holder = holdLockInOtherThread(scheduler.lockFor(PROFILE_NAME, TASK_ID));
    try {
      scheduler.runOnce(profile, schedule, id);

      verify(agentService, never()).process(any(), any());
      assertThat(store.executions(id)).isEmpty();
    } finally {
      holder.releaseAndJoin();
    }
  }

  @Test
  @DisplayName("不同 Agent 使用相同任务标识时互不阻塞")
  void runOnce_sameTaskIdDifferentAgents_doNotBlockEachOther() throws InterruptedException {
    Profile.ScheduleConfig schedule = schedule("daily", "0 0 9 * * *", "Asia/Shanghai", MESSAGE);
    Profile agentA = profile("agent-a", schedule);
    Profile agentB = profile("agent-b", schedule);
    long idA = taskId(agentA, schedule);
    long idB = taskId(agentB, schedule);
    Session sessionB =
        new Session("scheduler:scheduler:agent-b", "agent-b", "scheduler", "scheduler");
    when(sessionManager.getOrCreate("scheduler", "scheduler", "agent-b")).thenReturn(sessionB);

    AgentScheduler scheduler = scheduler();
    LockHolder holder = holdLockInOtherThread(scheduler.lockFor("agent-a", "daily"));
    try {
      scheduler.runOnce(agentA, schedule, idA); // 撞名且自身被占住：本次跳过
      scheduler.runOnce(agentB, schedule, idB); // 另一个 Agent 的同名任务照常执行
    } finally {
      holder.releaseAndJoin();
    }

    verify(agentService, times(1)).process(sessionB, MESSAGE);
    assertThat(store.executions(idA)).isEmpty();
    assertThat(store.executions(idB)).hasSize(1);
  }

  @Test
  @DisplayName("任务抛异常不外抛，且锁在 finally 被释放")
  void runOnce_whenProcessThrows_doesNotRethrowAndReleasesLock() {
    Profile profile = profile(schedule(TASK_ID, "0 9 * * * *", "Asia/Shanghai", MESSAGE));
    Profile.ScheduleConfig schedule = profile.schedules().get(0);
    long id = taskId(profile, schedule);
    AgentScheduler scheduler = scheduler();
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString()))
        .thenReturn(
            new Session(
                "scheduler:scheduler:" + PROFILE_NAME, PROFILE_NAME, "scheduler", "scheduler"));
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"));

    assertThatCode(() -> scheduler.runOnce(profile, schedule, id)).doesNotThrowAnyException();

    // 后半段才是关键：再跑一次能进来，证明锁真的放了，没有永久卡死。
    scheduler.runOnce(profile, schedule, id);
    verify(agentService, times(2)).process(any(), any());
    assertThat(store.find(id)).get().extracting(ScheduledTaskView::lastStatus).isEqualTo("failed");
    assertThat(store.find(id)).get().extracting(ScheduledTaskView::runCount).isEqualTo(2L);
    assertThat(store.executions(id)).hasSize(2);
  }

  @Test
  @DisplayName("停用的任务到点直接跳过，不执行也不记历史")
  void runOnce_whenDisabled_skipsAndRecordsNothing() {
    Profile profile = profile(schedule(TASK_ID, "0 9 * * * *", "Asia/Shanghai", MESSAGE));
    Profile.ScheduleConfig schedule = profile.schedules().get(0);
    long id = taskId(profile, schedule);
    store.setEnabled(id, false);

    scheduler().runOnce(profile, schedule, id);

    verify(agentService, never()).process(any(), any());
    assertThat(store.executions(id)).isEmpty();
  }

  @Test
  @DisplayName("立即执行无视停用状态，并记录历史")
  void runNow_ignoresDisabledAndRecords() {
    Profile profile = profile(schedule(TASK_ID, "0 9 * * * *", "Asia/Shanghai", MESSAGE));
    Profile.ScheduleConfig schedule = profile.schedules().get(0);
    long id = taskId(profile, schedule);
    store.setEnabled(id, false);
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString()))
        .thenReturn(
            new Session(
                "scheduler:scheduler:" + PROFILE_NAME, PROFILE_NAME, "scheduler", "scheduler"));
    when(agentService.process(any(), any())).thenReturn("ok");

    TaskExecutionView execution = scheduler().runNow(id).orElseThrow();

    assertThat(execution.success()).isTrue();
    verify(agentService).process(any(), any());
    assertThat(store.find(id)).get().extracting(ScheduledTaskView::enabled).isEqualTo(false);
    assertThat(store.executions(id)).hasSize(1);
  }

  @Test
  @DisplayName("立即执行时任务锁被占，返回空而不是并发跑第二份")
  void runNow_whenLockHeld_returnsEmpty() throws InterruptedException {
    Profile profile = profile(schedule(TASK_ID, "0 9 * * * *", "Asia/Shanghai", MESSAGE));
    Profile.ScheduleConfig schedule = profile.schedules().get(0);
    long id = taskId(profile, schedule);
    AgentScheduler scheduler = scheduler();
    LockHolder holder = holdLockInOtherThread(scheduler.lockFor(PROFILE_NAME, TASK_ID));
    try {
      assertThat(scheduler.runNow(id)).isEmpty();
      verify(agentService, never()).process(any(), any());
    } finally {
      holder.releaseAndJoin();
    }
  }

  @Test
  @DisplayName("单次执行超过上限_中止并把可读原因写进执行历史")
  void runNow_whenCallExceedsTimeout_recordsFailureAndKeepsSchedulerUsable() throws Exception {
    Profile profile = profile(schedule(TASK_ID, "0 9 * * * *", "Asia/Shanghai", MESSAGE));
    Profile.ScheduleConfig schedule = profile.schedules().get(0);
    long id = taskId(profile, schedule);
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString()))
        .thenReturn(
            new Session(
                "scheduler:scheduler:" + PROFILE_NAME, PROFILE_NAME, "scheduler", "scheduler"));
    CountDownLatch neverReleased = new CountDownLatch(1);
    when(agentService.process(any(), any()))
        .thenAnswer(
            invocation -> {
              neverReleased.await(10, TimeUnit.SECONDS);
              return "太晚了";
            });
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("scheduler-timeout-test-");
    try {
      AgentScheduler scheduler =
          new AgentScheduler(
              taskScheduler,
              profileRegistry,
              agentService,
              sessionManager,
              store,
              executor,
              Duration.ofMillis(150));

      TaskExecutionView execution = scheduler.runNow(id).orElseThrow();

      assertThat(execution.success()).isFalse();
      assertThat(execution.errorMessage()).contains("超过");
      assertThat(store.executions(id)).hasSize(1);
      // 关键：超时不能把调度器本身拖死——任务锁已释放，下一次立即执行仍能进入。
      assertThat(scheduler.runNow(id)).isPresent();
      assertThat(store.executions(id)).hasSize(2);
    } finally {
      neverReleased.countDown();
      executor.close();
    }
  }

  @Test
  @DisplayName("配置删除后再次注册，旧任务被标记退役")
  void registerAll_retiresTaskMissingFromConfig() {
    Profile profile = profile(schedule(TASK_ID, "0 0 9 * * *", "Asia/Shanghai", MESSAGE));
    profileRegistry.register(profile);
    AgentScheduler scheduler = scheduler();
    scheduler.registerAll();
    long id = store.list().get(0).scheduleId();

    profileRegistry.register(profile(PROFILE_NAME));
    scheduler.registerAll();

    assertThat(store.list()).isEmpty();
    assertThat(store.find(id)).get().extracting(ScheduledTaskView::retired).isEqualTo(true);
  }

  @Test
  @DisplayName("非法配置只跳过该条，不影响其余任务")
  void registerAll_skipsOnlyInvalidSchedule() {
    profileRegistry.register(
        profile(
            schedule("bad-cron", "not-a-cron", "Asia/Shanghai", MESSAGE),
            schedule(TASK_ID, "0 9 * * * *", "Asia/Shanghai", MESSAGE)));

    scheduler().registerAll();

    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
    assertThat(store.list()).hasSize(1);
  }

  @Test
  @DisplayName("每次触发都开一条新的根链路，渠道标记为 scheduler")
  void registeredTask_opensFreshTracePerTrigger() {
    Profile profile = profile(schedule(TASK_ID, "0 9 * * * *", "Asia/Shanghai", MESSAGE));
    profileRegistry.register(profile);
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString()))
        .thenReturn(
            new Session(
                "scheduler:scheduler:" + PROFILE_NAME, PROFILE_NAME, "scheduler", "scheduler"));
    List<String> traceIds = new ArrayList<>();
    List<String> channels = new ArrayList<>();
    when(agentService.process(any(), any()))
        .thenAnswer(
            invocation -> {
              traceIds.add(TraceContext.traceId());
              channels.add(TraceContext.business(TraceContext.MDC_CHANNEL));
              return "ok";
            });

    scheduler().registerAll();
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler).schedule(task.capture(), any(Trigger.class));

    task.getValue().run();
    task.getValue().run();

    assertThat(traceIds).hasSize(2).doesNotContainNull();
    assertThat(traceIds.get(0)).isNotEqualTo(traceIds.get(1));
    assertThat(channels).containsOnly(TraceContext.CHANNEL_SCHEDULER);
  }

  @Test
  @DisplayName("跳过分支同样每次开新链路，不复用上一次 traceId")
  void skippedTrigger_opensFreshTracePerTrigger() throws InterruptedException {
    Profile profile = profile(schedule(TASK_ID, "0 0 9 * * *", "Asia/Shanghai", MESSAGE));
    profileRegistry.register(profile);
    AgentScheduler scheduler = scheduler();
    scheduler.registerAll();
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler).schedule(task.capture(), any(Trigger.class));

    Logger schedulerLogger = (Logger) LoggerFactory.getLogger(AgentScheduler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    schedulerLogger.addAppender(appender);
    try {
      LockHolder holder = holdLockInOtherThread(scheduler.lockFor(PROFILE_NAME, TASK_ID));
      try {
        task.getValue().run();
        task.getValue().run();
      } finally {
        holder.releaseAndJoin();
      }
    } finally {
      schedulerLogger.detachAppender(appender);
    }

    List<String> traceIds =
        appender.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .map(event -> event.getMDCPropertyMap().get(TraceContext.MDC_TRACE_ID))
            .toList();
    assertThat(traceIds).hasSize(2).doesNotContainNull();
    assertThat(traceIds.get(0)).isNotEqualTo(traceIds.get(1));
    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("同一 Agent 的不同定时任务并发时串行访问同一会话")
  void runOnce_differentSchedulesOfSameAgent_serializeSessionAccess() throws InterruptedException {
    Profile.ScheduleConfig first = schedule("task-a", "0 0 9 * * *", "Asia/Shanghai", MESSAGE);
    Profile.ScheduleConfig second = schedule("task-b", "0 0 9 * * *", "Asia/Shanghai", MESSAGE);
    Profile profile = profile(first, second);
    long firstId = taskId(profile, first);
    long secondId = taskId(profile, second);
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString()))
        .thenReturn(
            new Session(
                "scheduler:scheduler:" + PROFILE_NAME, PROFILE_NAME, "scheduler", "scheduler"));
    CountDownLatch firstInside = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maxActive = new AtomicInteger();
    when(agentService.process(any(), any()))
        .thenAnswer(
            invocation -> {
              maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
              firstInside.countDown();
              awaitQuietly(releaseFirst);
              active.decrementAndGet();
              return "ok";
            });

    AgentScheduler scheduler = scheduler();
    Thread firstRun =
        new Thread(() -> scheduler.runOnce(profile, first, firstId), "scheduler-first");
    firstRun.start();
    assertThat(firstInside.await(2, TimeUnit.SECONDS)).isTrue();

    Thread secondRun =
        new Thread(() -> scheduler.runOnce(profile, second, secondId), "scheduler-second");
    secondRun.start();
    // 给第二个触发足够时间抵达会话锁：若未串行化，它此刻已进入 process、并发计数变 2。
    Thread.sleep(300);
    assertThat(maxActive.get()).as("同一 Agent 的两个定时任务不得并发处理同一会话").isEqualTo(1);

    releaseFirst.countDown();
    firstRun.join(2000);
    secondRun.join(2000);
    assertThat(maxActive.get()).isEqualTo(1);
    verify(agentService, times(2)).process(any(), any());
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * 在另一线程占住锁：{@link java.util.concurrent.locks.ReentrantLock} 对同线程可重入，本线程自己 lock 完再调 {@code
   * runOnce} 仍会拿到锁，必须换线程才等价于「上一次还在调度线程上跑」。
   */
  private static LockHolder holdLockInOtherThread(Lock lock) throws InterruptedException {
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              lock.lock();
              held.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                lock.unlock();
              }
            },
            "scheduler-lock-holder");
    holder.start();
    held.await();
    return new LockHolder(release, holder);
  }

  private record LockHolder(CountDownLatch release, Thread holder) {
    void releaseAndJoin() throws InterruptedException {
      release.countDown();
      holder.join(2000);
    }
  }

  private static Profile.ScheduleConfig schedule(
      String id, String cron, String zone, String message) {
    return new Profile.ScheduleConfig(id, cron, zone, message);
  }

  private static Profile profile(Profile.ScheduleConfig... schedules) {
    return profile(PROFILE_NAME, schedules);
  }

  private static Profile profile(String name, Profile.ScheduleConfig... schedules) {
    return new Profile(
        name,
        "测试 Agent",
        new Profile.Identity(name, "你是定时简报助手。"),
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(schedules),
        List.of(),
        new Profile.Settings(null, null));
  }
}
