# Contract: ProviderService——统一调用入口

**Feature**: 001-lesson16-provider | 契约类型：进程内 Java 接口（core）+ Spring AI 实现（provider）

## 1. 接口（`com.sableos.core.provider.ProviderService`）

```java
ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request);
```

| 参数 | 语义 |
| --- | --- |
| sessionId | 对话会话标识（审计落 `llm_calls.session_id`；来源与格式随各入口，18 节前由调用方保证非空） |
| profile | 本次调用的 Agent 运行配置；系统取 `profile.provider().name()/model()/temperature()` 与 `profile.tools()` |
| request | 用户输入内容 + 本次可用工具说明（`ProviderRequest`，见 §3） |

**返回值**：`ProviderResponse`——模型文本（`content`，可为 null/空）与/或工具调用请求列表（`toolCalls`，恒非空；可能为空表）。

### 1.1 行为规约

1. **路由**：以 `profile.provider().name()` 在显式映射表中取 `ChatModel`；不在表中 → 抛 `ProviderNotFoundException(providerName)`，**不降级、不换家、不留空**（FR-002/FR-003、US1-AS2）。
2. **翻译**：`profile.tools()` 经 `ToolSchemaAdapter` 转 `ToolCallback` 列表（只翻译不执行，见 §4）。
3. **调用**：构造 per-call 选项（`model`、`temperature` 有值才设、`internalToolExecutionEnabled=false`、工具非空才挂载），执行一次 `ChatModel.call`（FR-004）。
4. **成功路径**：审计写 `success=true` + usage + 耗时（`durationMs` 从调用前起计）；审计写失败 fail-open——LOG.error 后照常返回业务结果。
5. **失败路径**：`RuntimeException` 先审计 `success=false` + `e.getMessage()` + usage=null，**原异常继续上抛**；若审计写自身也失败，LOG.error + `e.addSuppressed(auditError)`，原异常照抛（FR-005、US2-AS2）。
6. **响应**：模型返回的「想调工具」请求原样映射为 `ToolCallRequest` 列表交回上层；本层绝不执行工具、不解释工具名（FR-004、US3-AS2）。

### 1.2 异常契约

| 异常 | 触发 | 说明 |
| --- | --- | --- |
| `ProviderNotFoundException` | `profile.provider().name()` 不在映射表 | 消息含 provider 名；位于 `com.sableos.provider` |
| 供应商原异常（`RuntimeException` 子类） | 超时/限流/服务端错误 | 审计后**原样上抛**，不包装不吞 |

## 2. 审计接口（`com.sableos.core.provider.LlmCallAuditor`）

```java
void record(String sessionId, String provider, String model, Usage usage,
            boolean success, String errorMessage, long durationMs);
```

- 七参签名与课件 harness 测试 2 的 verify 逐参对齐（`eq("s-1"), eq("deepseek"), any(), isNull(), eq(false), contains("timeout"), anyLong()`）。
- 实现（`JpaLlmCallAuditor`，storage）**fail-closed**：写库异常 → LOG.error（CRLF 净化）→ 抛 `IllegalStateException`；调用方按 §1.1 的成功/失败路径决定兜底策略。
- `trace_id` 由实现内部取 `TraceContext.traceId()` 写入，**不经参数传递**（接口零改动；口径见 contracts/llm-calls-schema.md）。

## 3. 中立 DTO（`com.sableos.core.provider`）

| 类型 | 形状 | 说明 |
| --- | --- | --- |
| `ProviderRequest` | `record(String content, List<SableTool> availableTools)` | `availableTools` 为空列表 = 无可用工具（Edge Case）；恒非 null |
| `ProviderResponse` | `record(String content, List<ToolCallRequest> toolCalls, Usage usage)` | `toolCalls` 恒非空；`usage` 可为 null（供应商未返回/失败） |
| `ToolCallRequest` | `record(String id, String name, String argumentsJson)` | id 原样透传（上层回填用）；argumentsJson 为模型给出的原始 JSON 文本，不预解析 |
| `Usage` | `record(Integer promptTokens, Integer completionTokens, Integer totalTokens)` | 各字段可为 null |

映射关系（实现侧）：Spring AI `ChatResponse` → `getResult().getOutput().getText()` 取 content；`getToolCalls()` → `ToolCallRequest`（record 组件 id/type/name/arguments 中取 id/name/arguments）；`getMetadata().getUsage()` → `Usage`（null 安全）。

## 4. 工具翻译契约（`com.sableos.provider.ToolSchemaAdapter`）

```java
List<ToolCallback> toSpringAiTools(List<SableTool> tools);
```

- `SableTool` 三要素（name/description/inputSchema 文本）**逐字**填入 Spring AI `ToolDefinition`（`ToolDefinition.builder().name().description().inputSchema(String).build()`）；
- 返回的每个 `ToolCallback` 为「只翻译不执行」载体：`getToolDefinition()` 正常返回定义，**`call(...)` 恒抛 `IllegalStateException`**（消息如「Provider 只翻译工具、不执行工具」）——即使本层防御失效被误执行，也会立刻显性失败而非静默跑工具；
- 空入参 → 空表；不做去重、排序、改名（保真）。

## 5. 模型工厂契约（`com.sableos.provider.ProviderChatModelFactory`）

```java
Map<String, ChatModel> build(ProvidersProperties properties);
```

- **显式映射**：遍历 `properties.providers()` 逐条构造，绝不做类路径/容器扫描（宪法原则 IV）；
- 按 `type` 分派协议栈：`openai` → `OpenAiApi` + `OpenAiChatModel`；`dashscope` → `DashScopeApi` + `DashScopeChatModel`；
- 构造共性：`defaultOptions` 内嵌 `internalToolExecutionEnabled(false)`；`retryTemplate` = 单次（`maxAttempts(1)`）；HTTP 客户端 JdkClientHttpRequestFactory（连接 10s / 读取 120s）、HTTP/1.1、不跟随重定向；base-url 归一（去尾 `/`、去尾 `/v1`）；
- 产物不注册为 Spring bean——只交给 `ProviderService` 实现的内部路由表。
