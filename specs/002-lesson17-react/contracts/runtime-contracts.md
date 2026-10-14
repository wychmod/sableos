# Contract: 运行时前向契约（core）

**Feature**: 002-lesson17-react | 契约类型：进程内 Java 接口 / 类型（`sableos-core`，框架无关）

本文件定义第 17 节在 core 内落地的 4 个**前向契约**与 1 个线程级上下文工具——它们的真实现分别归 18/20/22 节（依赖倒置，见 research D-01）。上游需求见 [spec.md](../spec.md) FR-011~FR-015，结构见 [data-model.md](../data-model.md)。

## 1. Session 家族（`com.sableos.core.session`）

18 节 `SessionManager` + `sessions` 表落地前的内存前身；字段口径与 18 节 data-model 一致。

```java
public final class Session {
  public Session(String id, String profileName, String channel, String userId);  // status=active, createdAt=now
  public String id();
  public String profileName();
  public String channel();
  public String userId();
  public String status();
  public Instant createdAt();
  public Instant lastActiveAt();
  public List<SessionMessage> messages();          // 不可变视图
  public void appendUserMessage(String content);
  public void appendAssistantMessage(ProviderResponse response);
  public void appendToolResult(String toolName, ToolResult result);
}

public record SessionMessage(
    MessageRole role, String content, List<ToolCallRequest> toolCalls,
    String toolName, Boolean success) {
  public static SessionMessage user(String content);
  public static SessionMessage assistant(ProviderResponse response);
  public static SessionMessage tool(String toolName, ToolResult result);
}

public enum MessageRole { USER, ASSISTANT, TOOL }

public interface SessionStore {
  void save(Session session);      // 处理完成后保存（FR-011/FR-015）；本节 InMemorySessionStore 实现
}
```

**行为规约**：

1. `appendAssistantMessage`：`content` = `response.content()`、`toolCalls` = `response.toolCalls()`；追加时刷新 `lastActiveAt`。
2. `appendToolResult`：`TOOL` 条目 `content` = 成功时 `result.content()`、失败时 `result.errorMessage()`；`success` = `result.success()`；刷新 `lastActiveAt`。
3. 三个 append 均为「就地修改 + 返回 void」；调用方（`ReActLoop`）决定调用时机。
4. `messages()` 返回不可变视图（外部不可改内部列表）。

## 2. ToolCatalog（`com.sableos.core.tool`）

工具集合的前向契约；20 节 `ToolRegistry` 为其真实现（名字互不占位，见 research D-04）。

```java
public interface ToolCatalog {
  List<SableTool> toolsFor(Profile profile);   // 按 Profile 过滤（供 PromptBuilder 组装 schema）
  SableTool find(String name);                 // 按名解析（供 ToolExecutor）；未解析到返回 null
}
```

**行为规约**：

1. `toolsFor` 返回本次 Profile 可用的工具（按 `profile.tools()` 过滤）；空列表合法（无工具）。
2. `find` 按工具名查找；**未找到返回 null**（不抛异常）——由 `ToolExecutor` 转「未注册」失败结果（FR-008）。
3. 本节 boot 装配空实现：`toolsFor` 返回空表、`find` 返回 null（research D-10）。

## 3. ToolInvocationAuditor（`com.sableos.core.tool`）

工具调用审计写入契约；逐参对齐 16 节 `LlmCallAuditor` 风格（research D-09）。

```java
public interface ToolInvocationAuditor {
  void record(String sessionId, String toolName, String inputJson, String resultJson,
              boolean success, String errorMessage, long durationMs);
}
```

**行为规约**：

1. 每条记录对应一次 `ToolExecutor.execute`；成功 `success=true`（`resultJson` 非空）、失败 `success=false`（`errorMessage` 非空）——成败两态（FR-010）。
2. 实现（`JpaToolInvocationAuditor`，storage）**fail-closed**：写库失败 → LOG.error（CRLF 净化）→ 抛 `IllegalStateException`。
3. `trace_id` 由实现内部取 `TraceContext.traceId()` 写入，不经参数传递（接口零改动）。

## 4. MemoryProvider（`com.sableos.core.memory`）

长期记忆注入的前向契约；22 节落地真实现（research D-05）。

```java
public interface MemoryProvider {
  String memorySection(Profile profile);   // 记忆段文本；无记忆返回 null（PromptBuilder 跳过该段）
}
```

**行为规约**：返回 null 或空白 → `PromptBuilder` 整段省略（FR-006「无则跳过」）；本节 boot 装配返回 null 的 lambda。

## 5. ProfileContext（`com.sableos.core.profile`）

线程级「当前 Agent 运行配置」上下文（FR-012）。静态工具类，底层 `ThreadLocal`（research D-06）。

```java
public final class ProfileContext {
  public static void set(Profile profile);
  public static Profile current();    // 无上下文返回 null
  public static void clear();
  private ProfileContext() {}
}
```

**行为规约**：

1. `AgentService` 入口 `set`、`finally` `clear`（异常路径也清，FR-011）。
2. 有效期为一次处理；每请求独占一虚拟线程，天然不串台（FR-012）。
3. **不继承**（非 `InheritableThreadLocal`，AGENTS.md §5.2 禁）；跨线程需显式传递。

## 6. 异常契约

| 异常 | 触发 | 说明 |
| --- | --- | --- |
| `IllegalArgumentException` | `AgentService.process` 时 `ProfileRegistry.get(profileName)` 未命中（点名言 profile 名） | 不新增异常类型（沿用第 16 节 core 风格） |
| `IllegalStateException` | 审计写库失败（`JpaToolInvocationAuditor` fail-closed） | 由编排决定兜底 |
