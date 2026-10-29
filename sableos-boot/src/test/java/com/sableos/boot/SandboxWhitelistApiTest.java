package com.sableos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sableos.cli.SableOsRuntime;
import com.sableos.core.sandbox.SandboxWhitelistAdmin;
import com.sableos.core.sandbox.WhitelistEntry;
import com.sableos.core.sandbox.WhitelistType;
import com.sableos.tool.sandbox.ActionType;
import com.sableos.tool.sandbox.SandboxAction;
import com.sableos.tool.sandbox.SandboxViolationException;
import com.sableos.tool.sandbox.WhitelistSandbox;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 端到端：真实装配根 + 真 Flyway/SQLite + MockMvc。
 *
 * <p>守点：CRUD 全链路生效、经 HTTP 新增的条目对 {@link WhitelistSandbox#enforce} 立即可见、删除后立即拒绝。
 *
 * <p>「开关关闭返回 404」放在 {@link SandboxWhitelistDisabledTest}——它需要另一套上下文属性。
 */
@SpringBootTest(classes = SableOsRuntime.class)
@AutoConfigureMockMvc
class SandboxWhitelistApiTest {

  private static final String DB_URL = "jdbc:sqlite:" + tempDbPath();
  private static final String WORKSPACE_DIR = tempWorkspacePath();

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DB_URL);
    registry.add("workspace.root", () -> WORKSPACE_DIR);
    registry.add("file.allowed_paths[0]", () -> WORKSPACE_DIR);
  }

  private static String tempDbPath() {
    try {
      Path path = Files.createTempFile("sableos-whitelist-it-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String tempWorkspacePath() {
    try {
      Path path = Files.createTempDirectory("sableos-whitelist-workspace-");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private SandboxWhitelistAdmin admin;
  @Autowired private WhitelistSandbox sandbox;

  @Test
  @DisplayName("经 HTTP 新增条目后_enforce 立即放行_删除后立即拒绝")
  void addViaHttp_takesEffectOnEnforce() throws Exception {
    Path dir = Files.createTempDirectory("sableos-whitelist-target-");
    dir.toFile().deleteOnExit();
    Path file = dir.resolve("note.txt");

    String body =
        "{\"type\":\"FILE_PATH\",\"value\":\""
            + dir.toString().replace("\\", "\\\\")
            + "\",\"description\":\"it\"}";

    String response =
        mvc.perform(
                post("/api/v1/sandbox/whitelist")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();

    long id = Long.parseLong(com.jayway.jsonpath.JsonPath.read(response, "$.data.id").toString());

    assertDoesNotThrow(
        () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, file.toString())));

    mvc.perform(delete("/api/v1/sandbox/whitelist/" + id)).andExpect(status().isOk());

    assertThatThrownBy(
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, file.toString())))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  @DisplayName("列表与单条详情返回完整字段")
  void listAndGet_returnSeededEntries() throws Exception {
    var seeded = admin.list(WhitelistType.FILE_PATH);
    assertThat(seeded).isNotEmpty();

    long id = seeded.get(0).id();
    mvc.perform(get("/api/v1/sandbox/whitelist/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(id));
  }

  @Test
  @DisplayName("重复新增返回 409")
  void duplicateViaHttp_returns409() throws Exception {
    WhitelistEntry existing =
        admin.add(
            new com.sableos.core.sandbox.WhitelistCommand(
                WhitelistType.HTTP_DOMAIN, "api.it.example.com", null));

    mvc.perform(
            post("/api/v1/sandbox/whitelist")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"HTTP_DOMAIN\",\"value\":\"API.IT.EXAMPLE.COM\"}"))
        .andExpect(status().isConflict());

    admin.delete(existing.id());
  }

  @Test
  @DisplayName("非法域名经 HTTP 返回 400 且不回显输入")
  void invalidDomain_returns400WithoutEcho() throws Exception {
    mvc.perform(
            post("/api/v1/sandbox/whitelist")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"HTTP_DOMAIN\",\"value\":\"http://evil.example.com/x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString("evil.example.com"))));
  }

  @Test
  @DisplayName("配置里的白名单在首次启动时被导入并生效")
  void seededFromConfiguration_isEffective() {
    // WORKSPACE_DIR 通过 file.allowed_paths[0] 注入，首次启动应已导入
    assertThat(admin.list(WhitelistType.FILE_PATH)).extracting(WhitelistEntry::value).isNotEmpty();

    assertDoesNotThrow(
        () ->
            sandbox.enforce(
                new SandboxAction(
                    ActionType.FILE_READ, Path.of(WORKSPACE_DIR).resolve("x.txt").toString())));
  }
}
