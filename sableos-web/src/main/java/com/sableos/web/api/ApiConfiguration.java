package com.sableos.web.api;

import com.sableos.core.agent.AgentService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;

/**
 * Web API 包的内部装配：把 60 秒调用执行器做成单例，供两个需要跑 Agent 的 Controller 共用。
 *
 * <p><b>为什么不直接注入 {@code applicationTaskExecutor}</b>：Boot 的 {@code TaskExecutionAutoConfiguration}
 * 带 {@code @ConditionalOnMissingBean(Executor.class)}，而本容器已有 {@code agentTaskScheduler}（调度用线程池，也是
 * Executor），因此 Boot 根本不会创建 {@code applicationTaskExecutor}。直接按名注入会在启动期失败。
 *
 * <p>这里显式声明一个仅服务于 Agent 调用的虚拟线程执行器，并复用容器内唯一的 {@link TaskDecorator}（{@code
 * TraceTaskDecorator}）做链路传播。它不是线程池（每任务一个虚拟线程），也不新增第二个 {@code TaskDecorator} bean；调度线程池与请求执行器
 * 相互隔离，长调用不会占住定时任务的槽位。
 */
@Configuration
class ApiConfiguration {

  private static final String THREAD_NAME_PREFIX = "sableos-web-agent-";

  @Bean(destroyMethod = "close")
  AsyncTaskExecutor agentCallTaskExecutor(TaskDecorator traceTaskDecorator) {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(THREAD_NAME_PREFIX);
    executor.setVirtualThreads(true);
    executor.setTaskDecorator(traceTaskDecorator);
    return executor;
  }

  @Bean
  AgentCallExecutor agentCallExecutor(
      AgentService agentService,
      @Qualifier("agentCallTaskExecutor") AsyncTaskExecutor agentCallTaskExecutor) {
    return new AgentCallExecutor(agentService, agentCallTaskExecutor);
  }
}
