# Implementation Plan: Agent Provider——对接大模型的统一入口

**Branch**: `001-lesson16-provider` | **Date**: 2026-10-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-lesson16-provider/spec.md`

## Summary

薄封装一次模型调用：上层传入会话标识、Profile、Prompt，系统按 Profile 的 provider 名经**显式映射表**选中 `ChatModel`、发起一次调用、把结果原样返回，并**不论成败**落 `llm_calls` 审计。

分层落位（依赖倒置，理由见 research D-01）：

- **sableos-core**：`Profile`/`ProfileRegistry`/`AgentLoader`（扫 `.sableos/agents/<name>/AGENT.md` 派生 Profile）+ Provider 契约（`ProviderService` 接口与中立 DTO：`ProviderRequest`/`ProviderResponse`/`ToolCallRequest`/`Usage`/`LlmCallAuditor`）+ `SableTool`/`ToolResult` 契约。core 保持框架无关（不依赖 Spring AI 类型）。
- **sableos-provider**：Spring AI 实现——`ProvidersProperties`（全局清单）、`ProviderChatModelFactory`（逐条显式构造 `ChatModel`，禁类型扫描，宪法 IV）、`ToolSchemaAdapter`（只翻译不执行）、`SpringAiProviderServiceImpl`（路由 + 建 Prompt + 审计编排）。
- **sableos-storage**：`LlmCall` 实体 + `LlmCallRepository` + `JpaLlmCallAuditor`（宪法 VI：day one 落库）+ Flyway 双轨迁移 `V1__llm_calls.sql`。
- **sableos-boot**：启动装配（AgentLoader 加载 + ProviderChatModelFactory 构造 + JPA 扫描）+ `application.yaml` 全局层配置；禁用 DashScope 自动装配（见 Technical Context）。

## Technical Context

**Language/Version**: Java 21（enforcer 钉死 `[21,)`；P3C/PMD 以 17 目标语法解析，避开 Java 18+ 语法形态）

**Primary Dependencies**:

- Spring Boot 3.5.16（`spring-boot-starter-parent` = 全量 BOM：SnakeYAML / Jackson 2 / JUnit5 / Mockito / SQLite dialect 均由 BOM 管理）
- Spring AI 1.1.8：**raw `org.springframework.ai:spring-ai-openai`**（已核 jar：无任何 autoconfiguration 类，OpenAI 兼容端点经 `OpenAiApi`+`OpenAiChatModel` 显式构造）
- Spring AI Alibaba 1.1.2.4-security-fix：`com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope`（父 POM 已锁版）。已逐类核实其 `spring-ai-alibaba-autoconfigure-dashscope` jar 的 **10 个 autoconfig 全部带 `@ConditionalOnDashScopeEnabled`**（= `@ConditionalOnProperty("spring.ai.dashscope.enabled", matchIfMissing=true)`），故 boot 以**单个属性 `spring.ai.dashscope.enabled: false`** 禁用（替代方案与理由见 research D-03）
- core 新增两个 BOM 管版本依赖：`org.yaml:snakeyaml`（AGENT.md frontmatter 解析）、`com.fasterxml.jackson.core:jackson-databind`（`SableTool.execute(JsonNode)` 入参）

**Storage**: SQLite（sqlite-jdbc 3.53.4.0）+ Spring Data JPA + Flyway 双轨 `classpath:db/migration/{vendor}`；**手写** `V1__llm_calls.sql`（sqlite/postgresql 1:1），禁 `ddl-auto` 改结构

**Testing**: JUnit 5 + Mockito + AssertJ（父 POM 统一注入 `spring-boot-starter-test`）；单测 mock `ChatModel` 零网络；`ProviderSmokeIT` 打 `@Tag("integration")` 本地手动跑

**Target Platform**: JVM 服务端单体（fat JAR）；虚拟线程已启用

**Project Type**: Maven 多模块库（本节落在 core / provider / storage / boot 四个既有模块）

**Performance Goals**: 本节无量化指标（同步阻塞一次调用；超时与单次重试配置见 D-09）

**Constraints**: core 不得依赖 Spring（框架无关保持）；凭证只走 `${ENV}` 占位；`internalToolExecutionEnabled=false` 必须显式关闭；测试方法名英文 + `@DisplayName` 保留课件中文

**Scale/Scope**: 每实例 N 个 provider 并存、N 个 Agent 并存（核心阶段多个 Agent 并存）；首个消费方为第 17 节 ReActLoop

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据/说明 |
| --- | --- | --- |
| I. JDK 21 + Spring Boot 3.x 单体 | ✅ | 目标字节码 21；不引响应式；Maven 多模块不变 |
| II. 自实现 ReAct 循环 | ✅ | 本节不触碰循环；Provider 只做「一次调用」薄封装，不使用 Spring AI Agent 抽象 |
| III. Spring AI 只用一半 | ✅ | 只消费 Provider 抽象/协议转换/工具 schema 承载（`ToolDefinition`）；**禁用自动 tool 执行**：options 显式 `internalToolExecutionEnabled(false)`，且有回归测试钉死（harness 测试 3） |
| IV. Provider 显式映射 | ✅ | `ProviderChatModelFactory` 依据声明清单逐条显式构造 name→`ChatModel`；零类型扫描 |
| V. Tool 三档且单模块；Agent 目录不当 Tool | ✅ | 本节只落 `SableTool`/`ToolResult` **契约**（技术方案 §6.1 + 课件 16 引用）；工具实现、`ToolRegistry`、三档接入归第 20 节 `sableos-tool`；`AgentLoader` 在 core（上下文层），不算 Tool |
| VI. 审计 day one 落库 | ✅ | `llm_calls` 手写迁移建表 + `JpaLlmCallAuditor` 成败两态写入 + `trace_id` 列（`TraceContext.traceId()` 显式赋入，禁用 `UUID.randomUUID()`） |
| VII. Sandbox 接口先行 | ✅(N/A) | 本节无工具执行、无用户可控涉外 IO；LLM 出站调用不属沙箱管辖（工具侧校验归 20/24 节）。H4-① 自查按「无调用位」记录 |
| VIII. 范围收敛 | ✅ | 无 fallback/hedge racing/熔断/成本看板/流式/多租户；不新建模块 |
| IX. 每个 US 可演示 Demo | ✅ | 16 节无独立入口，验收=单测全绿 + `ProviderSmokeIT` 真连通（撑住 Demo 一里那次调用）；对话版由 17 节接 ReAct 后成型 |

**Post-Phase 1 re-check**: 设计未引入新违规；两处设计取舍（契约放 core、`SableTool` 提前落地）记入 Complexity Tracking，均为技术方案/课件锚点支撑，非违规。

## Project Structure

### Documentation (this feature)

```text
specs/001-lesson16-provider/
├── plan.md              # 本文件
├── spec.md              # /speckit-specify 产出
├── research.md          # Phase 0：D-01~D-12 决策与核实证据
├── data-model.md        # Phase 1：Profile / ProviderConfig / llm_calls
├── quickstart.md        # Phase 1：可执行的验收向导
├── contracts/           # Phase 1：provider-service / provider-config / llm-calls-schema
└── tasks.md             # /speckit-tasks 产出
```

### Source Code (repository root)

```text
sableos-core/src/main/java/com/sableos/core/
├── agent/                # AgentLoader（扫 .sableos/agents/、deriveProfile、坏目录不阻断）
├── profile/              # Profile（全字段记录类）/ ProfileRegistry
│                         #   （frontmatter 校验失败走 IllegalArgumentException，报错点名 provider/Agent，不新增异常类型）
├── provider/             # 契约与中立 DTO：ProviderService / ProviderRequest / ProviderResponse
│                         #                    / ToolCallRequest / Usage / LlmCallAuditor
└── tool/                 # SableTool / ToolResult（§6.1 契约）

