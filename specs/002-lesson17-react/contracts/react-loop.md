# Contract: ReActLoop——自实现的循环引擎

**Feature**: 002-lesson17-react | 契约类型：进程内 Java 类（`com.sableos.core.react`，核心能力二，宪法原则 II）

## 1. 类结构与入口

```java
public final class ReActLoop {
  public ReActLoop(ProviderService providerService, PromptBuilder promptBuilder, ToolExecutor toolExecutor);
  public String run(Session session, String userMessage, Profile profile);
}
```

```java
public interface ProviderService {   // 第 16 节既有，零改动
  ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request);
}
```

| 参数 | 语义 |
| --- | --- |
| session | 本次会话（循环把每轮响应与工具结果累积进它，FR-005） |
| userMessage | 本条用户消息（循环开始前先 `session.appendUserMessage(userMessage)`） |
| profile | 本次处理的 Agent 运行配置（取 `settings().maxIterations()`） |

**返回值**：最终答复文本（模型不再要求调用工具时的输出）或转满上限的固定终止文案。

## 2. 行为规约（逐条对应课件骨架 + FR）

```text
run(session, userMessage, profile):
    session.appendUserMessage(userMessage)
    maxIterations = profile.settings().maxIterations()
    for (int i = 0; i < maxIterations; i++):
        request = promptBuilder.build(session, profile)          # 组装本轮上下文（FR-006）
        response = providerService.chat(session.id(), profile, request)   # 调第 16 节 Provider（FR-001）
        session.appendAssistantMessage(response)                 # 累积模型输出（FR-005）
        if response.toolCalls().isEmpty():                       # 无工具调用 → 收尾
            return response.content() == null ? "" : response.content()
        for (ToolCallRequest call : response.toolCalls()):        # 逐个（不并行，FR-003）
            result = toolExecutor.execute(session.id(), call)     # 执行权唯一在此（FR-002）
            session.appendToolResult(call.name(), result)         # 回填历史（FR-005）
    return "达到最大轮数，已停止"                                  # 转满强制收尾（FR-004）
```

| 规约 | 对应需求 |
| --- | --- |
| 进入循环前先 `appendUserMessage` | 当前用户消息成为历史一部分（PromptBuilder 渲染它） |
| 每轮先 `build` 再 `chat` | FR-001 固定顺序 |
| 每轮 `appendAssistantMessage` | FR-005 累积 |
| `toolCalls()` 恒非空（第 16 节保证）；空即收尾 | US1-AS1 |
| 一轮多工具 → `for` 顺序执行、每个都回填 | FR-003 / US1-AS3 |
| 恰好迭代 `maxIterations` 次 | FR-004 / SC-001（一轮不多不少） |
| 转满返回固定文案 `"达到最大轮数，已停止"` | FR-004；**不含**最后一条模型输出（research D-14） |
| `response.content()` 为 null 时返回空串 | 防御：避免调用方拿到 null |

## 3. 边界与约定

| 项 | 规约 |
| --- | --- |
| 迭代上限 | `profile.settings().maxIterations()`；Profile 未覆盖时其 `Settings` 构造已默认 10（第 16 节保证非 null） |
| 工具失败不断循环 | `ToolExecutor.execute` 返回失败 `ToolResult`（不抛），循环继续、由轮数上限兜底（FR-008/FR-009） |
| 循环内不做上下文压缩 | 仅靠 PromptBuilder 的「最近 N 条」截断（FR-007，边界） |
| 不做并行工具调用 | 一轮多工具顺序执行（FR-003，边界） |
| 不做流式输出 / Agent 互托 | 明确不做（spec 边界） |
| traceId | 循环本身不开作用域；由 `AgentService`（`TraceContext.openIfAbsent()`）或更外层入口统一开（research D-06 / AGENTS.md §5.2） |
| 阻塞调用与锁 | `run` 全程无 `synchronized`（虚拟线程会钉住，AGENTS.md §5.2） |

## 4. 关键回归测试（课件 harness 原样落地）

| 测试（英文方法名） | 守点 | 课件原文（@DisplayName） |
| --- | --- | --- |
| `run_modelAlwaysRequestsTools_stopsExactlyAtMaxIterations` | 每轮都要求调工具时恰好转满 `maxIterations` 次并返回终止结果（SC-001） | 模型每轮都要调工具时恰好转满最大轮数强制停并返回终止结果 |
| `run_modelStopsRequestingTools_returnsFinalText` | 无工具调用即返回该轮文本收尾（US1-AS1） | （harness 骨架） |
| `run_toolResultsAccumulate_intoSessionHistory` | 每轮响应与工具结果都累积进会话（SC-002） | 每轮响应与工具结果都累积进会话 |

断言逻辑逐条保真；mock `ProviderService` / `ToolExecutor` 零网络。
