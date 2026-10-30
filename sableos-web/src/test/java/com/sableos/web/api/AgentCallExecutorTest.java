package com.sableos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sableos.core.agent.AgentService;
import com.sableos.core.provider.ProviderFailureException;
import com.sableos.core.session.Session;
import com.sableos.web.error.AgentTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/** 60 秒上限的执行机制验收：超时截断、异常原类型透出、正常结果透传。 */
class AgentCallExecutorTest {

  private static final Session SESSION =
      new Session("web:alice:default", "default", "web", "alice");

  @Test
  @DisplayName("超过上限_抛AgentTimeoutException")
  void timeoutThrowsAgentTimeout() {
    AgentService agentService = mock(AgentService.class);
    when(agentService.process(any(), any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(5_000L);
              return "too late";
            });
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("agent-timeout-test-");
    try {
      AgentCallExecutor callExecutor =
          new AgentCallExecutor(agentService, executor, Duration.ofMillis(100));
      assertThatThrownBy(() -> callExecutor.process(SESSION, "hi"))
          .isInstanceOf(AgentTimeoutException.class)
          .hasMessageContaining("60 秒");
    } finally {
      executor.close();
    }
  }

  @Test
  @DisplayName("提供方失败_原类型透出而不是ExecutionException")
  void providerFailureIsUnwrapped() {
    AgentService agentService = mock(AgentService.class);
    ProviderFailureException failure = new ProviderFailureException("模型提供方调用失败", null);
    when(agentService.process(any(), any())).thenThrow(failure);
    AsyncTaskExecutor executor = Runnable::run;

    AgentCallExecutor callExecutor = new AgentCallExecutor(agentService, executor);

    assertThatThrownBy(() -> callExecutor.process(SESSION, "hi")).isSameAs(failure);
  }

  @Test
  @DisplayName("正常返回_答复原样透传")
  void successfulReplyIsReturned() {
    AgentService agentService = mock(AgentService.class);
    when(agentService.process(any(), any())).thenReturn("你好");
    AsyncTaskExecutor executor = Runnable::run;

    AgentCallExecutor callExecutor = new AgentCallExecutor(agentService, executor);

    assertThat(callExecutor.process(SESSION, "hi")).isEqualTo("你好");
  }
}