sableos-provider/src/main/java/com/sableos/provider/
├── ProvidersProperties.java        # @ConfigurationProperties(prefix="sableos")：providers 清单 + validate()
├── ProviderChatModelFactory.java   # 显式构造 name→ChatModel（openai / dashscope 两型）
├── ToolSchemaAdapter.java          # SableTool→ToolCallback 只翻译不执行（call() 永远抛）
├── SpringAiProviderServiceImpl.java# 路由→Prompt→调用→审计（成败两态）
└── ProviderNotFoundException.java

sableos-storage/src/main/java/com/sableos/storage/
├── LlmCall.java / LlmCallRepository.java / JpaLlmCallAuditor.java
sableos-storage/src/main/resources/db/migration/
├── sqlite/V1__llm_calls.sql
└── postgresql/V1__llm_calls.sql    # 双轨 1:1

sableos-boot/src/main/java/com/sableos/boot/
├── SableOsApplication.java         # + @EntityScan/@EnableJpaRepositories（com.sableos.storage）
├── provider/ProviderConfiguration.java   # ProvidersProperties/适配器/审计/ProviderService 装配
└── agent/AgentConfiguration.java         # AgentLoader + ProfileRegistry（启动加载）
sableos-boot/src/main/resources/application.yaml  # spring.ai.dashscope.enabled=false + sableos.providers

