package com.sableos.web.trace;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 把 {@link TraceFilter} 注册到 servlet 容器，覆盖全部路径。
 *
 * <p>顺序取 {@code HIGHEST_PRECEDENCE + 10}：晚于 Boot 的字符编码过滤器，早于其他一切（含日后可能引入的 Spring Security
 * 过滤器链，其默认顺序是 {@code -100}），因此链路作用域能覆盖安全过滤器内部产生的日志。
 */
@Configuration
@EnableConfigurationProperties(TraceProperties.class)
public class TraceConfiguration {

  private static final int FILTER_ORDER_OFFSET = 10;
  private static final String ALL_PATHS = "/*";
  private static final String FILTER_NAME = "traceFilter";

  @Bean
  public FilterRegistrationBean<TraceFilter> traceFilterRegistration(TraceProperties properties) {
    FilterRegistrationBean<TraceFilter> registration =
        new FilterRegistrationBean<>(new TraceFilter(properties));
    registration.setName(FILTER_NAME);
    registration.addUrlPatterns(ALL_PATHS);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + FILTER_ORDER_OFFSET);
    return registration;
  }
}
