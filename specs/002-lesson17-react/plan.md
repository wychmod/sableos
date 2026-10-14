# Implementation Plan: ReAct 循环——Agent 的核心循环引擎

**Branch**: `002-lesson17-react` | **Date**: 2026-10-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-lesson17-react/spec.md`

## Summary

自实现 ReAct 循环（宪法原则 II）：给定会话与用户消息，反复执行「组装本轮上下文 → 调第 16 节 Provider → 判断有无工具调用 → 有则交 `ToolExecutor` 逐个执行并回填历史」，直到模型不再要求调用工具（以该轮输出收尾）或转满最大轮数（返回含「达到最大轮数」字样的固定文案）。迭代上限默认 10、历史截断默认 20 条消息，均可由 Profile 覆盖。

分层落位（依赖倒置，理由见 research D-01）：

- **sableos-core**（全部运行时内核，框架无关——不依赖 Spring / Spring AI 类型）：
  - `com.sableos.core.react`：`ReActLoop`（循环引擎）
  - `com.sableos.core.prompt`：`PromptBuilder`（上下文拍平组装）
  - `com.sableos.core.tool`：`ToolExecutor` + 前向契约 `ToolCatalog` / `ToolInvocationAuditor`
  - `com.sableos.core.context`：`ContextLoader`（Bootstrap + AGENT.md 正文 + skills 软连接扫描）
  - `com.sableos.core.agent`：`AgentService`（统一编排入口）
  - `com.sableos.core.session`：`Session` / `SessionMessage` / `MessageRole` / `SessionStore`（前向契约 + 本节内存实现）
  - `com.sableos.core.profile`：`ProfileContext`（线程级当前 Agent 上下文）
  - `com.sableos.core.memory`：`MemoryProvider`（前向契约，可选注入，22 节落地）
- **sableos-storage**：`ToolInvocation` 实体 + `ToolInvocationRepository` + `JpaToolInvocationAuditor` + `InMemorySessionStore`（本节会话保存的进程内实现）+ Flyway 双轨 `V2__tool_invocations.sql`。
- **sableos-boot**：新增 `react/ReactConfiguration.java` 装配（`PromptBuilder` / `ReActLoop` / `ToolExecutor` / `AgentService` / `ContextLoader` / `SessionStore` / `ToolInvocationAuditor` / 本节空 `ToolCatalog`）。

## Technical Context

**Language/Version**: Java 21（enforcer 钉死 `[21,)`；P3C/PMD 以 17 目标语法解析，避开 Java 18+ 语法形态，禁用增强 switch 的 `default ->` 形态）

**Primary Dependencies**:

- 复用第 16 节已锁定 BOM，**本节不新增任何第三方依赖**：Spring Boot 3.5.16 全量 BOM；core 侧 `com.fasterxml.jackson.core:jackson-databind`（Jackson 2，`ObjectMapper`/`JsonNode` 已在 core 依赖内，白名单实测见 research 附录 A）、`org.slf4j:slf4j-api`、`org.yaml:snakeyaml`。
- 消费第 16 节交付的 `ProviderService` / `ProviderRequest` / `ProviderResponse` / `ToolCallRequest` / `Usage` / `LlmCallAuditor`、`Profile` / `ProfileRegistry` / `AgentLoader`、`SableTool` / `ToolResult`。

**Storage**: SQLite（sqlite-jdbc 3.53.4.0）+ Spring Data JPA + Flyway 双轨 `classpath:db/migration/{vendor}`；**手写** `V2__tool_invocations.sql`（sqlite/postgresql 1:1），禁 `ddl-auto` 改结构。会话本节为进程内实现（跨重启持久化归 18 节）。

**Testing**: JUnit 5 + Mockito + AssertJ（父 POM 统一注入 `spring-boot-starter-test`）；五类核心单测零网络（mock `ProviderService` / `ToolCatalog` / `ToolInvocationAuditor` / `SessionStore`）；`ToolInvocationRepositoryTest`（storage，真 Flyway + 临时 SQLite）与 `ReactAssemblyTest`（boot，零 key 装配）走 `@SpringBootTest`；集成冒烟打 `@Tag("integration")` CI 默认跳过。

**Target Platform**: JVM 服务端单体（fat JAR）；虚拟线程已启用（循环内阻塞调用运行在虚拟线程上）

**Project Type**: Maven 多模块库（本节落在 core / storage / boot 三个既有模块，provider 保持不变）

**Performance Goals**: 本节无量化指标（同步阻塞循环；每轮一次模型调用 + 顺序工具执行）

**Constraints**:
- core 保持框架无关（不引 Spring / Spring AI 类型）；
- `ObjectMapper` 解析工具入参须显式处理解析失败（转可重试失败结果，不上抛）；
- 线程级上下文用 `ThreadLocal`（ScopedValue 在 JDK 21 仍是 preview，开 `--enable-preview` 会挂构建，见 research D-06）；
- traceId 一律 `TraceContext.traceId()` / `TraceIds`，禁 `UUID.randomUUID()`；跨线程用 `TraceContext.snapshot()`；
- `synchronized` 内不得做阻塞调用（JDK 21 钉住虚拟线程），如需锁用 `ReentrantLock`；
- 全容器只允许 `ObservabilityConfig` 那一个 `TaskDecorator`（本节不新增）；
- 用户可控字符串入日志前 CRLF 净化，保持 `replaceAll("[\\r\\n]", "_")` 字面形态；
- 测试方法名英文 + `@DisplayName` 保留课件中文原文。

**Scale/Scope**: 每实例多 Agent 并存；每请求独占一虚拟线程（`ProfileContext` 天然不串台）；首个消费方为 18 节 CLI 入口。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据/说明 |
| --- | --- | --- |
| I. JDK 21 + Spring Boot 3.x 单体 | ✅ | 目标字节码 21；不引响应式；Maven 多模块不变 |
| II. 自实现 ReAct 循环 | ✅ | `ReActLoop` 由 core 从零实现（迭代计数、消息累积、错误处理全自持）；不依赖 Spring AI Agent 抽象（core 根本不含 Spring AI 类型） |
| III. Spring AI 只用一半 | ✅ | 本节只**消费**第 16 节 `ProviderService`（模型调用）与 `SableTool` schema；工具调度唯一由 `ReActLoop` + `ToolExecutor` 控制，Provider 层不执行工具（`ToolCallRequest` 原样交回，第 16 节已钉死 `internalToolExecutionEnabled(false)` 且有回归测试） |
| IV. Provider 显式映射 | ✅ | 本节不触碰 Provider 路由；`ProfileService` 调用不变 |
| V. Tool 三档且单模块；Agent 目录不当 Tool | ✅ | 工具注册表（`ToolCatalog`）本节只落**前向契约**，实现（`ToolRegistry`）归 20 节 `sableos-tool`；`ContextLoader` 扫 `AGENT.md`/`skills/` 时**只读上下文**（不把 Agent 目录当 Tool），且不新增工具模块 |
| VI. 审计 day one 落库 | ✅ | `tool_invocations` 手写双轨迁移建表 + `JpaToolInvocationAuditor` 成败两态写入 + `trace_id` 列（`TraceContext.traceId()` 写入，禁 `UUID.randomUUID()`） |
| VII. Sandbox 接口先行 | ✅(调用位) | 本节只在 `ToolExecutor` 留**一道校验调用位**并 `// TODO(24 节)` 标注（`Sandbox` 接口五件归 20 节课件、`WhitelistSandbox` 实现归 24 节），本节不新建 Sandbox 类型（软门禁：与 skill 落位表一致） |
| VIII. 范围收敛 | ✅ | 无并行工具、无 Agent 互托、无流式、无上下文压缩（仅「只留最近 N 条」）、无工具 Policy；会话持久化跨重启归 18 节，本节仅进程内 |
| IX. 每个 US 可演示 Demo | ✅ | 本节无独立入口；验收=单测全绿 + 装配可启动，撑住「五个测试类」；对话 Demo 由 18 节 CLI 接入后成型 |

