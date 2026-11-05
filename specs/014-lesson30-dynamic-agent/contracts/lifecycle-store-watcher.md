# 契约：AgentStore / AgentLifecycleService / WorkspaceWatcher / AgentScheduler

> core 内部契约（无 HTTP 语义）。所有异常语义由调用方决定：`AgentLifecycleService` 只抛 `IllegalArgumentException`（400）与既有 `RuntimeException`；404 由 web 层判定。

## 1. `AgentStore`

```java
public class AgentStore {
  public AgentStore(Path root);

  public Path root();
  public Path agentsDir();                     // <root>/agents
  public Path archiveDir();                    // <root>/archive

  public Path writeAll(String name, Map<String, String> files);
  public void delete(Path agentDir);
  public void archive(String name);
  public String read(String name);                          // AGENT.md
  public Map<String, String> readAll(String name);          // 目录内全部文本文件
  public Path resolveWithin(String relativePath);           // 边界校验后的绝对路径
  public boolean exists(String name);
  public static boolean isValidName(String name)            // 供 web 侧预校验复用
}
```

**不变量**
1. `writeAll` 的每个相对路径 `normalize()` 后必须 `startsWith(agentDir)`，否则 `IllegalArgumentException`。
2. `archive` 目标已存在时追加 `-<yyyyMMddHHmmss>` 后缀，**绝不覆盖**。
3. `delete` 只删传入目录；不存在时静默（回滚路径必须幂等）。
4. `resolveWithin` 只接受落在 `agents/` 或 `archive/` 下的路径；读前由调用方再解析一次真实路径。

## 2. `AgentLifecycleService`

```java
public class AgentLifecycleService {
  // 构造：AgentLoader, ProfileRegistry, AgentScheduler, AgentStore, ProviderService,
  //      String authorProvider, String authorModel, String defaultProvider

  public String generate(String name, String description);              // 合并文本（预览）
  public Map<String, String> generateFiles(String name, String description);
  public Profile create(String name, String description);
  public Profile register(Path agentDir);
  public Profile refresh(Path agentDir);
  public void unregisterByDir(Path agentDir);
  public Optional<Profile> get(String name);
  public List<Profile> list();
  public Profile update(String name, String agentMarkdown);
  public Profile saveFiles(String name, Map<String, String> files);
  public void delete(String name);
}
```

**行为契约**

| 方法 | 前置校验 | 副作用 | 异常 |
| --- | --- | --- | --- |
| `generate` / `generateFiles` | name 合法；description 非空；`authorModel` 非空 | 一次 `ProviderService.chat`（落 `llm_calls`）；不落盘不注册 | `IllegalArgumentException`（400）/ `ProviderUnavailableException`（503） |
| `create` | name 合法；`!registry.exists(name)` | `writeAll` → `register` | 同名 → `IllegalArgumentException`；注册失败 → 回滚后重抛 |
| `register` | 无（委托 `deriveProfile`） | `registry.register` + 有 schedules 则 `scheduler.registerProfile` | `deriveProfile` 的异常原样上抛 |
| `refresh` | 无 | 先 `unregisterProfile(旧 Profile ifPresent)`，再 `register` | 同上 |
| `unregisterByDir` | 无 | `unregisterProfile` + `registry.remove`（不归档） | 无 |
| `update` | `parse` 预校验 | `saveFiles(name, {"AGENT.md": md})` | 非法 → `IllegalArgumentException` |
| `saveFiles` | 含 `AGENT.md` 且可 `parse` | `writeAll` → `refresh` | 同上 |
| `delete` | 无（未注册时幂等返回） | `unregisterProfile` → `registry.remove` → `archive` | 归档失败 → `IllegalArgumentException` |

**顺序不变量（harness 用 `InOrder` 钉死）**
1. `delete`：`agentScheduler.unregisterProfile` → `profileRegistry.remove` → `agentStore.archive`
2. `create` 失败回滚：`agentStore.writeAll` → （异常）→ `agentStore.delete`；`agentScheduler.registerProfile` 从未被调用
3. `update`/`saveFiles` 定时变更：`agentScheduler.unregisterProfile(旧)` → `agentScheduler.registerProfile(新)`

## 3. `WorkspaceWatcher`

```java
public class WorkspaceWatcher {
  public WorkspaceWatcher(AgentLifecycleService lifecycle, Path root, Executor watcherExecutor);

  public void start();          // 装配层 @Bean(initMethod="start")
  public void stop();           // ContextClosedEvent 触发

  void dispatch(Path dir, Path changed, WatchEvent.Kind<?> kind);   // 包级，单测直接调
  void handleChange(Path agentDir, WatchEvent.Kind<?> kind);        // 包级
}
```

**事件映射**

| 监听目录 | 事件 | 条件 | 动作 |
| --- | --- | --- | --- |
| `agents/` | `ENTRY_CREATE` | `changed` 是目录 | 补挂监听 + `lifecycle.refresh(changed)` |
| `agents/` | `ENTRY_DELETE` | — | `lifecycle.unregisterByDir(changed)` |
| `agents/` | `ENTRY_MODIFY` | — | 忽略（交给子目录 watch） |
| `agents/<name>/` | `CREATE`/`MODIFY` | 文件名 `AGENT.md`（大小写不敏感） | `lifecycle.refresh(dir)` |
| `agents/<name>/` | `DELETE` | 文件名 `AGENT.md` | `lifecycle.unregisterByDir(dir)` |

**容错**：`handleChange` 整体 try/catch，异常只记 WARN（点名目录名与原因，CRLF 净化），监听循环继续。

## 4. `AgentScheduler.unregisterProfile`

```java
public void unregisterProfile(Profile profile);
```

**契约**
- 遍历 `registeredTasks`，筛 `task.profile().name().equals(profile.name())`：
  - `scheduledTasks.remove(scheduleId)` 并 `future.cancel(false)`（不中断正在执行的那次）；
  - `registeredTasks.remove(scheduleId)`；
  - `scheduledTaskStore.retire(scheduleId)`（失败只记 ERROR，继续处理下一条）。
- **不动** `taskLocks` / `sessionLocks`（重注册复用同一把锁）。
- 未命中任何句柄时静默返回（幂等）。

## 5. `ToolExecutionContext`

```java
public final class ToolExecutionContext {
  public static void setAgentName(String name);
  public static String agentName();     // 可能 null
  public static void clear();
}
```

**契约**
- `ThreadLocal`，**非** `InheritableThreadLocal`。
- `ToolExecutor.execute` 在 `tool.execute(input)` 前 `set(ProfileContext.current()?.name())`，`finally` 中 `clear()`。
- `MemoryServiceImpl.buildContext/memorySection` 在委托 store 前后 `set` 并**复原原值**（用 try/finally）。

## 6. `MarkdownMemoryStore` 路径解析

```java
private Path memoryFile(String profileName) {
  String agent = ToolExecutionContext.agentName();
  if (agent != null && SAFE_AGENT.matcher(agent).matches()) {
    return workspaceRoot.resolve("agents").resolve(agent).resolve("MEMORY.md");
  }
  return workspaceRoot.resolve("memory").resolve(profileName).resolve("MEMORY.md");  // 既有行为
}
```

**契约**：`LongTermMemoryStore` 接口签名不变；三档后端契约测试零改动。
