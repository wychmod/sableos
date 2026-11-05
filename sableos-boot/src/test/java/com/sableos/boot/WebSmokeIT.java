package com.sableos.boot;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sableos.cli.SableOsRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 真实装配冒烟（26 节验收 harness）：不起模型、不碰外网，验证 Web 层 Bean 装配、JPA 仓库扫描、CORS 与 OpenAPI。
 *
 * <p>放在 boot 而不是 web：真正的装配根是 {@code sableos-cli} 的 {@link SableOsRuntime}，web 模块内没有
 * {@code @SpringBootConfiguration}，切片测试无法起真实上下文（008 节已踩过同类问题）。它同时守住“18 节 Found 0 repositories 的坑若在
 * web 模块复发，这里第一时间红”。
 */
@SpringBootTest(classes = SableOsRuntime.class)
@AutoConfigureMockMvc
class WebSmokeIT {

  private static final String DB_URL = "jdbc:sqlite:" + tempFile(".db");
  private static final String WORKSPACE_DIR = tempDir();

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DB_URL);
    registry.add("workspace.root", () -> WORKSPACE_DIR);
  }

  @Autowired private MockMvc mvc;

  @Test
  @DisplayName("健康检查_真实链路可达")
  void healthIsReachable() throws Exception {
    mvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("ok"));
  }

  @Test
  @DisplayName("系统信息_无provider时返回空数组")
  void infoWithoutProvidersReturnsEmptyArray() throws Exception {
    mvc.perform(get("/api/v1/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.app").isNotEmpty())
        .andExpect(jsonPath("$.data.providers").isArray())
        .andExpect(jsonPath("$.data.providers").isEmpty());
  }

  @Test
  @DisplayName("Agent清单_空工作区返回空数组")
  void profilesAreEmptyInCleanWorkspace() throws Exception {
    mvc.perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  @DisplayName("工具清单_真实注册表非空")
  void toolsAreRegistered() throws Exception {
    mvc.perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data.length()", greaterThan(0)))
        .andExpect(jsonPath("$.data[0].name").isNotEmpty())
        .andExpect(jsonPath("$.data[0].inputSchema").isNotEmpty());
  }

  @Test
  @DisplayName("Agent 专属记忆_未注册 Agent 返回 404")
  void memoryOfUnknownAgentReturnsNotFound() throws Exception {
    mvc.perform(get("/api/v1/agents/ghost/memory"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  @DisplayName("OpenAPI文档_包含会话端点")
  void openApiDocumentListsEndpoints() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/api/v1/sessions")))
        .andExpect(content().string(containsString("/api/v1/agents/{name}/invoke")));
  }

  @Test
  @DisplayName("CORS预检_api路径允许任意来源")
  void corsPreflightAllowsAnyOriginForApiPaths() throws Exception {
    mvc.perform(
            options("/api/v1/sessions")
                .header(HttpHeaders.ORIGIN, "http://example.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"));
  }

  private static String tempFile(String suffix) {
    try {
      Path path = Files.createTempFile("sableos-web-it-", suffix);
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String tempDir() {
    try {
      Path path = Files.createTempDirectory("sableos-web-workspace-");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
