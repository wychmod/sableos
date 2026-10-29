package com.sableos.cli.command;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sableos.core.scheduler.AgentScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 装配链路验收：{@code serve} / {@code gateway} 启动成功后必须注册定时任务（技术方案 §8.6）。
 *
 * <p>「{@code chat} 不注册」不做断言：它根本没有调用点，测「某处不存在调用」只能靠源码扫描，收益低且脆。
 */
class ServeCommandTest {

  @Test
  @DisplayName("serve 启动成功后注册全部定时任务")
  void startScheduler_registersAllTasks() {
    ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    AgentScheduler scheduler = mock(AgentScheduler.class);
    when(context.getBean(AgentScheduler.class)).thenReturn(scheduler);

    ServeCommand.startScheduler(context);

    verify(scheduler).registerAll();
  }
}
