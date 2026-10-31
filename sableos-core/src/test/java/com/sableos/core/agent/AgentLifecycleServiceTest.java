package com.sableos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

/** 课件《第30节》验收 harness：AgentLifecycleServiceTest——创建按序执行、同名先拒不写盘、失败回滚、删除定序、 更新先注销旧定时、生成只出草稿不落盘。 */
class AgentLifecycleServiceTest {

  private static final String PROVIDER = "mock";
  private static final String AGENT_MD =
      """
      ---
      name: %s
      description: 测试 Agent
      provider:
        name: %s
        model: mock-model
      tools: []
      settings:
        max_iterations: 5
        max_history_turns: 10
      ---

      被触发时说一句「收到」。
      """;

  @TempDir Path workspace;

  private AgentLoader agentLoader;
  private ProfileRegistry registry;
  private AgentScheduler scheduler;
  private AgentStore store;
  private ProviderService providerService;
  private AgentLifecycleService service;

  @BeforeEach
  void setUp() {
    agentLoader = new AgentLoader(workspace.resolve("agents"), Set.of(PROVIDER), Set.of());
    registry = new ProfileRegistry();
    scheduler = mock(AgentScheduler.class);
    store = mock(AgentStore.class);
    providerService = mock(ProviderService.class);
    service =
        new AgentLifecycleService(
            agentLoader,
            registry,
            scheduler,
            store,
            providerService,
            PROVIDER,
            PROVIDER,
            "author-model",
            List.of("save_memory", "web_search"));
  }

  private static String agentMd(String name) {
    return AGENT_MD.formatted(name, PROVIDER);
  }

  // ---------------------------------------------------------------- create

  @Test
  @DisplayName("create 按序执行：写盘 → 注册 → 登记定时")
  void create_runsInOrder() {
    when(store.writeAll(anyString(), any())).thenReturn(workspace.resolve("agents/sample"));
    // 真实派生需要文件，这里让 deriveProfile 走真实目录：先把文件写进去
    Path agentDir = workspace.resolve("agents/sample");
    writeAgentDir(agentDir, agentMd("sample"));

    Profile profile = service.create("sample", "每天早上九点查天气");

    InOrder order = inOrder(store, scheduler);
    order.verify(store).writeAll(anyString(), any());
    assertThat(profile.name()).isEqualTo("sample");
    assertThat(registry.exists("sample")).isTrue();
  }

