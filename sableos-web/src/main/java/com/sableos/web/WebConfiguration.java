package com.sableos.web;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层跨域配置（26 节；技术方案 §7.4）。
 *
 * <p>核心阶段无认证、内网部署，开放所有来源方便调试；范围严格限定在 {@code /api/**}，不把放宽策略扩散到 {@code /swagger-ui}、{@code
 * /actuator} 等路径。扩展阶段改为来源白名单时只改这一处。
 *
 * <p>{@code allowCredentials(false)} 与通配来源配套：浏览器规范不允许“任意来源 + 携带凭证”的组合，显式关闭可避免误配。
 */
@Configuration
@SuppressFBWarnings(
    value = "PERMISSIVE_CORS",
    justification =
        "技术方案 §7.4 明确核心阶段开放所有源方便调试；范围仅限 /api/**、allowCredentials(false)，"
            + "且阶段内无认证（§7.5），不存在“凭证随通配来源泄露”的组合。扩展阶段改为来源白名单时只改本类。")
public class WebConfiguration implements WebMvcConfigurer {

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins("*")
        .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(false);
  }
}
