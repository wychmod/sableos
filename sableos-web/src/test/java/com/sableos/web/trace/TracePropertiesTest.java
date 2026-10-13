package com.sableos.web.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.sableos.core.trace.TraceHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 配置绑定验收：record + {@code @DefaultValue} 的构造器绑定容易在升级 Boot 时静默失效（键读不到就用默认值，不报错），
 * 因此把「默认值」与「覆盖生效」两头都钉住。
 */
class TracePropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropsConfig.class);

  @Test
  @DisplayName("未配置时_三个键取默认值")
  void usesDefaultsWhenUnset() {
    runner.run(
        context -> {
          TraceProperties properties = context.getBean(TraceProperties.class);

          assertThat(properties.enabled()).isTrue();
          assertThat(properties.responseHeader()).isEqualTo(TraceHeaders.TRACE_ID);
          assertThat(properties.propagateW3c()).isTrue();
        });
  }

  @Test
  @DisplayName("kebab配置覆盖_三个键都生效")
  void bindsKebabCaseOverrides() {
    runner
        .withPropertyValues(
            "sableos.observability.trace.enabled=false",
            "sableos.observability.trace.response-header=X-Request-Id",
            "sableos.observability.trace.propagate-w3c=false")
        .run(
            context -> {
              TraceProperties properties = context.getBean(TraceProperties.class);

              assertThat(properties.enabled()).isFalse();
              assertThat(properties.responseHeader()).isEqualTo("X-Request-Id");
              assertThat(properties.propagateW3c()).isFalse();
            });
  }

  @Test
  @DisplayName("驼峰配置同样可绑定_relaxed_binding生效")
  void bindsCamelCaseOverrides() {
    runner
        .withPropertyValues("sableos.observability.trace.responseHeader=X-Correlation-Id")
        .run(
            context ->
                assertThat(context.getBean(TraceProperties.class).responseHeader())
                    .isEqualTo("X-Correlation-Id"));
  }

  @Test
  @DisplayName("defaults工厂与配置默认值口径一致")
  void defaultsFactoryMatchesBoundDefaults() {
    runner.run(
        context ->
            assertThat(context.getBean(TraceProperties.class))
                .isEqualTo(TraceProperties.defaults()));
  }

  @Configuration
  @EnableConfigurationProperties(TraceProperties.class)
  static class PropsConfig {}
}
