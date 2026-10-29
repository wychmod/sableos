package com.sableos.core.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sableos.core.agent.AgentService;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.core.trace.TraceContext;
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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * 定时任务验收（课件第 25 节 harness）：注册参数、重叠跳过、失败隔离与锁释放、会话身份、每次触发新链路。
 *
 * <p>测试诀窍：不真等时间——{@code runOnce} 是独立方法，直接调它即可覆盖全部行为；cron 触发本身是框架的事，这里只验「注册参数传对了」。
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

  @AfterEach
  void tearDown() {
    TraceContext.clear();
  }

  private AgentScheduler scheduler() {
    return new AgentScheduler(taskScheduler, profileRegistry, agentService, sessionManager);
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
    Profile.ScheduleConfig schedule = schedule(TASK_ID, "0 9 * * * *", "Asia/Shanghai", MESSAGE);
    Session session =
        new Session("scheduler:scheduler:" + PROFILE_NAME, PROFILE_NAME, "scheduler", "scheduler");
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

    AgentScheduler scheduler = scheduler();
    scheduler.runOnce(profile(schedule), schedule);
    scheduler.runOnce(profile(schedule), schedule);

    verify(sessionManager, times(2)).getOrCreate("scheduler", "scheduler", PROFILE_NAME);
    verify(agentService, times(2)).process(session, MESSAGE);
  }

  @Test
  @DisplayName("上一次还没跑完，本次触发直接跳过")
  void runOnce_whenPreviousStillRunning_skipsThisTrigger() throws InterruptedException {
    Profile.ScheduleConfig schedule = schedule(TASK_ID, "0 0 9 * * *", "Asia/Shanghai", MESSAGE);
    AgentScheduler scheduler = scheduler();
    Lock lock = scheduler.lockFor(TASK_ID);
    // ReentrantLock 对同线程可重入：本线程自己 lock 完再调 runOnce 仍会拿到锁。
    // 因此必须由另一线程占住，才等价于「上一次还在调度线程上跑」。
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread previousRun =
        new Thread(
            () -> {
              lock.lock();
              holding.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                lock.unlock();
              }
            },
            "scheduler-previous-run");
    previousRun.start();
    holding.await();
    try {
      scheduler.runOnce(profile(schedule), schedule);

      verify(agentService, never()).process(any(), any());
    } finally {
      release.countDown();
      previousRun.join();
    }
  }

  @Test
  @DisplayName("任务抛异常不外抛，且锁在 finally 被释放")
  void runOnce_whenProcessThrows_doesNotRethrowAndReleasesLock() {
    Profile.ScheduleConfig schedule = schedule(TASK_ID, "0 9 * * * *", "Asia/Shanghai", MESSAGE);
    AgentScheduler scheduler = scheduler();
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"));

    assertThatCode(() -> scheduler.runOnce(profile(schedule), schedule)).doesNotThrowAnyException();

    // 后半段才是关键：再跑一次能进来，证明锁真的放了，没有永久卡死。
    scheduler.runOnce(profile(schedule), schedule);
    verify(agentService, times(2)).process(any(), any());
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
  }

  @Test
  @DisplayName("每次触发都开一条新的根链路，渠道标记为 scheduler")
  void registeredTask_opensFreshTracePerTrigger() {
    Profile.ScheduleConfig schedule = schedule(TASK_ID, "0 9 * * * *", "Asia/Shanghai", MESSAGE);
    profileRegistry.register(profile(schedule));
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
  @DisplayName("同一 Agent 的不同定时任务并发时串行访问同一会话")
  void runOnce_differentSchedulesOfSameAgent_serializeSessionAccess() throws InterruptedException {
    Profile.ScheduleConfig first = schedule("task-a", "0 0 9 * * *", "Asia/Shanghai", MESSAGE);
    Profile.ScheduleConfig second = schedule("task-b", "0 0 9 * * *", "Asia/Shanghai", MESSAGE);
    Profile profile = profile(first, second);
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
    Thread firstRun = new Thread(() -> scheduler.runOnce(profile, first), "scheduler-first");
    firstRun.start();
    assertThat(firstInside.await(2, TimeUnit.SECONDS)).isTrue();

    Thread secondRun = new Thread(() -> scheduler.runOnce(profile, second), "scheduler-second");
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

  private static Profile.ScheduleConfig schedule(
      String id, String cron, String zone, String message) {
    return new Profile.ScheduleConfig(id, cron, zone, message);
  }

  private static Profile profile(Profile.ScheduleConfig... schedules) {
    return new Profile(
        PROFILE_NAME,
        "测试 Agent",
        new Profile.Identity(PROFILE_NAME, "你是定时简报助手。"),
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(schedules),
        List.of(),
        new Profile.Settings(null, null));
  }
}