tests（与实现同行）:
sableos-core/src/test/java/com/sableos/core/agent/AgentLoaderTest.java
sableos-provider/src/test/java/com/sableos/provider/ProviderServiceTest.java
sableos-provider/src/test/java/com/sableos/provider/ToolSchemaAdapterTest.java
sableos-provider/src/test/java/com/sableos/provider/ProvidersPropertiesTest.java
sableos-storage/src/test/java/com/sableos/storage/LlmCallRepositoryTest.java
sableos-boot/src/test/java/com/sableos/boot/ProviderAssemblyTest.java
sableos-boot/src/test/java/com/sableos/boot/ProviderSmokeIT.java（@Tag("integration")）
```

**Structure Decision**: 沿用既有 9 模块骨架，不新增模块；本节改动集中在 core / provider / storage / boot 四模块。契约（接口 + 中立 DTO）与实现分置 core 与 provider，保证 core 的 ReActLoop（第 17 节）只依赖 core 自身——依赖方向 core ← provider / storage ← boot，绝不反向（research D-01）。

## Complexity Tracking

| 取舍 | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| 契约（`ProviderService` 接口 + 中立 DTO）放 core，实现放 provider | 第 17 节 ReActLoop 在 core 内消费 Provider；core 不得反向依赖 provider 模块，也不得染 Spring AI 类型 | 接口直接放 provider（技术方案 §10 字面）会让 core→provider 反向依赖，破坏模块地基 |
| `SableTool`/`ToolResult` 提前到 16 节（core） | 课件 16 的「工具格式适配器」入参就是 `SableTool.getInputSchema()`；技术方案 §6.1 已定义契约 | 等到 20 节才建契约，则 16 节适配器无类型可译，20 节还要回改前序节公共接口 |
| DashScope starter 进 provider 但 boot 属主禁用（`spring.ai.dashscope.enabled=false`） | 技术栈锁定 Spring AI Alibaba（多协议接入能力）；其 10 个 autoconfig 默认全激活且无 key 即启动失败 | 排除类清单（10 个 FQCN）在升级后静默失效；裸 jar 无 BOM 覆盖且需另行核实完整依赖 |
| 迁移脚本按节手写 `V1__llm_calls.sql`（非一次性五表 T015 合并） | storage README 的 T015 预留位已随 `specs/001-runtime-core` 删除失效；按节落表可让每节迁移各自可验证 | 预建五表会让未实现能力空转，且 README 引用已悬空 |
