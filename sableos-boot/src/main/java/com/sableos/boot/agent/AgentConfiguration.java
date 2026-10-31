package com.sableos.boot.agent;

import com.sableos.core.agent.AgentLifecycleService;
import com.sableos.core.agent.AgentLoader;
import com.sableos.core.agent.AgentStore;
import com.sableos.core.agent.WorkspaceWatcher;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.scheduler.AgentScheduler;
import com.sableos.core.tool.SableTool;
import com.sableos.provider.ProvidersProperties;
import com.sableos.tool.ToolRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;

/** Agent 装配：启动期全量扫描 {@code .sableos/agents/} 并派生 Profile 注册表（坏目录不阻断启动）。 */
@Configuration
public class AgentConfiguration {

  /**
   * 工具名单以 {@code Set<String>} 传入而非 {@code ToolRegistry} 对象：core 不得依赖 tool 模块（ArchitectureTest
   * 红线），且该名单只用于「未注册工具告警」，不参与派生校验。
   */
  @Bean
  public AgentLoader agentLoader(
      ProvidersProperties providersProperties,
      ToolRegistry toolRegistry,
      @Value("${workspace.root:.sableos}") String workspaceRoot) {
    Set<String> knownProviders =
        providersProperties.providers().stream()
            .map(ProvidersProperties.Provider::name)
            .collect(Collectors.toSet());
    Set<String> knownTools =
        toolRegistry.all().stream().map(SableTool::getName).collect(Collectors.toSet());
    return new AgentLoader(Path.of(workspaceRoot, "agents"), knownProviders, knownTools);
  }

  @Bean
  public ProfileRegistry profileRegistry(AgentLoader agentLoader) {
    return agentLoader.loadAll();
  }

  /** Agent 目录的文件读写（第 30 节）：工作区根跟随 {@code workspace.root}，与 agents / skills / SQLite 同一解析结果。 */
  @Bean
  public AgentStore agentStore(@Value("${workspace.root:.sableos}") String workspaceRoot) {
    return new AgentStore(Path.of(workspaceRoot));
  }

  /**
   * Agent 生命周期编排（第 30 节）：三条录入路径共用同一段登记代码。
   *
   * <p>生成用的 provider 缺省取 {@code sableos.providers} 首个（清单顺序即运维意图，不是类型扫描）；model 必须由 {@code
   * sableos.author.model} 显式给出——系统没有「默认模型」概念，留空时生成端点返回 503 而不是向模型端点发空模型名。
   *
   * @param providerService 按需注入：无 tool / 无 provider 的轻量测试上下文里它可以缺席
   */
  @Bean
  public AgentLifecycleService agentLifecycleService(
      AgentLoader agentLoader,
      ProfileRegistry profileRegistry,
      AgentScheduler agentScheduler,
      AgentStore agentStore,
      ProvidersProperties providersProperties,
      ObjectProvider<ProviderService> providerService,
      ToolRegistry toolRegistry,
      @Value("${sableos.author.provider:}") String authorProvider,
      @Value("${sableos.author.model:}") String authorModel) {
    String firstProvider =
        providersProperties.providers().stream()
            .map(ProvidersProperties.Provider::name)
            .filter(name -> name != null && !name.isBlank())
            .min(String::compareToIgnoreCase)
            .orElse("");
    List<String> toolNames =
        toolRegistry.all().stream().map(SableTool::getName).collect(Collectors.toList());
    return new AgentLifecycleService(
        agentLoader,
        profileRegistry,
        agentScheduler,
        agentStore,
        providerService.getIfAvailable(),
        firstProvider,
        authorProvider,
        authorModel,
        toolNames);
  }

  /**
   * 监听循环专属的守护线程执行器（第 30 节）：单线程保证事件顺序，daemon 保证 {@code chat} 跑完进程能退出；{@code serve} / {@code gateway}
   * 常驻靠主线程保活。
   *
   * <p>不用虚拟线程执行器：监听循环是「一次 run 永不返回」的长驻任务，与每请求一虚拟线程的模型不同类。
   */
  @Bean(destroyMethod = "shutdown")
  public ExecutorService workspaceWatcherExecutor() {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "sableos-workspace-watcher");
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * 实时监听 {@code agents/}（第 30 节）：启动后的新增 / 改动 / 删除都走同一段登记与注销。
   *
   * <p>{@code initMethod=start} 在装配完成后挂载；启动失败只 WARN，不阻断服务启动（工作区根不可写时创建接口仍会在调用时报错）。
   */
  @Bean(initMethod = "start")
  public WorkspaceWatcher workspaceWatcher(
      AgentLifecycleService agentLifecycleService,
      ExecutorService workspaceWatcherExecutor,
      @Value("${workspace.root:.sableos}") String workspaceRoot) {
    return new WorkspaceWatcher(
        agentLifecycleService, Path.of(workspaceRoot), workspaceWatcherExecutor);
  }

  /**
   * 优雅关闭：{@code ContextClosedEvent} 先于执行器的 SmartLifecycle 停机发布，此处关掉 {@link WatchService} 让监听循环立刻退出；
   * 否则 {@code take()} 不醒，执行器 stop 要等满超时。
   */
  @Bean
  public ApplicationListener<ContextClosedEvent> watcherGracefulShutdown(
      ObjectProvider<WorkspaceWatcher> workspaceWatcher) {
    return event -> workspaceWatcher.ifAvailable(WorkspaceWatcher::stop);
  }
}
