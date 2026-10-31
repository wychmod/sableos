package com.sableos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sableos.core.agent.AgentLifecycleService;
import com.sableos.core.agent.AgentStore;
import com.sableos.core.memory.MemoryProvider;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.provider.ProviderNotConfiguredException;
import com.sableos.web.GlobalExceptionHandler;
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

/** 课件《第30节》验收 harness：AgentApiControllerTest——薄门面转发、冲突 400、不存在 404、统一信封、生成的 503 透传。 */
class AgentApiControllerTest {

  private static final String AGENT = "demo";

  @TempDir Path workspace;

  private AgentLifecycleService lifecycle;
  private ProfileRegistry registry;
  private AgentStore agentStore;
  private MemoryProvider memoryProvider;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    lifecycle = mock(AgentLifecycleService.class);
    registry = new ProfileRegistry();
    agentStore = new AgentStore(workspace);
    memoryProvider = mock(MemoryProvider.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AgentApiController(
                    mock(com.sableos.core.session.SessionManager.class),
                    registry,
                    lifecycle,
                    agentStore,
                    mock(AgentCallExecutor.class),
                    memoryProvider))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("列表转发到 lifecycle，空清单返回空数组")
  void list_delegatesToLifecycle() throws Exception {
    when(lifecycle.list()).thenReturn(List.of());

    mvc.perform(get("/api/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  @DisplayName("详情：未注册 → 404")
  void get_unknown_returnsNotFound() throws Exception {
    mvc.perform(get("/api/v1/agents/ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("详情：已注册返回公开字段 + 文件树")
  void get_known_returnsDetail() throws Exception {
    registry.register(profile(AGENT));
    agentStore.writeAll(AGENT, Map.of("AGENT.md", "x"));

    mvc.perform(get("/api/v1/agents/" + AGENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.agent.name").value(AGENT))
        .andExpect(jsonPath("$.data.agent.provider").value("mock"))
        .andExpect(jsonPath("$.data.files[?(@.name == 'AGENT.md')]").exists());
  }

  @Test
  @DisplayName("创建：同名冲突由 lifecycle 抛 400，透传为 400")
  void create_conflict_returnsBadRequest() throws Exception {
    when(lifecycle.create(anyString(), any()))
        .thenThrow(new IllegalArgumentException("Agent 已存在：" + AGENT));

    mvc.perform(
            post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + AGENT + "\",\"description\":\"重复\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("创建：name 空白在门面就被拒，不触达 lifecycle")
  void create_blankName_rejectedBeforeLifecycle() throws Exception {
    mvc.perform(
            post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \",\"description\":\"x\"}"))
        .andExpect(status().isBadRequest());

    verify(lifecycle, never()).create(anyString(), any());
  }

  @Test
  @DisplayName("更新：未注册 → 404")
  void update_unknown_returnsNotFound() throws Exception {
    mvc.perform(
            put("/api/v1/agents/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("删除：转发到 lifecycle.delete")
  void delete_delegatesToLifecycle() throws Exception {
    registry.register(profile(AGENT));

    mvc.perform(delete("/api/v1/agents/" + AGENT)).andExpect(status().isOk());

    verify(lifecycle).delete(AGENT);
  }

  @Test
  @DisplayName("生成：模型未配置 → 503 且 message 可读")
  void generateFiles_notConfigured_returns503() throws Exception {
    when(lifecycle.generateFiles(anyString(), any()))
        .thenThrow(new ProviderNotConfiguredException("未配置生成用模型（sableos.author.model）"));

    mvc.perform(
            post("/api/v1/agents/" + AGENT + "/generate-files")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"每天早上查天气\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503))
        .andExpect(
            jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("sableos.author.model")));
  }

  @Test
  @DisplayName("生成：描述为空在门面就被拒")
  void generateFiles_blankDescription_rejected() throws Exception {
    mvc.perform(
            post("/api/v1/agents/" + AGENT + "/generate-files")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"\"}"))
        .andExpect(status().isBadRequest());

    verify(lifecycle, never()).generateFiles(anyString(), any());
  }

  @Test
  @DisplayName("保存文件：必须含 AGENT.md")
  void saveFiles_withoutAgentMd_rejected() throws Exception {
    mvc.perform(
            post("/api/v1/agents/" + AGENT + "/files")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":{}}"))
        .andExpect(status().isBadRequest());

    verify(lifecycle, never()).saveFiles(anyString(), any());
  }

  @Test
  @DisplayName("记忆：返回该 Agent 的记忆段，无记忆为空串")
  void memory_returnsSection() throws Exception {
    Profile profile = profile(AGENT);
    registry.register(profile);
    when(memoryProvider.memorySection(profile)).thenReturn(null);

    mvc.perform(get("/api/v1/agents/" + AGENT + "/memory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.profile").value(AGENT))
        .andExpect(jsonPath("$.data.content").value(""));
  }

  @Test
  @DisplayName("记忆：未注册 → 404")
  void memory_unknown_returnsNotFound() throws Exception {
    mvc.perform(get("/api/v1/agents/ghost/memory")).andExpect(status().isNotFound());
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