**Post-Phase 1 re-check**: 设计未引入新违规。三处设计取舍（4 个前向契约放 core、会话内存实现、`ContextLoader` 自持 frontmatter 剥离）记入 Complexity Tracking，均为依赖倒置 / skill 落位表支撑，非违规。

## Project Structure

### Documentation (this feature)

```text
specs/002-lesson17-react/
├── plan.md              # 本文件
├── spec.md              # /speckit-specify 产出
├── research.md          # Phase 0：D-01~D-14 决策与核实证据
├── data-model.md        # Phase 1：Session / SessionMessage / tool_invocations
├── quickstart.md        # Phase 1：可执行的验收向导
├── contracts/           # Phase 1：runtime-contracts / react-loop / context-assembly
│                        #           / tool-executor / agent-service / tool-invocations-schema
└── tasks.md             # /speckit-tasks 产出
```

### Source Code (repository root)

```text
sableos-core/src/main/java/com/sableos/core/
├── agent/                # AgentLoader（既有）+ AgentService（本节新增：统一编排入口）
├── profile/              # Profile / ProfileRegistry（既有）+ ProfileContext（本节新增：ThreadLocal）
├── provider/             # 契约与中立 DTO（既有，本节零改动）
├── tool/                 # SableTool / ToolResult（既有）
│                         #   + ToolExecutor（本节新增：解析→校验位→执行→审计）
│                         #   + ToolCatalog / ToolInvocationAuditor（本节新增：前向契约）
├── session/              # 本节新增：Session / SessionMessage / MessageRole / SessionStore
├── prompt/               # 本节新增：PromptBuilder
├── context/              # 本节新增：ContextLoader
├── memory/               # 本节新增：MemoryProvider（前向契约）
└── react/                # 本节新增：ReActLoop

sableos-storage/src/main/java/com/sableos/storage/
├── ToolInvocation.java              # 本节新增：tool_invocations 的 JPA 映射
├── ToolInvocationRepository.java    # 本节新增
├── JpaToolInvocationAuditor.java    # 本节新增：fail-closed 审计实现
└── InMemorySessionStore.java        # 本节新增：SessionStore 的进程内实现
sableos-storage/src/main/resources/db/migration/
├── sqlite/V2__tool_invocations.sql       # 本节新增
└── postgresql/V2__tool_invocations.sql   # 本节新增（双轨 1:1）

sableos-boot/src/main/java/com/sableos/boot/
├── agent/AgentConfiguration.java    # 既有（零改动）
└── react/ReactConfiguration.java     # 本节新增：ReAct/Tool/Prompt/Context/AgentService/审计 装配
                                    #   + 本节空 ToolCatalog 实现（包内私有类）

tests（与实现同行）:
sableos-core/src/test/java/com/sableos/core/react/ReActLoopTest.java
sableos-core/src/test/java/com/sableos/core/prompt/PromptBuilderTest.java
sableos-core/src/test/java/com/sableos/core/tool/ToolExecutorTest.java
sableos-core/src/test/java/com/sableos/core/agent/AgentServiceTest.java
sableos-core/src/test/java/com/sableos/core/context/ContextLoaderTest.java
sableos-storage/src/test/java/com/sableos/storage/ToolInvocationRepositoryTest.java
sableos-boot/src/test/java/com/sableos/boot/ReactAssemblyTest.java
```

