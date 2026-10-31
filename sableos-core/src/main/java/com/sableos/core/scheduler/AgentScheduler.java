package com.sableos.core.scheduler;

import com.sableos.core.agent.AgentService;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.core.trace.TraceContext;
import com.sableos.core.trace.TracedExecutors;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * 定时任务调度器：第三种触发源（技术方案 §8.5）——「钟推」到点拼一条消息，交给与 CLI / Web 完全相同的 {@link AgentService#process}。
 *
 * <p>第 28 节把它升级成完整子系统：注册时经 {@link ScheduledTaskStore} 登记任务并取得稳定 {@code scheduleId}；执行前查启用状态；
 * 每次真正执行成功失败都写执行历史并更新任务状态；新增 {@link #runNow(long)} 供管理面补跑。定义来源仍是 {@link Profile#schedules()}，本类不管理
 * cron 定义。
 *
 * <p>三条并发约定与 25 节一致：任务锁按 {@code (profileName, scheduleKey)} 防重叠；同一 Agent 的多条 schedules 共享会话，故再按会话加
 * 阻塞锁串行化；失败只记日志、{@code finally} 释锁，绝不拖垮调度器。
 */
public final class AgentScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(AgentScheduler.class);

  /** 钟推会话的固定用户标识；channel 复用 {@link TraceContext#CHANNEL_SCHEDULER}（同为 {@code scheduler}）。 */
  private static final String SCHEDULER_USER_ID = "scheduler";

  private static final int MAX_ERROR_MESSAGE_CHARS = 1000;

  /** 单次定时执行的时长上限，与 Web 入口的 60 秒口径一致（技术方案 §7.5）。 */
  public static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

  private final TaskScheduler taskScheduler;
  private final ProfileRegistry profileRegistry;
  private final AgentService agentService;
  private final SessionManager sessionManager;
  private final ScheduledTaskStore scheduledTaskStore;
  private final AsyncTaskExecutor agentCallExecutor;
  private final Duration callTimeout;
  private final ConcurrentMap<TaskKey, Lock> taskLocks = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Lock> sessionLocks = new ConcurrentHashMap<>();
  private final ConcurrentMap<Long, RegisteredTask> registeredTasks = new ConcurrentHashMap<>();

  /**
   * @param taskScheduler 触发调度器（装配层提供 {@code ThreadPoolTaskScheduler}）
   * @param profileRegistry Agent 运行配置注册表
   * @param agentService 统一处理入口
   * @param sessionManager 会话身份与持久化的唯一收口
   * @param scheduledTaskStore 任务运行态与执行历史（core 契约，storage 实现）
   * @param agentCallExecutor 执行 Agent 调用的执行器（虚拟线程），用于施加 {@code callTimeout} 上限
   * @param callTimeout 单次执行上限；到点后取消并记失败，避免一处卡死长期占住调度线程
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：七个参数都是依赖注入的协作者引用，本类无任何返回它们的 getter，不构成把可变内部表示暴露出去。"
              + "依赖注入本就必须持有协作者引用，不引入多余抽象接口即可规避 EI_EXPOSE_REP2 属 SpotBugs 对"
              + "「注入具体类」的固有误判（先例见 PromptBuilder 构造器上的同款抑制）。撤销条件：一旦其中任一协作者"
              + "的引用被对外暴露（新增 getter / public 字段），须重新评估并移除本抑制。")
  public AgentScheduler(
      TaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager,
      ScheduledTaskStore scheduledTaskStore,
      AsyncTaskExecutor agentCallExecutor,
      Duration callTimeout) {
    this.taskScheduler = taskScheduler;
    this.profileRegistry = profileRegistry;
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.scheduledTaskStore = scheduledTaskStore;
    this.agentCallExecutor = agentCallExecutor;
    this.callTimeout = callTimeout;
  }

  /**
   * 启动期注册全部定时任务：遍历所有已注册 Profile 的 {@code schedules}，逐条登记到 store 并注册 cron。
   *
   * <p>单条配置非法（id / cron / zone 缺失或解析失败）只记错误日志并跳过该条，不阻断其余任务、不阻断进程启动（FR-010）。注册完成后把
   * 已不在当前配置集合里的活动任务标记退役（FR-007）。
   */
  public void registerAll() {
    Set<TaskKey> configured = new HashSet<>();
    Instant now = Instant.now();
    for (Profile profile : profileRegistry.all()) {
      for (Profile.ScheduleConfig schedule : profile.schedules()) {
        registerOne(profile, schedule, now, configured);
      }
    }
    retireMissing(configured);
  }

  private void registerOne(
      Profile profile, Profile.ScheduleConfig schedule, Instant now, Set<TaskKey> configured) {
    String taskId = schedule.id();
    if (isBlank(taskId) || isBlank(schedule.cron()) || isBlank(schedule.zone())) {
      LOG.error(
          "定时任务配置不完整（id/cron/zone 必填），已跳过：profile={} id={}", safe(profile.name()), safe(taskId));
      return;
    }
    try {
      // ZoneId.of 对非法时区抛异常；不用 TimeZone.getTimeZone——它对非法 ID 静默回落 GMT，
      // 会让「时区写错」变成「按伦敦时间跑」且毫无痕迹。
      CronTrigger trigger = new CronTrigger(schedule.cron(), ZoneId.of(schedule.zone()));
      Instant nextRunAt = trigger.nextExecution(new SimpleTriggerContext(now, now, now));
      ScheduledTaskView stored =
          scheduledTaskStore.reconcile(
              profile.name(),
              taskId,
              taskId,
              schedule.cron(),
              schedule.zone(),
              schedule.message(),
              nextRunAt);
      ScheduledFuture<?> future =
          taskScheduler.schedule(
              TracedExecutors.wrapRoot(
                  () -> runOnce(profile, schedule, stored.scheduleId()),
                  TraceContext.CHANNEL_SCHEDULER),
              trigger);
      registeredTasks.put(
          stored.scheduleId(), new RegisteredTask(profile, schedule, stored.scheduleId(), future));
      configured.add(new TaskKey(profile.name(), taskId));
      LOG.info(
          "定时任务已注册：profile={} id={} scheduleId={} cron={} zone={}",
          safe(profile.name()),
          safe(taskId),
          stored.scheduleId(),
          safe(schedule.cron()),
          safe(schedule.zone()));
    } catch (RuntimeException e) {
      LOG.error(
          "定时任务 cron/时区非法或注册失败，已跳过：profile={} id={} reason={}",
          safe(profile.name()),
          safe(taskId),
          safe(e.getMessage()));
    }
  }

  /** 把本次配置里不存在的活动任务退役；退役失败只记日志，不影响已注册任务。 */
  private void retireMissing(Set<TaskKey> configured) {
    try {
      for (ScheduledTaskView task : scheduledTaskStore.list()) {
        TaskKey key = new TaskKey(task.profileName(), task.scheduleKey());
        if (!configured.contains(key)) {
          scheduledTaskStore.retire(task.scheduleId());
          RegisteredTask removed = registeredTasks.remove(task.scheduleId());
          if (removed != null && removed.future() != null) {
            removed.future().cancel(false);
          }
          LOG.info(
              "定时任务已退役（配置中不再出现）：profile={} id={} scheduleId={}",
              safe(task.profileName()),
              safe(task.scheduleKey()),
              task.scheduleId());
        }
      }
    } catch (RuntimeException e) {
      LOG.error("定时任务退役对账失败，保留现状：reason={}", safe(e.getMessage()));
    }
  }

  /**
   * 到点触发一次：先查启用状态，再取任务锁；停用或上一次未结束都直接跳过、不记历史。
   *
   * <p>无论成功失败，锁都在 {@code finally} 释放；异常只记日志，不向调度器外抛。
   *
   * @param profile 该任务所属 Agent
   * @param schedule 任务配置
   * @param scheduleId 持久化任务标识
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "确认误报：进入日志模板的动态值只有 safe(profile.name())、safe(schedule.id()) 与 safe(e.getMessage())，都经"
              + " replaceAll(\"[\\r\\n]\", \"_\") 移除了 CR/LF；末尾的 Throwable 只用于渲染堆栈，不参与 {} 插值。"
              + "撤销条件：改为把异常对象以外的未净化字符串拼进日志。")
  void runOnce(Profile profile, Profile.ScheduleConfig schedule, long scheduleId) {
    boolean enabled;
    try {
      enabled = scheduledTaskStore.isEnabled(scheduleId);
    } catch (RuntimeException e) {
      LOG.error(
          "读取定时任务启用状态失败，跳过本次触发：profile={} id={} reason={}",
          safe(profile.name()),
          safe(schedule.id()),
          safe(e.getMessage()));
      return;
    }
    if (!enabled) {
      LOG.debug("定时任务已停用，跳过本次触发：profile={} id={}", safe(profile.name()), safe(schedule.id()));
      return;
    }
    Lock lock = lockFor(profile.name(), schedule.id());
    if (!lock.tryLock()) {
      LOG.warn("定时任务上一次尚未结束，跳过本次触发：profile={} id={}", safe(profile.name()), safe(schedule.id()));
      return;
    }
    try {
      execute(profile, schedule, scheduleId);
    } catch (Exception e) {
      LOG.error(
          "定时任务执行失败：profile={} id={} reason={}",
          safe(profile.name()),
          safe(schedule.id()),
          safe(e.getMessage()),
          e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 管理面立即执行一次：不等 cron、无视启用状态，但仍遵守任务锁与会话锁。
   *
   * @param scheduleId 持久化任务标识
   * @return 本次执行历史；任务不存在或上一次仍占用执行权时为空
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "确认误报：进入日志模板的动态值只有 safe(profile.name())、safe(schedule.id()) 与 safe(e.getMessage())，"
              + "都经 replaceAll(\"[\\r\\n]\", \"_\") 移除了 CR/LF；末尾的 Throwable 只用于渲染堆栈，不参与 {} 插值。"
              + "撤销条件：改为把异常对象以外的未净化字符串拼进日志。")
  public Optional<TaskExecutionView> runNow(long scheduleId) {
    RegisteredTask task = registeredTasks.get(scheduleId);
    if (task == null) {
      task = reconstruct(scheduleId);
    }
    if (task == null) {
      return Optional.empty();
    }
    Lock lock = lockFor(task.profile().name(), task.schedule().id());
    if (!lock.tryLock()) {
      LOG.warn(
          "定时任务上一次尚未结束，拒绝立即执行：profile={} id={}",
          safe(task.profile().name()),
          safe(task.schedule().id()));
      return Optional.empty();
    }
    try {
      return Optional.of(execute(task.profile(), task.schedule(), scheduleId));
    } catch (Exception e) {
      LOG.error(
          "定时任务立即执行失败：profile={} id={} reason={}",
          safe(task.profile().name()),
          safe(task.schedule().id()),
          safe(e.getMessage()),
          e);
      return Optional.empty();
    } finally {
      lock.unlock();
    }
  }

  /**
   * 在 {@link #callTimeout} 内跑完一次处理。
   *
   * <p>定时任务跑在调度线程池上（池大小 4），模型与工具调用都是阻塞 IO，没有可取消的统一超时点；一旦对端挂死就会长期占住一个槽位， 拖垮同实例的其余定时任务。故与 Web
   * 入口同口径，在调用外层用 {@link Future#get(long, TimeUnit)} 截断，超时即取消并把失败写进 执行历史（课件 28 节 §2.4「该超时的都要超时」）。
   *
   * <p>链路上下文由 {@code TracedExecutors} 显式传播：执行器每任务一个虚拟线程，MDC 不会自动继承（AGENTS §5.2）。
   */
  private void processWithTimeout(Session session, String message) {
    Future<String> future =
        agentCallExecutor.submit(
            TracedExecutors.wrapCallable(() -> agentService.process(session, message)));
    try {
      future.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new IllegalStateException("定时任务执行超过 " + callTimeout.toMillis() + " 毫秒被中止", e);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("定时任务执行被中断", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException("定时任务执行失败", cause);
    }
  }

  private RegisteredTask reconstruct(long scheduleId) {
    ScheduledTaskView view;
    try {
      view = scheduledTaskStore.find(scheduleId).orElse(null);
    } catch (RuntimeException e) {
      LOG.error("读取定时任务失败：scheduleId={} reason={}", scheduleId, safe(e.getMessage()));
      return null;
    }
    if (view == null || view.retired()) {
      return null;
    }
    Profile profile = profileRegistry.get(view.profileName());
    if (profile == null) {
      LOG.warn("定时任务所属 Agent 未注册，拒绝立即执行：profile={}", safe(view.profileName()));
      return null;
    }
    Profile.ScheduleConfig schedule =
        new Profile.ScheduleConfig(view.scheduleKey(), view.cron(), view.zone(), view.message());
    return new RegisteredTask(profile, schedule, scheduleId, null);
  }

  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "确认误报：进入日志模板的动态值只有 safe(profile.name())、safe(schedule.id()) 与 errorMessage"
              + "（已由 readableError 做 replaceAll(\"[\\r\\n]\", \"_\") 净化）；末尾的 Throwable 只用于渲染堆栈，"
              + "不参与 {} 插值。撤销条件：改为把异常对象以外的未净化字符串拼进日志。")
  private TaskExecutionView execute(
      Profile profile, Profile.ScheduleConfig schedule, long scheduleId) {
    Instant startedAt = Instant.now();
    long startNanos = System.nanoTime();
    boolean success = true;
    String errorMessage = null;
    // session_id 只由 SessionManager 拼接（H4④）；这里先算出候选值，getOrCreate 失败时仍能记录可读的归属。
    String sessionId =
        SessionManager.sessionId(TraceContext.CHANNEL_SCHEDULER, SCHEDULER_USER_ID, profile.name());
    try {
      Session session =
          sessionManager.getOrCreate(
              TraceContext.CHANNEL_SCHEDULER, SCHEDULER_USER_ID, profile.name());
      sessionId = session.id();
      // 任务锁只防「同一任务」重叠；同一 Agent 的多条 schedules 共享同一个会话，
      // 并发处理会各自加载历史快照、后写覆盖前写。故在任务锁之内再按会话串行化。
      Lock sessionLock = sessionLockFor(profile.name());
      sessionLock.lock();
      try {
        processWithTimeout(session, schedule.message());
      } finally {
        sessionLock.unlock();
      }
    } catch (Exception e) {
      success = false;
      errorMessage = readableError(e);
      LOG.error(
          "定时任务执行失败：profile={} id={} reason={}",
          safe(profile.name()),
          safe(schedule.id()),
          errorMessage,
          e);
    }
    long durationMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    Instant nextRunAt = nextRunAt(schedule, Instant.now());
    Optional<TaskExecutionView> recorded =
        scheduledTaskStore.recordExecution(
            scheduleId, sessionId, startedAt, success, errorMessage, durationMs, nextRunAt);
    if (recorded.isPresent()) {
      return recorded.get();
    }
    LOG.error("定时任务已执行但任务记录不存在，执行历史未落库：scheduleId={}", scheduleId);
    return new TaskExecutionView(
        0L, scheduleId, sessionId, startedAt, success, errorMessage, durationMs);
  }

  /**
   * 按「Agent 名 + 任务键」取（首次惰性创建）执行权；包级可见供同包测试模拟「上一次仍占着锁」。
   *
   * <p>任务的身份是 {@code (profileName, scheduleKey)} 而不是裸 key：两个 Agent 各自声明 {@code id: daily} 是两条独立任务，
   * 不能因为撞名而互相跳过（FR-004「不同任务互不阻塞」）。这也与 §8.5 的 {@code (profile_name, schedule_key)} 口径一致。
   */
  Lock lockFor(String profileName, String scheduleKey) {
    return taskLocks.computeIfAbsent(
        new TaskKey(profileName, scheduleKey), key -> new ReentrantLock());
  }

  /** 任务身份键：用 record 而非拼接字符串，避免 Agent 名 / 任务键含分隔符时两条任务撞成同一把锁。 */
  private record TaskKey(String profileName, String scheduleKey) {}

  /** 已注册任务的内存索引：仅用于立即执行时快速找到配置；不作为运行态事实源。 */
  private record RegisteredTask(
      Profile profile,
      Profile.ScheduleConfig schedule,
      long scheduleId,
      ScheduledFuture<?> future) {}

  /**
   * 按定时会话取（首次惰性创建）串行化锁：同一 Agent 的历次定时触发共用一个会话，必须串行读写。
   *
   * <p>键调 {@link SessionManager#sessionId} 这个唯一公式生成，不在本类另拼字符串（H4④）。用阻塞获取而非 {@code
   * tryLock}：两条不同任务的触发都应有结果，不能把后到的一次静默丢掉。
   */
  Lock sessionLockFor(String profileName) {
    return sessionLocks.computeIfAbsent(
        SessionManager.sessionId(TraceContext.CHANNEL_SCHEDULER, SCHEDULER_USER_ID, profileName),
        id -> new ReentrantLock());
  }

  private static Instant nextRunAt(Profile.ScheduleConfig schedule, Instant from) {
    try {
      CronTrigger trigger = new CronTrigger(schedule.cron(), ZoneId.of(schedule.zone()));
      return trigger.nextExecution(new SimpleTriggerContext(from, from, from));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String readableError(Exception e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      message = e.getClass().getSimpleName();
    }
    String sanitized = message.replaceAll("[\r\n]", "_");
    return sanitized.length() > MAX_ERROR_MESSAGE_CHARS
        ? sanitized.substring(0, MAX_ERROR_MESSAGE_CHARS)
        : sanitized;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法；勿改成双反斜杠或 Pattern/Matcher）。 */
  private static String safe(String value) {
    return (value == null) ? null : value.replaceAll("[\r\n]", "_");
  }
}