  @Test
  @DisplayName("同名先拒，一个目录都不写")
  void create_duplicateName_writesNothing() {
    registry.register(profile("sample", List.of()));

    assertThatThrownBy(() -> service.create("sample", "重复"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已存在");

    verify(store, never()).writeAll(anyString(), any());
  }

  @Test
  @DisplayName("注册失败回滚已写目录，调度器不登记")
  void create_failure_rollsBack() {
    Path agentDir = workspace.resolve("agents/broken");
    when(store.writeAll(anyString(), any()))
        .thenAnswer(
            invocation -> {
              writeAgentDir(agentDir, "不是合法的 AGENT.md");
              return agentDir;
            });

    assertThatThrownBy(() -> service.create("broken", "坏的定义"))
        .isInstanceOf(IllegalArgumentException.class);

    verify(store).delete(agentDir);
    verify(scheduler, never()).registerProfile(any());
    assertThat(registry.exists("broken")).isFalse();
  }

  @Test
  @DisplayName("非法名字在第一步就拒")
  void create_invalidName_rejected() {
    assertThatThrownBy(() -> service.create("../evil", "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非法的 Agent 名");
    verify(store, never()).writeAll(anyString(), any());
  }

  @Test
  @DisplayName("带 schedules 的 Agent 创建后进入调度器")
  void create_withSchedules_registersScheduler() {
    Path agentDir = workspace.resolve("agents/timed");
    when(store.writeAll(anyString(), any()))
        .thenAnswer(
            invocation -> {
              writeAgentDir(agentDir, agentMdWithSchedule("timed"));
              return agentDir;
            });

    service.create("timed", "有定时");

    verify(scheduler).registerProfile(any());
  }

  // ---------------------------------------------------------------- delete

  @Test
  @DisplayName("delete 定序：注销定时 → 移出索引 → 归档")
  void delete_runsInOrder() {
    Profile existing = profile("sample", List.of());
    registry.register(existing);

    service.delete("sample");

    InOrder order = inOrder(scheduler, store);
    order.verify(scheduler).unregisterProfile(existing);
    order.verify(store).archive("sample");
    assertThat(registry.exists("sample")).isFalse();
  }

  @Test
  @DisplayName("delete 对未注册的名字幂等")
  void delete_unknown_isIdempotent() {
    service.delete("ghost");
    verify(scheduler, never()).unregisterProfile(any());
    verify(store, never()).archive(anyString());
  }

  // ---------------------------------------------------------------- update

  @Test
  @DisplayName("update 改定时：先注销旧句柄再注册新的")
  void update_schedules_relinksScheduler() {
    Profile existing = profile("sample", List.of());
    registry.register(existing);
    when(store.writeAll(anyString(), any())).thenReturn(workspace.resolve("agents/sample"));
    Path agentDir = workspace.resolve("agents/sample");
    writeAgentDir(agentDir, agentMdWithSchedule("sample"));

    service.update("sample", agentMdWithSchedule("sample"));

    InOrder order = inOrder(scheduler);
    order.verify(scheduler).unregisterProfile(existing);
    order.verify(scheduler).registerProfile(any());
  }

  @Test
  @DisplayName("update 非法定义不写坏目录")
  void update_invalidDefinition_rejectedBeforeWrite() {
    registry.register(profile("sample", List.of()));

    assertThatThrownBy(() -> service.update("sample", "没有 frontmatter"))
        .isInstanceOf(IllegalArgumentException.class);

    verify(store, never()).writeAll(anyString(), any());
  }

  // ---------------------------------------------------------------- generate

  @Test
  @DisplayName("生成只出草稿：不落盘、不注册")
  void generateFiles_neverWritesOrRegisters() {
    when(providerService.chat(anyString(), any(), any(ProviderRequest.class)))
        .thenReturn(new ProviderResponse(agentMd("draft"), List.of(), null));

    Map<String, String> files = service.generateFiles("draft", "每天早上查天气");

    assertThat(files).containsKey("AGENT.md");
    assertThat(files.get("AGENT.md")).contains("name: draft");
    verify(store, never()).writeAll(anyString(), any());
    verify(scheduler, never()).registerProfile(any());
    assertThat(registry.exists("draft")).isFalse();
  }

  @Test
  @DisplayName("模型未配置生成用 model 时明确 503 语义异常")
  void generateFiles_withoutAuthorModel_throwsNotConfigured() {
    AgentLifecycleService noModel =
        new AgentLifecycleService(
            agentLoader,
            registry,
            scheduler,
            store,
            providerService,
            PROVIDER,
            PROVIDER,
            "",
            List.of());

    assertThatThrownBy(() -> noModel.generateFiles("draft", "需求"))
        .isInstanceOf(ProviderNotConfiguredException.class)
        .hasMessageContaining("sableos.author.model");
    verify(providerService, never()).chat(anyString(), any(), any());
  }

  @Test
  @DisplayName("模型产出非法定义时 400 语义异常")
  void generateFiles_invalidDefinition_throws() {
    when(providerService.chat(anyString(), any(), any(ProviderRequest.class)))
        .thenReturn(new ProviderResponse("这不是 AGENT.md", List.of(), null));

    assertThatThrownBy(() -> service.generateFiles("draft", "需求"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("模型产出 skills/ 路径时拒绝（skills 只许软链）")
  void generateFiles_rejectsSkillsPath() {
    String text =
        "===FILE: AGENT.md===\n" + agentMd("draft") + "===FILE: skills/x/SKILL.md===\n" + "x";
    when(providerService.chat(anyString(), any(), any(ProviderRequest.class)))
        .thenReturn(new ProviderResponse(text, List.of(), null));

    assertThatThrownBy(() -> service.generateFiles("draft", "需求"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非法或保留路径");
  }

  // ---------------------------------------------------------------- 辅助

  private static void writeAgentDir(Path dir, String markdown) {
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("AGENT.md"), markdown);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String agentMdWithSchedule(String name) {
    return """
        ---
        name: %s
        description: 有定时的 Agent
        provider:
          name: %s
          model: mock-model
        tools: []
        settings:
          max_iterations: 5
          max_history_turns: 10
        schedules:
          - id: daily
            cron: "0 0 9 * * *"
            zone: Asia/Shanghai
            message: 到点执行
        ---

        被触发时说一句「收到」。
        """
        .formatted(name, PROVIDER);
  }

  private static Profile profile(String name, List<Profile.ScheduleConfig> schedules) {
    return new Profile(
        name,
        "desc",
        null,
        new Profile.ProviderRef(PROVIDER, "mock-model", null),
        List.of(),
        List.of(),
        List.of(),
        schedules,
        List.of(),
        new Profile.Settings(null, null));
  }
}