**Structure Decision**: 沿用既有 9 模块骨架，不新增模块；本节改动集中在 core / storage / boot 三模块，provider 保持零改动（只被消费）。运行时内核（循环、组装、执行、编排、上下文）全部落 core 且框架无关；持久化与审计实现落 storage；装配落 boot。依赖方向 core ← storage ← boot，绝不反向（research D-01）。

## Complexity Tracking

| 取舍 | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| 4 个前向契约（`Session`/`SessionStore`、`ToolCatalog`、`ToolInvocationAuditor`、`MemoryProvider`）提前落 core，真实现归后节 | 第 17 节编排需要「会话累积」「按名解析工具」「成败审计」「可选记忆注入」四个能力，其正式归属分别是 18/20/20+storage/22 节；core 内消费若不先定契约即成环 | 等后节再定 → 17 节循环无法落地；直接在 core 内自造一套完成后节再改 → 回改前序节公共接口（软门禁 #4） |
| 本节会话保存用 storage 的 `InMemorySessionStore`（非落库） | 会话表与跨重启恢复归 18 节（FR-015 / spec Assumptions）；本节只要求「处理完成后会话被保存」这条契约成立 | 本节自建 `sessions` 表 → 越 18 节范围，且与其 data-model 口径可能冲突（软门禁 #1） |
| `ContextLoader` 自持 frontmatter 剥离逻辑，不调用 `AgentLoader` 私有方法 | `AgentLoader` 的 `parseFrontmatter` 是私有实现，且职责是「派生 Profile」；`ContextLoader` 只要 AGENT.md 正文 | 把 `AgentLoader` 私有方法提为 public → 改动前序节公共接口（软门禁 #4）；复制整段 frontmatter 解析 → 重复造轮子且易漂移（本节只需剥离围栏，不需 YAML 解析） |
