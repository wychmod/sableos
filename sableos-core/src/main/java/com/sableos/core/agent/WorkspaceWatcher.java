package com.sableos.core.agent;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实时监听 {@code <root>/agents/}（第 30 节第二条录入路径）：目录新增 / {@code AGENT.md} 变更 / 目录删除，全部汇到 {@link
 * AgentLifecycleService} 的登记、刷新与注销——与 API 创建走同一段代码。
 *
 * <p><b>为什么维护两层 watch</b>：JDK {@code WatchService} 不递归。只盯根目录会收不到 {@code agents/<name>/AGENT.md}
 * 的增改删，于是「编辑器改一行就生效」这条验收项做不到。故本类维护 {@code WatchKey → 被监听目录} 的映射：根目录盯直接子目录的增删， 每个 Agent 子目录各自盯自己的
 * {@code AGENT.md}；新目录一出现即补挂，被删即撤挂。
 *
 * <p><b>线程模型</b>：监听循环跑在装配层提供的**单个守护线程**上（与 25 节调度线程同类的基础设施线程），不把异步编程模型引进请求链路。 逐事件
 * try/catch——单个坏目录只留一条点名 WARN，不拖垮监听线程，也不影响其余 Agent。
 *
 * <p><b>关闭</b>：{@link #stop()} 关闭 {@code WatchService}，令 {@code take()} 抛 {@link
 * ClosedWatchServiceException} 而退出循环。装配层应在 {@code ContextClosedEvent} 里调用它：该事件先于生命周期停机发布，执行器才能
 * 及时停掉而不是等满超时。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "确认误报：lifecycle 与 watcherExecutor 都是 Spring 注入的共享单例，构造注入共享同一引用正是意图；"
            + "本类不返回它们、也不暴露其内部字段。撤销条件：新增暴露这两者的 getter 或 public 字段。")
