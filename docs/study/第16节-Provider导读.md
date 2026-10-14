# 第 16 节 · Agent Provider 实现导读（提交前 review 版）

> 一句话：本节把「一次大模型调用」封装成一条**可路由、可审计、绝不自作主张**的薄通道。
> 代码总量 877 行（11 个主文件），但真正需要精读的只有 ~350 行，其余是 record 样板与逐字照合同的 SQL。
> 日期：2026-10-14 ｜ 验收证据：`mvn clean verify` 全绿（130 tests，四层门禁）

## 0. 模块与依赖方向（架构图）

```text
                        ┌──────────────────────────────────────────────┐
                        │              sableos-boot（装配层）            │
                        │  ProviderConfiguration  AgentConfiguration    │
                        │  （只管拼装：validate→build→new，不做业务）      │
                        └───────┬──────────────┬───────────────┬────────┘
                                │              │               │
        ┌───────────────────────┘              │               └────────────────────┐
        ▼                                      ▼                                    ▼
┌────────────────────────┐          ┌────────────────────────┐        ┌────────────────────────┐
│    sableos-provider    │          │       sableos-core      │        │    sableos-storage     │
│    （实现层）            │───依赖──▶│      （契约层）          │◀──依赖──│    （持久化层）          │
│                        │          │                        │        │                        │
│ SpringAiProviderService│          │ ProviderService 接口    │        │ LlmCall 实体            │
│   Impl（心脏：路由+审计）│          │ LlmCallAuditor 接口     │        │ LlmCallRepository      │
│ ProviderChatModelFactory│         │ ProviderRequest/Response│        │ JpaLlmCallAuditor      │
│ ToolSchemaAdapter      │          │ ToolCallRequest / Usage │        │ （trace_id 源头 core）   │
│ ProvidersProperties    │          │ Profile / ProfileRegistry│       │ db/migration/sqlite     │
│ ProviderNotFoundException│        │ AgentLoader            │        │   /V1__llm_calls.sql   │
│                        │          │ SableTool / ToolResult  │        │ db/migration/postgresql │
└────────────────────────┘          └────────────────────────┘        └────────────────────────┘

依赖方向（单向，无环）：provider → core ｜ storage → core ｜ boot → 全部
★ 倒置要点：接口与 DTO 在 core、实现放 provider —— 因为 17 节的 ReActLoop 住在 core 要调 ProviderService，
  接口若留在 provider 会形成 core→provider 的环（Maven 直接拒绝构建）。
```

## 1. 启动时发生了什么（顺序）

```text
Spring 启动
 ① ProvidersProperties 绑定 application.yaml 的 sableos.providers（空清单合法、零 key 可启动）
 ② ProviderConfiguration.providerService(...)：
      2a. properties.validate()            ← 违规即点名报错、启动失败（快失败）
      2b. ProviderChatModelFactory.build() ← 逐条显式构造 ChatModel；★不注册为 bean（宪法 IV 禁类型扫描）
      2c. new SpringAiProviderServiceImpl(路由表, properties, adapter, auditor)
 ③ AgentConfiguration：
      3a. agentLoader（扫 .sableos/agents/，knownProviders=清单名字集）
      3b. profileRegistry = loadAll()      ← 坏目录只记日志跳过，不阻断启动（SC-004）
 ④ storage 栈初始化：Flyway 跑 db/migration/sqlite/V1__llm_calls.sql → 建 llm_calls 表
 ⑤ JpaLlmCallAuditor(LlmCallRepository)    ← 审计落库通道就位
```

## 2. 运行时：一次 `chat()` 的完整链路（调用图）

