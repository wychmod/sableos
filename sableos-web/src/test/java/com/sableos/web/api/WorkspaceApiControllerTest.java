package com.sableos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sableos.core.agent.AgentLifecycleService;
import com.sableos.core.agent.AgentStore;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.web.GlobalExceptionHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 课件《第30节》验收 harness：WorkspaceApiControllerTest——目录树列出 agents/archive、读文件返回内容、 <b>目录穿越一律
 * 400</b>（关键回归）、写 AGENT.md 走 Agent 更新链路。
 */
class WorkspaceApiControllerTest {

  private static final String AGENT = "demo";

  @TempDir Path workspace;

  private AgentStore agentStore;
  private AgentLifecycleService lifecycle;
  private ProfileRegistry registry;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws IOException {
    agentStore = new AgentStore(workspace);
    lifecycle = mock(AgentLifecycleService.class);
    registry = new ProfileRegistry();
    registry.register(profile(AGENT));

    agentStore.writeAll(
        AGENT, Map.of("AGENT.md", "---\nname: demo\n---\n\n正文", "scripts/a.py", "print(1)"));
    Files.createDirectories(workspace.resolve("archive").resolve("old").resolve("scripts"));

    mvc =
        MockMvcBuilders.standaloneSetup(new WorkspaceApiController(agentStore, lifecycle, registry))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("目录树含 agents/archive，且能钻进 Agent 目录列文件")
  void tree_listsAgentsAndArchive() throws Exception {
    mvc.perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.directory").value(true))
        .andExpect(jsonPath("$.data.children[?(@.name == 'agents')]").exists())
        .andExpect(jsonPath("$.data.children[?(@.name == 'archive')]").exists())
        .andExpect(
            jsonPath(
                    "$.data.children[?(@.name == 'agents')].children[?(@.name == 'demo')].children[?(@.name == 'AGENT.md')]")
                .exists());
  }

  @Test
  @DisplayName("读正常文件返回内容")
  void file_returnsContent() throws Exception {
    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/scripts/a.py"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(containsString("print(1)")));
  }

  @Test
  @DisplayName("目录穿越（../ 跳出工作区）→ 400")
  void file_traversalWithParentSegments_isRejected() throws Exception {
    mvc.perform(get("/api/v1/workspace/file").param("path", "../../etc/passwd"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("绝对路径 → 400")
  void file_absolutePath_isRejected() throws Exception {
    mvc.perform(get("/api/v1/workspace/file").param("path", "/etc/passwd"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("工作区内但不在两棵子树（如 sableos.db）→ 400")
  void file_outsideBrowsableSubtrees_isRejected() throws Exception {
    Files.writeString(workspace.resolve("sableos.db"), "binary");

    mvc.perform(get("/api/v1/workspace/file").param("path", "sableos.db"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("文件不存在 → 404")
  void file_missing_returnsNotFound() throws Exception {
    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/nope.md"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("写 agents/<name>/AGENT.md 走 Agent 更新链路（不裸写盘）")
  void write_agentDefinition_goesThroughLifecycle() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("agents/demo/AGENT.md", "---\nname: demo\n---\n\n新正文")))
        .andExpect(status().isOk());

    verify(lifecycle).saveFiles(eq(AGENT), any());
  }

  @Test
  @DisplayName("写其它文件直接落盘")
  void write_otherFile_writesDirectly() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("agents/demo/notes.md", "笔记内容")))
        .andExpect(status().isOk());

    assertThat(Files.readString(workspace.resolve("agents/demo/notes.md"))).isEqualTo("笔记内容");
    verify(lifecycle, org.mockito.Mockito.never()).saveFiles(anyString(), any());
  }

  @Test
  @DisplayName("写越界路径 → 400 且不产生工作区外文件")
  void write_traversal_isRejected() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("../outside.md", "x")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(Files.exists(workspace.getParent().resolve("outside.md"))).isFalse();
  }

  private static String json(String path, String content) {
    return "{\"path\":\"" + path.replace("\\", "\\\\") + "\",\"content\":" + quote(content) + "}";
  }

  private static String quote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private static Profile profile(String name) {
    return new Profile(
        name,
        "desc",
        null,
        new Profile.ProviderRef("mock", "mock-model", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(null, null));
  }
}