public class WorkspaceWatcher {

  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceWatcher.class);

  /** Agent 定义文件名：只有它的增 / 改 / 删才牵动该 Agent 的注册状态。 */
  private static final String AGENT_FILE = "AGENT.md";

  /** 新目录登记的重试次数与间隔：覆盖「mkdir 先到、AGENT.md 后落盘」的时序。 */
  private static final int NEW_DIR_ATTEMPTS = 5;

  private static final long NEW_DIR_RETRY_DELAY_MS = 100L;

  private final AgentLifecycleService lifecycle;
  private final Path agentsDir;
  private final Executor watcherExecutor;

  /** {@code start()} 创建、{@code stop()} 关闭（volatile：启动线程写、关闭线程读）。 */
  private volatile WatchService watchService;

  /** {@link WatchKey} → 被监听目录，把事件解析回来源目录。 */
  private final Map<WatchKey, Path> watchedDirs = new ConcurrentHashMap<>();

  public WorkspaceWatcher(AgentLifecycleService lifecycle, Path root, Executor watcherExecutor) {
    this.lifecycle = lifecycle;
    this.agentsDir = root.toAbsolutePath().normalize().resolve("agents");
    this.watcherExecutor = watcherExecutor;
  }

  /** 装配层 {@code @Bean(initMethod = "start")} 调用：挂载已存在的每个目录，然后在守护线程上跑监听循环。 */
  public void start() {
    WatchService service;
    try {
      Files.createDirectories(agentsDir);
      service = agentsDir.getFileSystem().newWatchService();
      registerDir(service, agentsDir);
      try (DirectoryStream<Path> children =
          Files.newDirectoryStream(agentsDir, Files::isDirectory)) {
        for (Path child : children) {
          registerDir(service, child);
        }
      }
    } catch (IOException e) {
      LOG.warn("WorkspaceWatcher 启动失败，实时监听不可用：{}", safe(e.getMessage()));
      return;
    }
    this.watchService = service;
    watcherExecutor.execute(() -> loop(service));
  }

  /** 关闭 {@link WatchService} 令监听循环退出；重复调用安全。 */
  public void stop() {
    WatchService service = watchService;
    if (service == null) {
      return;
    }
    try {
      service.close();
    } catch (IOException e) {
      LOG.warn("关闭 WorkspaceWatcher 失败：{}", safe(e.getMessage()));
    }
  }

  /** 把目录注册进 {@link WatchService} 并记入映射；同一目录重复注册返回同一 {@link WatchKey}（幂等）。 */
  private void registerDir(WatchService service, Path dir) throws IOException {
    WatchKey key = dir.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
    watchedDirs.put(key, dir);
  }

  private void loop(WatchService service) {
    while (true) {
      WatchKey key;
      try {
        key = service.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (ClosedWatchServiceException e) {
        return; // stop() 关闭了服务：正常退出路径
      }
      Path dir = watchedDirs.get(key);
      if (dir != null) {
        for (WatchEvent<?> event : key.pollEvents()) {
          if (event.context() instanceof Path relative) {
            dispatch(service, dir, dir.resolve(relative), event.kind());
          }
        }
      }
      if (!key.reset()) {
        watchedDirs.remove(key);
        if (agentsDir.equals(dir)) {
          return; // 根目录不可用：整体退出，避免空转
        }
        if (dir != null) {
          handleChange(dir, ENTRY_DELETE); // 子目录本身消失：注销该 Agent
        }
      }
    }
  }

  /**
   * 把一个目录事件翻成对某个 Agent 的登记 / 刷新 / 注销。包级可见，供单测不依赖真实事件时序直接调用。
   *
   * <ul>
   *   <li>根目录事件：只认直接子目录的新增（补挂监听 + 试登记）与删除（注销）；
   *   <li>子 Agent 目录事件：只认 {@code AGENT.md} 的增 / 改（登记或刷新）与删（注销）。
   * </ul>
   */
  void dispatch(WatchService service, Path dir, Path changed, WatchEvent.Kind<?> kind) {
    if (agentsDir.equals(dir)) {
      if (kind == ENTRY_CREATE && Files.isDirectory(changed)) {
        watchDirQuietly(service, changed);
        registerNewDirectory(changed);
      } else if (kind == ENTRY_DELETE) {
        handleChange(changed, ENTRY_DELETE);
      }
      // 根目录层的 MODIFY 不处理：AGENT.md 变更由子目录自己的 watch 捕获
      return;
    }
    // 大小写不敏感：macOS / Windows 上编辑器与 WatchService 都可能上报 agent.md
    if (!isAgentFile(changed)) {
      return; // 子目录里只有 AGENT.md 的变更牵动注册
    }
    if (kind == ENTRY_DELETE) {
      handleChange(dir, ENTRY_DELETE);
    } else {
      handleChange(dir, ENTRY_MODIFY);
    }
  }

  /**
   * 该路径的文件名是否就是 {@code AGENT.md}（大小写不敏感）。
   *
   * <p>用 {@link Locale#ROOT} 显式小写比较而非 {@code equalsIgnoreCase}：后者是 findsecbugs {@code
   * IMPROPER_UNICODE} 的固定报点，而这里的语义恰恰是「大小写不敏感文件系统上的同一文件」，属该规则的误报；改成全小写比较既保留语义又不引入 locale 相关的隐式转换。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "AGENT.md 是 ASCII 保留文件名；此处的产物就是「按大小写不敏感文件系统语义比较同一文件名」，"
              + "不存在 Unicode 混淆风险。撤销条件：把该比较用于用户可控的自由文本。")
  private static boolean isAgentFile(Path changed) {
    Path fileName = changed.getFileName();
    if (fileName == null) {
      return false;
    }
    return AGENT_FILE.equals(fileName.toString().toLowerCase(java.util.Locale.ROOT));
  }

  /** 补挂对新 Agent 目录的监听；挂不上只记 WARN、不拖垮监听线程。 */
  private void watchDirQuietly(WatchService service, Path dir) {
    try {
      registerDir(service, dir);
    } catch (IOException e) {
      LOG.warn(
          "监听 Agent 目录失败：dir={} reason={}",
          safe(String.valueOf(dir.getFileName())),
          safe(e.getMessage()));
    }
  }

  /**
   * 新目录登记：{@code mkdir} 与写 {@code AGENT.md} 是两个系统调用，根目录的 CREATE 事件常在文件落盘**之前**到达 （实测：Windows
   * 上尤其明显），此时派生必然报「缺少 AGENT.md」；而子目录监听一旦挂得晚，那次 CREATE 事件也已错过。
   *
   * <p>因此在监听线程上做有限重试（5 × 100ms）：覆盖「目录先建、文件后到」的常见时序。只在新建目录时发生，最坏阻塞监听线程 500ms； 仍失败则记一条 WARN 交人工判断。
   */
  void registerNewDirectory(Path agentDir) {
    for (int attempt = 1; attempt <= NEW_DIR_ATTEMPTS; attempt++) {
      if (Files.isRegularFile(agentDir.resolve(AGENT_FILE))) {
        handleChange(agentDir, ENTRY_CREATE);
        return;
      }
      if (attempt == NEW_DIR_ATTEMPTS) {
        LOG.warn(
            "新 Agent 目录在 {} 次重试内仍未出现 {}，已跳过：dir={}",
            NEW_DIR_ATTEMPTS,
            AGENT_FILE,
            safe(String.valueOf(agentDir.getFileName())));
        return;
      }
      try {
        Thread.sleep(NEW_DIR_RETRY_DELAY_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** 单个 Agent 目录变更 → 同一段登记 / 刷新 / 注销；坏目录记 WARN 跳过，不拖垮监听。包级可见供单测直接调。 */
  void handleChange(Path agentDir, WatchEvent.Kind<?> kind) {
    try {
      if (kind == ENTRY_DELETE) {
        lifecycle.unregisterByDir(agentDir);
      } else if (Files.isDirectory(agentDir)) {
        // CREATE / MODIFY 同一段：refresh 会先注销旧定时再重注册，重复编辑不残留旧 cron
        lifecycle.refresh(agentDir);
      }
    } catch (RuntimeException e) {
      LOG.warn(
          "Agent 目录变更处理失败，已跳过：dir={} reason={}",
          safe(String.valueOf(agentDir.getFileName())),
          safe(e.getMessage()));
    }
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法）。 */
  private static String safe(String value) {
    return (value == null) ? null : value.replaceAll("[\r\n]", "_");
  }
}
