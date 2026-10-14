# Contract: ToolExecutor——工具执行与审计

**Feature**: 002-lesson17-react | 契约类型：进程内 Java 类（`com.sableos.core.tool`）

执行权唯一收敛处（FR-002）：Provider 层绝不执行工具，`ReActLoop` 只调用 `ToolExecutor`。

## 1. 类结构

```java
public final class ToolExecutor {
  public ToolExecutor(ToolCatalog toolCatalog, ToolInvocationAuditor auditor);
  public ToolResult execute(String sessionId, ToolCallRequest call);
}
```

| 参数 | 语义 |
| --- | --- |
| sessionId | 会话标识（审计落 `tool_invocations.session_id`） |
| call | 模型给出的工具调用请求（`record(String id, String name, String argumentsJson)`，第 16 节中立 DTO） |

**返回值**：`ToolResult`（第 16 节既有 record）——成成功/失败两态，失败带原因，**绝不抛异常**（见 §2）。

## 2. 行为规约（FR-006 步骤 + FR-008 / FR-009）

```text
execute(sessionId, call):
    start = now()
    tool = toolCatalog.find(call.name())              # 按名解析（FR-008）
    if tool == null:
        result = ToolResult.failure("未注册的工具：" + call.name(), false)
        audit(sessionId, call, result, null, start)   # 落 success=false + 原因
        return result

    // ---- 对外动作白名单校验位（FR-009；规则归 24 节）----
    // TODO(24 节): Sandbox.enforce(...) 接线处——本节只留调用位，不实现规则
    // （Sandbox 接口五件归 20 节课件、WhitelistSandbox 实现归 24 节；本节不新建 Sandbox 类型）

    input = parseJson(call.argumentsJson())           # Jackson ObjectMapper.readTree
    if parse 失败:
        result = ToolResult.failure("工具入参解析失败：" + msg, true)
        audit(sessionId, call, result, call.argumentsJson(), start)
        return result

    try:
        result = tool.execute(input)                  # 工具自身返回 ToolResult
        audit(sessionId, call, result, call.argumentsJson(), start)
        return result
    catch (RuntimeException e):
        result = ToolResult.failure("工具执行异常：" + e.getMessage(), true)   # 异常不吞（原因留痕）
        audit(sessionId, call, result, call.argumentsJson(), start)
        return result
```

| 规约 | 对应需求 |
| --- | --- |
| `find` 未命中 → 失败结果回填、不抛 | FR-008 |
| 执行前经一道白名单校验调用位 | FR-009 |
| `execute` 任何路径都返回 `ToolResult`，**不向上抛** | FR-009「异常不得被吞掉」（= 原因留痕，research D-07） |
| **成败两态都写审计**（含工具名/入参/结果/成败/原因/耗时/会话/链路标识） | FR-010 / SC-004 |
| 一轮多工具由 `ReActLoop` 顺序调用，本类无并行 | FR-003 |

## 3. 审计写入（`ToolInvocationAuditor`）

- 参数：`record(sessionId, toolName, inputJson, resultJson, success, errorMessage, durationMs)`。
- 成功：`resultJson` = `result.content()`、`errorMessage` = null；
- 失败：`resultJson` = null、`errorMessage` = `result.errorMessage()`；`inputJson` = 入参原文（解析失败/未注册分支按可用值给，可 null）。
- `trace_id` 由实现（`JpaToolInvocationAuditor`）内部取 `TraceContext.traceId()` 写入，不经本类传递（research D-09）。
- 实现 **fail-closed**：写库失败抛 `IllegalStateException`——本类不吞该异常（审计是审计，不能静默丢，原则 VI）。

## 4. JSON 解析

- 用 `com.fasterxml.jackson.databind.ObjectMapper.readTree(String)`（core 已在依赖内，Jackson 2.21.4，实测见 research 附录 A）。
- `argumentsJson` 为空/null → 视为 `NullNode`/空对象（工具自行处理空入参），不算解析失败。
- 解析抛 `JsonProcessingException`（checked）→ 转 `ToolResult.failure(..., true)`（可重试）。
- `ObjectMapper` 为线程安全只读实例，`ToolExecutor` 持有一个 final 字段；不用共享可变配置。

## 5. 边界

| 项 | 规约 |
| --- | --- |
| 不做并行工具调用 | 顺序执行（边界） |
| 不做工具 Policy（allow/deny） | 本次不做（边界） |
| 不实现 Sandbox 规则 | 只留调用位 + `// TODO(24 节)`（FR-009 / SKill 落位表） |
| 工具结果超长截断 | 本次不做（`sandbox.tool_result_max_chars` 键本节不消费，research 附录 B(L)） |
| Provider 自动执行 | 绝不触碰——`tool.execute` 的唯一调用者在 `ToolExecutor`（宪法 III） |

## 6. 关键回归测试（课件 harness）

| 测试（英文方法名） | 守点 | 课件原文 |
| --- | --- | --- |
| `execute_success_auditsSuccessTrue` | 工具成功写审计 `success=true`（SC-004） | 工具成功写审计 success=true |
| `execute_toolThrows_auditsFailureWithReason` | 工具失败写审计 `success=false` 带原因、异常不吞（SC-004） | 失败也写 success=false 带原因且异常不吞 |
| `execute_unknownToolName_failsWithoutThrowing` | 未注册工具名按失败处理、不抛（FR-008） | （FR-008） |
