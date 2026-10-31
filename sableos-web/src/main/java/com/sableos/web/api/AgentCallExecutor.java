package com.sableos.web.api;

import com.sableos.core.agent.AgentService;
import com.sableos.core.session.Session;
import com.sableos.web.error.AgentTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * 对外入口的 Agent 调用统一出口（26 节）：复用 {@link AgentService#process}，并施加 60 秒上限。
 *
 * <p><b>为什么另起一个任务</b>：ReAct 循环里的模型调用是阻塞 IO，没有统一可取消的超时点；只能在调用外层用 {@link Future#get(long, TimeUnit)}
 * 截断。执行器由 {@link ApiConfiguration} 显式创建（仅服务 Agent 调用），挂了全容器唯一的 {@code TraceTaskDecorator}，traceId /
 * MDC 能跨线程传播；每任务一个虚拟线程，不用 {@link java.util.concurrent.CompletableFuture}、不引入 Reactor（AGENTS H4⑤）。
 *
 * <p><b>超时后的语义</b>:请求线程立即释放并返回 504；底层调用可能忽略中断继续跑完（例如 HTTP 客户端尚未感知取消）。这是可接受的——
 * 会话与审计仍会由后台任务按正常路径落库，下一次请求看到的是完整历史，而不是半条。
 */
final class AgentCallExecutor {

  /** 课件与技术方案统一口径：Agent 调用最长 60 秒。 */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  private final AgentService agentService;
  private final AsyncTaskExecutor executor;
  private final Duration timeout;

  AgentCallExecutor(AgentService agentService, AsyncTaskExecutor executor) {
    this(agentService, executor, DEFAULT_TIMEOUT);
  }

  /** 测试可注入短超时，避免真等 60 秒。 */
  AgentCallExecutor(AgentService agentService, AsyncTaskExecutor executor, Duration timeout) {
    this.agentService = Objects.requireNonNull(agentService, "agentService 不能为空");
    this.executor = Objects.requireNonNull(executor, "executor 不能为空");
    this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
  }

  /**
   * 在超时上限内跑完一次完整处理。
   *
   * @param session 会话（profileName 决定使用哪个 Agent）
   * @param userMessage 用户消息
   * @return Agent 最终答复
   * @throws AgentTimeoutException 超过上限
   */
  String process(Session session, String userMessage) {
    Future<String> future = executor.submit(() -> agentService.process(session, userMessage));
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new AgentTimeoutException("Agent 调用超过 60 秒");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Agent 调用被中断", e);
    } catch (ExecutionException e) {
      throw rethrow(e);
    }
  }

  private static RuntimeException rethrow(ExecutionException e) {
    Throwable cause = e.getCause();
    if (cause instanceof RuntimeException runtime) {
      return runtime;
    }
    if (cause instanceof Error error) {
      throw error;
    }
    return new IllegalStateException("Agent 调用失败", cause);
  }
}
