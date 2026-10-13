package com.sableos.boot.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

/**
 * 可观测性装配（组合根）。
 *
 * <p><b>全容器只允许存在一个 {@link TaskDecorator} bean</b>：Boot 的 {@code SimpleAsyncTaskExecutorBuilder} 用
 * {@code ObjectProvider.getIfUnique()} 取装饰器，注册两个及以上会**静默返回 null**——链路不会传播，且没有任何报错或告警。 因此新增装饰需求时必须扩展
 * {@link TraceTaskDecorator}，而不是再注册一个 bean； {@code ObservabilityConfigTest} 用断言把这条钉住。
 */
@Configuration
public class ObservabilityConfig {

  @Bean
  public TaskDecorator traceTaskDecorator() {
    return new TraceTaskDecorator();
  }
}