```text
上层调用方（17 节 ReActLoop / 未来的 CLI、Web）
   │  chat(sessionId, profile, request)
   ▼
SpringAiProviderServiceImpl ······································· sableos-provider
   │ ① 路由：profile.provider().name() 查内部路由表
   │      查不到 → ProviderNotFoundException（消息点名）      ◀── 坑一：不降级不换家
   │ ② 翻译：ToolSchemaAdapter.toSpringAiTools(request.availableTools())
   │      SableTool → ToolCallback；产物 call() 恒抛          ◀── US3：只翻译不执行
   │ ③ 选型：buildOptions 按 provider type 造同型 options
   │      openai→OpenAiChatOptions.Builder / dashscope→DashScopeChatOptions.builder()
   │      内嵌 internalToolExecutionEnabled(false)           ◀── 坑二：显式关自动执行
   │ ④ 调用：model.call(new Prompt(List.of(new UserMessage(content)), options))
   │      ──────────▶ Spring AI (OpenAiChatModel/DashScopeChatModel) ──────────▶ 大模型 API
   │ ⑤ 映射：text / toolCalls(原样) / usage(null 安全)
   │
   ├── 失败路径：auditor.record(..., false, 异常消息, 耗时)   ← 先落账
   │            审计自身也失败？→ LOG.error + addSuppressed → 原异常照抛
   │
   └── 成功路径：auditor.record(..., true, usage, 耗时)
                审计失败？→ LOG.error，照常返回业务结果（fail-open）
   ▼
JpaLlmCallAuditor ················································· sableos-storage
   │ trace_id ← TraceContext.traceId()（无链路上下文 = null；禁 UUID.randomUUID）
   ▼
LlmCallRepository.save → SQLite llm_calls 表（成败各一条，day one 落库）

★ 审计语义一句话：审计写失败永远不改变业务结果；失败路径以原异常为准，成功路径以响应为准。
```

## 3. 三个「坑」的钉子（课件要求的三个关键回归测试）

| 回归测试（ProviderServiceTest） | 钉住的坑 | 断言要点 |
| --- | --- | --- |
| `routeByName_twoProvidersDoNotCross` | 显式映射、按名路由 | `verify(kimi, times(1))` + `verify(deepseek, never())` |
| `failedCall_auditRecordsFailureAndRethrows` | 失败必须留痕 | `assertThrows` + 七参 verify：`isNull(), eq(false), contains("timeout"), anyLong()` |
| `toolSchemaCall_disablesInternalToolExecution` | 禁止自动执行 | 捕获 Prompt → `getInternalToolExecutionEnabled()==FALSE` + toolCallbacks 非空 |

## 4. 必读 / 可略过（分桶）

**精读这 5 个（~350 行）**

| 文件 | 行数 | 看什么 |
| --- | --- | --- |
| `sableos-provider/.../SpringAiProviderServiceImpl.java` | 153 | `chat()` 的 try/catch 编排（失败先落账/成功 fail-open）；`buildOptions()` 两个 builder 分支 |
| `sableos-provider/src/test/.../ProviderServiceTest.java` | ~280 | 三个回归测试 + 5 条补强（未知 provider、usage 透传、fail-open、工具原样交回） |
| `sableos-provider/.../ProviderChatModelFactory.java` | 98 | 显式构造（零扫描）；HTTP 客户端/单次重试共性；base-url 归一 |
| `sableos-core/.../agent/AgentLoader.java` | 247 | 精读 `deriveProfile` 校验块 + `loadAll` 的 catch（坏目录跳过不阻断） |
| `sableos-boot/.../provider/ProviderConfiguration.java` | 46 | 装配顺序：validate → build → new；ChatModel 不进容器 |

**扫一眼签名即可（机械件）**：core 的 6 个 DTO/接口 record ｜ `Profile`/`ProfileRegistry` ｜ `LlmCall`（JPA 样板）｜ `LlmCallRepository` ｜ 两条 `V1__llm_calls.sql` ｜ `AgentConfiguration` ｜ 其余 6 个测试类 ｜ 三处配置改动（pom 依赖、yaml 两段）。

## 5. 提交前 re 清单（核这 5 条就够）

1. **依赖方向**：`grep -r "com.sableos.provider" sableos-core/src/main` → 应为空（契约倒置没被破坏）；
2. **三回归钉子**：打开 `ProviderServiceTest` 只看上表三行方法名与断言；
3. **审计 try 结构**：`SpringAiProviderServiceImpl.chat()` 里 catch 块只有一处 `throw callError;` 且 auditError 走 suppressed；
4. **唯一真正动了设计文档的点**：sqlite 轨 `created_at INTEGER` + `application.yaml` 的 `hibernate.type.preferred_instant_jdbc_type: TIMESTAMP`（原因在 yaml 注释：sqlite-jdbc 只认 INTEGER 毫秒、ISO 文本读回必挂；`llm-calls-schema.md`/`data-model.md` 已同步修订）；
5. **全量门禁重跑**：`mvn clean verify`（期望 BUILD SUCCESS、130 tests）。
