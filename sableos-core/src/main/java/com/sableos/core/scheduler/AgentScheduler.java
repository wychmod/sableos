package com.sableos.core.scheduler;

import com.sableos.core.agent.AgentService;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.core.trace.TraceContext;
import com.sableos.core.trace.TracedExecutors;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 定时任务调度器：第三种触发源（技术方案 §8.5）——「钟推」到点拼一条消息，交给与 CLI / Web 完全相同的 {@link AgentService#process}。
 *
 * <p>职责边界刻意收窄：本类只做「按配置注册 → 到点取锁 → 取会话 → 转交」四步。消息里说什么归 Profile / SKILL.md，消息怎么处理归 ReActLoop，本类一概不管。
 *
 * <p>三条约定：
 *
 * <ul>
 *   <li><b>配置驱动</b>：注册内容全部来自 {@link Profile#schedules()}，不用 {@code @Scheduled} 这类编译期常量。
 *   <li><b>本地锁防重叠</b>：按任务 id 的进程内 {@link ReentrantLock}，拿不到即跳过——核心阶段单实例，不做分布式协调。
 *   <li><b>失败隔离</b>：单次执行异常只记日志、{@code finally} 释锁，绝不外抛、不拖垮调度器；审计仍走 AgentService 内部既有路径。
 * </ul>
 *
 * <p>每次触发经 {@link TracedExecutors#wrapRoot} 开一条新的根链路：定时入口没有上游链路，跳过分支也各自成链，绝不复用上一次 traceId（{@code
 * TracedExecutors} 的 javadoc 对这一用法有明确约定）。
 */
public final class AgentScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(AgentScheduler.class);

  /** 钟推会话的固定用户标识；channel 复用 {@link TraceContext#CHANNEL_SCHEDULER}（同为 {@code scheduler}）。 */
  private static final String SCHEDULER_USER_ID = "scheduler";

  private final TaskScheduler taskScheduler;
  private final ProfileRegistry profileRegistry;
  private final AgentService agentService;
  private final SessionManager sessionManager;
  private final ConcurrentMap<String, Lock> taskLocks = new ConcurrentHashMap<>();

  /**
   * @param taskScheduler 触发调度器（装配层提供 {@code ThreadPoolTaskScheduler}）
   * @param profileRegistry Agent 运行配置注册表
   * @param agentService 统一处理入口
   * @param sessionManager 会话身份与持久化的唯一收口
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：四个参数都是依赖注入的协作者引用，本类无任何返回它们的 getter，不构成把可变内部表示暴露出去。"
              + "依赖注入本就必须持有协作者引用，不引入多余抽象接口即可规避 EI_EXPOSE_REP2 属 SpotBugs 对"
              + "「注入具体类」的固有误判（先例见 PromptBuilder 构造器上的同款抑制）。撤销条件：一旦其中任一协作者"
              + "的引用被对外暴露（新增 getter / public 字段），须重新评估并移除本抑制。")
  public AgentScheduler(
      TaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager) {
    this.taskScheduler = taskScheduler;
    this.profileRegistry = profileRegistry;
    this.agentService = agentService;
    this.sessionManager = sessionManager;
  }

  /**
   * 启动期注册全部定时任务：遍历所有已注册 Profile 的 {@code schedules} 逐条注册。
   *
   * <p>单条配置非法（id / cron / zone 缺失或解析失败）只记错误日志并跳过该条，不阻断其余任务、不阻断进程启动（FR-010）。
   */
  public void registerAll() {
    for (Profile profile : profileRegistry.all()) {
      for (Profile.ScheduleConfig schedule : profile.schedules()) {
        registerOne(profile, schedule);
      }
    }
  }

  private void registerOne(Profile profile, Profile.ScheduleConfig schedule) {
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
      taskScheduler.schedule(
          TracedExecutors.wrapRoot(
              () -> runOnce(profile, schedule), TraceContext.CHANNEL_SCHEDULER),
          trigger);
      LOG.info(
          "定时任务已注册：profile={} id={} cron={} zone={}",
          safe(profile.name()),
          safe(taskId),
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

  /**
   * 执行一次触发：拿不到执行权说明上一次还没跑完，直接跳过（不排队、不并发）。
   *
   * <p>无论成功失败，锁都在 {@code finally} 释放；异常只记日志，不向调度器外抛。
   *
   * @param profile 该任务所属 Agent
   * @param schedule 任务配置（id / cron / zone / message）
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "确认误报：进入日志模板的动态值只有 safe(id) 与 safe(e.getMessage())，两者都经 replaceAll(\"[\\r\\n]\", \"_\") "
              + "移除了 CR/LF；末尾的 Throwable 只用于渲染堆栈，不参与 {} 插值，其 message 已在 reason 位置以净化形态出现。"
              + "撤销条件：改为把异常对象以外的未净化字符串拼进日志。")
  void runOnce(Profile profile, Profile.ScheduleConfig schedule) {
    Lock lock = lockFor(schedule.id());
    if (!lock.tryLock()) {
      LOG.warn("定时任务上一次尚未结束，跳过本次触发：id={}", safe(schedule.id()));
      return;
    }
    try {
      // session_id 只由 SessionManager 拼接（H4④）：这里只给三元组，绝不自行拼 channel:user:profile。
      Session session =
          sessionManager.getOrCreate(
              TraceContext.CHANNEL_SCHEDULER, SCHEDULER_USER_ID, profile.name());
      agentService.process(session, schedule.message());
    } catch (Exception e) {
      // 坑三：失败不能悄悄没声音，也不能把调度器带崩；异常对象入日志保留堆栈，message 先做 CRLF 净化。
      LOG.error("定时任务执行失败：id={} reason={}", safe(schedule.id()), safe(e.getMessage()), e);
    } finally {
      lock.unlock();
    }
  }

  /** 按任务 id 取（首次惰性创建）执行权；包级可见供同包测试模拟「上一次仍占着锁」。 */
  Lock lockFor(String taskId) {
    return taskLocks.computeIfAbsent(taskId, id -> new ReentrantLock());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法；勿改成双反斜杠或 Pattern/Matcher）。 */
  private static String safe(String value) {
    return (value == null) ? null : value.replaceAll("[\r\n]", "_");
  }
}
