package com.sableos.boot.scheduler;

import com.sableos.core.agent.AgentService;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.scheduler.AgentScheduler;
import com.sableos.core.scheduler.ScheduledTaskStore;
import com.sableos.core.session.SessionManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务装配（技术方案 §8.5 / §8.6）：提供调度线程池与 {@link AgentScheduler}。
 *
 * <p>注册时机不放在 Bean 的 {@code @PostConstruct}：三种运行模式共用同一装配根，Bean 自注册会让交互式的 {@code chat} 也跑起定时任务，而 §8.6
 * 只要求「定时任务随 {@code serve} / {@code gateway} 一起常驻调度」。故由这两个子命令在上下文启动后 显式调用 {@link
 * AgentScheduler#registerAll()}。
 *
 * <p>不注册第二个 {@code TaskDecorator}：{@code ThreadPoolTaskScheduler} 本就没有该设置项，定时入口的链路传播由 {@code
 * AgentScheduler} 在注册时用 {@code TracedExecutors.wrapRoot} 完成（AGENTS §5.2）。
 */
@Configuration
public class SchedulerConfiguration {

  /** 池大小取 4：不同任务同时到点时互不阻塞（同一任务的重叠仍由任务级锁拦下）。 */
  private static final int SCHEDULER_POOL_SIZE = 4;

  private static final String THREAD_NAME_PREFIX = "sableos-scheduler-";

  @Bean
  public ThreadPoolTaskScheduler agentTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(SCHEDULER_POOL_SIZE);
    scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);
    return scheduler;
  }

  /**
   * @param agentTaskScheduler 本节专属调度器（显式具体类型，避免与 Boot 自动配置的 {@code taskScheduler} 按类型争用）
   * @param agentCallExecutor 与 Web 入口共用的 Agent 调用执行器（虚拟线程）；定时执行同样受 60 秒上限约束
   */
  @Bean
  public AgentScheduler agentScheduler(
      ThreadPoolTaskScheduler agentTaskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager,
      ScheduledTaskStore scheduledTaskStore,
      @Qualifier("agentCallTaskExecutor") AsyncTaskExecutor agentCallExecutor) {
    return new AgentScheduler(
        agentTaskScheduler,
        profileRegistry,
        agentService,
        sessionManager,
        scheduledTaskStore,
        agentCallExecutor,
        AgentScheduler.CALL_TIMEOUT);
  }
}
