package com.sableos.core.agent;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.scheduler.AgentScheduler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 课件《第30节》验收 harness：WorkspaceWatcherTest——往 agents/ 手工丢一个 Agent 目录 → 不重启即可用； 删目录 →
 * 注销；坏目录只告警、不拖垮监听。
 *
 * <p>事件分发走包级 {@code dispatch / handleChange}，不依赖真实事件时序；另有一个真 {@link WatchService} 的端到端用例。
 */
class WorkspaceWatcherTest {

  private static final String PROVIDER = "mock";
  private static final String AGENT_MD =
      """
      ---
      name: %s
      description: 监听测试
      provider:
        name: %s
        model: mock-model
      tools: []
      settings:
        max_iterations: 5
        max_history_turns: 10
      ---

      你好。
      """;

  @TempDir Path workspace;

  private ProfileRegistry registry;
  private AgentLifecycleService lifecycle;
  private AgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    AgentLoader loader = new AgentLoader(workspace.resolve("agents"), Set.of(PROVIDER), Set.of());
    registry = new ProfileRegistry();
    scheduler = mock(AgentScheduler.class);
    lifecycle =
        new AgentLifecycleService(
            loader,
            registry,
            scheduler,
            new AgentStore(workspace),
            mock(ProviderService.class),
            PROVIDER,
            PROVIDER,
            "",
            List.of());
  }

  private WorkspaceWatcher watcher() {
    return new WorkspaceWatcher(lifecycle, workspace, Runnable::run);
  }

  @Test
  @DisplayName("往 agents/ 丢一个合法 Agent 目录 → 监听触发登记，不重启即可用")
  void newAgentDirectory_registersWithoutRestart() throws IOException {
    Path agentDir = writeAgent("manual", "manual");

    watcher().handleChange(agentDir, ENTRY_CREATE);

    assertThat(registry.exists("manual")).isTrue();
    assertThat(registry.get("manual").name()).isEqualTo("manual");
  }

  @Test
  @DisplayName("目录被删 → 注销该 Agent")
  void removedDirectory_unregisters() throws IOException {
    Path agentDir = writeAgent("gone", "gone");
    lifecycle.register(agentDir);
    assertThat(registry.exists("gone")).isTrue();

    watcher().handleChange(agentDir, ENTRY_DELETE);

    assertThat(registry.exists("gone")).isFalse();
  }

  @Test
  @DisplayName("单个坏目录只告警并跳过，其余 Agent 不受影响")
  void brokenDirectory_doesNotBreakOthers() throws IOException {
    Path good = writeAgent("good", "good");
    Path broken = workspace.resolve("agents").resolve("broken");
    Files.createDirectories(broken);
    Files.writeString(broken.resolve("AGENT.md"), "这不是合法定义");

    WorkspaceWatcher watcher = watcher();
    watcher.handleChange(good, ENTRY_CREATE);
    watcher.handleChange(broken, ENTRY_CREATE); // 内部吞异常，只记 WARN

    assertThat(registry.exists("good")).isTrue();
    assertThat(registry.exists("broken")).isFalse();
  }

  @Test
  @DisplayName("修改 AGENT.md → 按新定义刷新，旧定时先注销")
  void modifiedAgent_refreshesAndRelinksScheduler() throws IOException {
    Path agentDir = writeAgent("edit", "edit");
    lifecycle.register(agentDir);
    Profile before = registry.get("edit");

    Files.writeString(agentDir.resolve("AGENT.md"), agentMd("edit", "改过的描述"));
    watcher().handleChange(agentDir, ENTRY_MODIFY);

    assertThat(registry.get("edit").description()).isEqualTo("改过的描述");
    verify(scheduler).unregisterProfile(before);
  }

  @Test
  @DisplayName("根目录事件：只有子目录的新增与删除被处理，MODIFY 忽略")
  void rootDirectoryEvents_onlyCreateAndDeleteOfDirectories() throws IOException {
    Path agentsDir = workspace.resolve("agents");
    Path agentDir = writeAgent("rooted", "rooted");
    AgentLifecycleService spied = mock(AgentLifecycleService.class);
    WorkspaceWatcher watcher = new WorkspaceWatcher(spied, workspace, Runnable::run);
    try (java.nio.file.WatchService service = agentsDir.getFileSystem().newWatchService()) {
      watcher.dispatch(service, agentsDir, agentDir, ENTRY_CREATE);
      watcher.dispatch(service, agentsDir, agentDir, ENTRY_DELETE);
      watcher.dispatch(service, agentsDir, agentDir, ENTRY_MODIFY);
    }

    verify(spied).refresh(agentDir);
    verify(spied).unregisterByDir(agentDir);
    // MODIFY 不产生第三次调用
    verify(spied, org.mockito.Mockito.times(1)).refresh(any());
  }

  @Test
  @DisplayName("子目录里的非 AGENT.md 变更被忽略")
  void childDirectory_nonAgentFileIgnored() throws IOException {
    Path agentDir = writeAgent("skip", "skip");
    AgentLifecycleService spied = mock(AgentLifecycleService.class);
    WorkspaceWatcher watcher = new WorkspaceWatcher(spied, workspace, Runnable::run);

    watcher.dispatch(null, agentDir, agentDir.resolve("MEMORY.md"), ENTRY_MODIFY);

    verify(spied, never()).refresh(any());
    verify(spied, never()).unregisterByDir(any());
  }

  @Test
  @DisplayName("子目录里的 AGENT.md 改动经 dispatch 转发为刷新（大小写不敏感）")
  void childDirectory_agentFileModifyIsDispatched() throws IOException {
    Path agentDir = writeAgent("edit-dispatch", "edit-dispatch");
    AgentLifecycleService spied = mock(AgentLifecycleService.class);
    WorkspaceWatcher watcher = new WorkspaceWatcher(spied, workspace, Runnable::run);

    watcher.dispatch(null, agentDir, agentDir.resolve("AGENT.md"), ENTRY_MODIFY);
    watcher.dispatch(null, agentDir, agentDir.resolve("agent.md"), ENTRY_MODIFY);

    // 两个大小写都要命中：AGENT.md 与 agent.md 在大小写不敏感文件系统上是同一个文件
    verify(spied, org.mockito.Mockito.times(2)).refresh(agentDir);
  }

  @Test
  @DisplayName("子目录里的 AGENT.md 删除经 dispatch 转发为注销")
  void childDirectory_agentFileDeleteIsDispatched() throws IOException {
    Path agentDir = writeAgent("delete-dispatch", "delete-dispatch");
    AgentLifecycleService spied = mock(AgentLifecycleService.class);
    WorkspaceWatcher watcher = new WorkspaceWatcher(spied, workspace, Runnable::run);

    watcher.dispatch(null, agentDir, agentDir.resolve("AGENT.md"), ENTRY_DELETE);

    verify(spied).unregisterByDir(agentDir);
    verify(spied, never()).refresh(any());
  }

  @Test
  @DisplayName("大小写变体（Agent.MD / aGeNt.Md）同样触发刷新，非定义文件仍忽略")
  void childDirectory_allCaseVariantsAreDispatched() throws IOException {
    Path agentDir = writeAgent("case-dispatch", "case-dispatch");
    AgentLifecycleService spied = mock(AgentLifecycleService.class);
    WorkspaceWatcher watcher = new WorkspaceWatcher(spied, workspace, Runnable::run);

    watcher.dispatch(null, agentDir, agentDir.resolve("Agent.MD"), ENTRY_MODIFY);
    watcher.dispatch(null, agentDir, agentDir.resolve("aGeNt.Md"), ENTRY_CREATE);
    // 这几类是常见的「长得像但不是定义」的文件名，必须继续忽略
    watcher.dispatch(null, agentDir, agentDir.resolve("AGENTS.md"), ENTRY_MODIFY);
    watcher.dispatch(null, agentDir, agentDir.resolve("CLAUDE.md"), ENTRY_MODIFY);
    watcher.dispatch(null, agentDir, agentDir.resolve("AGENT.md.bak"), ENTRY_MODIFY);

    verify(spied, org.mockito.Mockito.times(2)).refresh(agentDir);
    verify(spied, never()).unregisterByDir(any());
  }

  @Test
  @DisplayName("端到端：真 WatchService 收到新目录事件后 Agent 出现在注册表")
  void realWatchService_registersNewDirectory() throws Exception {
    try (ExecutorServiceHolder holder = new ExecutorServiceHolder()) {
      WorkspaceWatcher watcher = new WorkspaceWatcher(lifecycle, workspace, holder.executor);
      watcher.start();
      try {
        Path agentDir = writeAgent("live", "live");
        awaitRegistered("live", Duration.ofSeconds(30));
        assertThat(registry.exists("live")).isTrue();
      } finally {
        watcher.stop();
      }
    }
  }

  @Test
  @DisplayName("目录先建、AGENT.md 后落盘：重试后仍能登记（真实时序）")
  void newDirectoryBeforeAgentFile_isRegisteredAfterRetry() throws Exception {
    Path agentsDir = workspace.resolve("agents");
    Files.createDirectories(agentsDir);
    Path agentDir = agentsDir.resolve("late");
    Files.createDirectories(agentDir);

    Thread writer =
        new Thread(
            () -> {
              try {
                Thread.sleep(150);
                Files.writeString(agentDir.resolve("AGENT.md"), agentMd("late", "迟到的定义"));
              } catch (Exception ignored) {
                // 写失败会让断言自然失败
              }
            });
    writer.start();
    watcher().registerNewDirectory(agentDir);
    writer.join();

    assertThat(registry.exists("late")).isTrue();
  }

  @Test
  @DisplayName("目录里始终没有 AGENT.md：重试耗尽后只告警跳过")
  void directoryWithoutAgentFile_isSkipped() throws Exception {
    Path agentDir = workspace.resolve("agents").resolve("empty-dir");
    Files.createDirectories(agentDir);

    watcher().registerNewDirectory(agentDir);

    assertThat(registry.exists("empty-dir")).isFalse();
  }

  /**
   * 轮询等登记，超时即失败。
   *
   * <p>超时上限 30 秒而非 10 秒：真实 {@link java.nio.file.WatchService} 的事件投递依赖 inotify 队列与监听线程的调度，
   * 在低核数机器上跑全量构建（其他模块测试并行占 CPU）时，10 秒会踩线——本用例曾在 {@code mvn clean verify} 全量 构建时偶发失败、单独跑 5
   * 轮全绿。放宽后仍保留 30 秒上限，真卡死不会变成无限等。
   *
   * <p>超时抛 {@link AssertionError} 而不是静默返回：原来的写法让调用方断言只说「期望 true 实际 false」，
   * 看不出是「等超时了」还是「注册了但名字不对」，排查时得重跑才能定位。
   */
  private void awaitRegistered(String name, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (registry.exists(name)) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError(
        "等待 "
            + timeout.toSeconds()
            + " 秒后 Agent 仍未登记：name="
            + name
            + "，当前已登记="
            + registry.all().stream().map(Profile::name).toList()
            + "（可能原因：WatchService 事件未投递、inotify 实例耗尽、AGENT.md 解析失败）");
  }

  // ---------------------------------------------------------------- 辅助

  private Path writeAgent(String dirName, String agentName) throws IOException {
    Path dir = workspace.resolve("agents").resolve(dirName);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("AGENT.md"), agentMd(agentName, "监听测试"));
    return dir;
  }

  private static String agentMd(String name, String description) {
    return AGENT_MD
        .formatted(name, PROVIDER)
        .replace("description: 监听测试", "description: " + description);
  }

  /** 单守护线程执行器包装：测试结束随 try-with-resources 关闭，避免悬挂线程。 */
  private static final class ExecutorServiceHolder implements AutoCloseable {

    private final java.util.concurrent.ExecutorService pool;
    private final Executor executor;

    private ExecutorServiceHolder() {
      this.pool =
          java.util.concurrent.Executors.newSingleThreadExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "test-watcher");
                thread.setDaemon(true);
                return thread;
              });
      this.executor = pool;
    }

    @Override
    public void close() {
      pool.shutdownNow();
    }
  }
}
