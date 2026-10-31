package com.sableos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.provider.ProviderNotConfiguredException;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.scheduler.AgentScheduler;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * 课件《第30节》验收 harness：GenerateTest——一句话生成草稿的边界。
 *
 * <ul>
 *   <li>产出的 AGENT.md 能被 {@link AgentLoader#parse} 解析（草稿能过 = 写盘能过）；
 *   <li>只生成、不落盘、不注册；
 *   <li>模型输出非法 400、模型未配置 503；
 *   <li>生成提示词里带真实工具清单与目标 provider/model，杜绝模型编造能力。
 * </ul>
 */
class GenerateTest {

  private static final String PROVIDER = "mock";
  private static final String AGENT_MD =
      """
      ---
      name: %s
      description: 生成出来的 Agent
      provider:
        name: %s
        model: mock-model
      tools:
        - save_memory
      settings:
        max_iterations: 8
        max_history_turns: 20
      ---

      被触发时先说一句「收到」。
      """;

  @TempDir Path workspace;

  private ProfileRegistry registry;
  private AgentStore store;
  private AgentScheduler scheduler;
  private ProviderService providerService;
  private AgentLifecycleService service;

  @BeforeEach
  void setUp() {
    registry = new ProfileRegistry();
    store = mock(AgentStore.class);
    scheduler = mock(AgentScheduler.class);
    providerService = mock(ProviderService.class);
    service =
        new AgentLifecycleService(
            new AgentLoader(workspace.resolve("agents"), Set.of(PROVIDER), Set.of("save_memory")),
            registry,
            scheduler,
            store,
            providerService,
            PROVIDER,
            PROVIDER,
            "author-model",
            List.of("save_memory", "http_get"));
  }

  @Test
  @DisplayName("生成的 AGENT.md 可被同一套解析器接受")
  void generatedAgentMd_isParseable() {
    stubModel(agentMd("draft"));

    Map<String, String> files = service.generateFiles("draft", "每天早上九点查天气");

    Profile parsed =
        new AgentLoader(workspace.resolve("agents"), Set.of(PROVIDER), Set.of())
            .parse(files.get("AGENT.md"), "draft");
    assertThat(parsed.name()).isEqualTo("draft");
    assertThat(parsed.provider().model()).isEqualTo("mock-model");
  }

  @Test
  @DisplayName("生成不落盘、不注册、不登记定时")
  void generate_neverWritesOrRegisters() {
    stubModel(agentMd("draft"));

    service.generateFiles("draft", "需求");

    verify(store, never()).writeAll(anyString(), any());
    verify(scheduler, never()).registerProfile(any());
    assertThat(registry.exists("draft")).isFalse();
  }

  @Test
  @DisplayName("模型未配置生成用 model → 503 语义异常，且不发起调用")
  void missingAuthorModel_throwsNotConfigured() {
    AgentLifecycleService withoutModel =
        new AgentLifecycleService(
            new AgentLoader(workspace.resolve("agents"), Set.of(PROVIDER), Set.of()),
            registry,
            scheduler,
            store,
            providerService,
            PROVIDER,
            PROVIDER,
            "",
            List.of());

    assertThatThrownBy(() -> withoutModel.generateFiles("draft", "需求"))
        .isInstanceOf(ProviderNotConfiguredException.class)
        .hasMessageContaining("sableos.author.model");
    verify(providerService, never()).chat(anyString(), any(), any());
  }

  @Test
  @DisplayName("模型输出不是合法定义 → 400 语义异常")
  void invalidModelOutput_throws() {
    stubModel("抱歉，我无法完成这个请求。");

    assertThatThrownBy(() -> service.generateFiles("draft", "需求"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("多文件输出按 ===FILE:=== 分隔解析，代码围栏被剥离")
  void multiFileOutput_isSplit() {
    String text =
        "```markdown\n"
            + "===FILE: AGENT.md===\n"
            + agentMd("draft")
            + "===FILE: scripts/fetch.py===\n"
            + "print('hi')\n"
            + "```\n";
    stubModel(text);

    Map<String, String> files = service.generateFiles("draft", "需求");

    assertThat(files).containsKeys("AGENT.md", "scripts/fetch.py");
    assertThat(files.get("scripts/fetch.py")).isEqualTo("print('hi')");
  }

  @Test
  @DisplayName("产出 skills/ 路径被拒（skills 只许软链）")
  void skillsPath_rejected() {
    stubModel(
        "===FILE: AGENT.md===\n" + agentMd("draft") + "===FILE: skills/x/SKILL.md===\nbody\n");

    assertThatThrownBy(() -> service.generateFiles("draft", "需求"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非法或保留路径");
  }

  @Test
  @DisplayName("产出 ../ 跳出路径被拒")
  void traversalPath_rejected() {
    stubModel("===FILE: AGENT.md===\n" + agentMd("draft") + "===FILE: ../evil.sh===\nx\n");

    assertThatThrownBy(() -> service.generateFiles("draft", "需求"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非法或保留路径");
  }

  @Test
  @DisplayName("生成提示词带上真实工具清单，杜绝编造")
  void prompt_listsRealTools() {
    stubModel(agentMd("draft"));

    service.generateFiles("draft", "需求");

    ArgumentCaptor<ProviderRequest> captor = ArgumentCaptor.forClass(ProviderRequest.class);
    verify(providerService).chat(anyString(), any(), captor.capture());
    String prompt = captor.getValue().content();
    assertThat(prompt).contains("save_memory").contains("http_get").contains("需求");
  }

  @Test
  @DisplayName("已存在的 Agent 生成时沿用其 provider，保证结果仍可跑")
  void existingAgent_keepsItsProvider() {
    registry.register(
        new Profile(
            "draft",
            "旧描述",
            null,
            new Profile.ProviderRef("dashscope", "qwen-max", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(null, null)));
    // 返回的 AGENT.md 用原 provider 才可能通过 parse；这里只断言提示词里带的是既有 provider
    stubModel(agentMd("draft"));

    service.generateFiles("draft", "需求");

    ArgumentCaptor<ProviderRequest> captor = ArgumentCaptor.forClass(ProviderRequest.class);
    verify(providerService).chat(anyString(), any(), captor.capture());
    assertThat(captor.getValue().content()).contains("dashscope").contains("qwen-max");
  }

  @Test
  @DisplayName("需求描述为空 → 400 语义异常，不发起调用")
  void blankDescription_rejected() {
    assertThatThrownBy(() -> service.generateFiles("draft", "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("需求描述不能为空");
    verify(providerService, never()).chat(anyString(), any(), any());
  }

  private void stubModel(String text) {
    when(providerService.chat(anyString(), any(), any(ProviderRequest.class)))
        .thenReturn(new ProviderResponse(text, List.of(), null));
  }

  private static String agentMd(String name) {
    return AGENT_MD.formatted(name, PROVIDER);
  }
}
