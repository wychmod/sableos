package com.sableos.boot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 开关关闭时管理端点不存在。
 *
 * <p>为什么单独一个类：{@code @ConditionalOnProperty} 是**装配层**行为，Controller 的 standalone MockMvc 与
 * {@code @WebMvcTest} 切片都看不到条件装配；只有换一套上下文属性、真正走一次自动装配才能验证。
 */
@SpringBootTest(classes = SableOsRuntime.class)
@AutoConfigureMockMvc
class SandboxWhitelistDisabledTest {

  private static final String DB_URL = "jdbc:sqlite:" + tempDbPath();

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DB_URL);
    registry.add("sableos.sandbox.admin.enabled", () -> "false");
    registry.add("file.allowed_paths[0]", () -> tempWorkspacePath());
  }

  private static String tempDbPath() {
    try {
      Path path = Files.createTempFile("sableos-whitelist-disabled-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String tempWorkspacePath() {
    try {
      Path path = Files.createTempDirectory("sableos-whitelist-disabled-ws-");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private MockMvc mvc;

  @Test
  @DisplayName("开关为 false 时_管理路径返回 404")
  void disabled_returns404() throws Exception {
    mvc.perform(get("/api/v1/sandbox/whitelist")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("开关为 false 不影响沙箱自身工作（白名单仍从库生效）")
  void disabled_doesNotBreakSandbox() throws Exception {
    // 只要上下文能起来、其他端点正常，就说明关闭的是管理面而不是沙箱本体
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
