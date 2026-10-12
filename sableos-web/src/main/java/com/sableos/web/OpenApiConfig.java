package com.sableos.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 文档元信息（Swagger UI: /swagger-ui.html，spec: /v3/api-docs）。 */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI sableosOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("SableOS API")
                .description("核心阶段 REST API：会话管理、Agent 调用、信息查询、系统状态；统一响应信封见 http-api 契约")
                .version("v1"));
  }
}
