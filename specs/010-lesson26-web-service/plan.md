# Implementation Plan: Web Service——对外 REST API 门面（仅后端）

**Branch**: `010-lesson26-web-service` | **Date**: 2026-10-29 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/010-lesson26-web-service/spec.md`

## Summary

在 `sableos-web` 实现六个 REST Controller 共 10 个端点，统一前缀 `/api/v1`，把既有 `AgentService.process`、`SessionManager`、`ProfileRegistry`、`ToolRegistry`、`MemoryProvider` 暴露给业务系统。会话管理 4 个（建、发消息、查历史、软归档）、Agent 无状态调用 1 个、信息查询 3 个（Profile / Memory / Tool）、系统状态 2 个（health / info）。Controller 只做校验、包装与错误处理；所有异常经既有 `ApiResponse` 五字段信封与 `GlobalExceptionHandler` 输出。新增 60 秒调用上限辅助类（基于 Spring `AsyncTaskExecutor` + `Future.get`），新增 Provider 实时连通探测契约与 HTTP 实现，新增 Provider 调用失败到 503 的统一映射。不交付前端、静态资源、SPA 回落与任何前端 skill。

## Technical Context

**Language/Version**: Java 21（父 POM enforcer 钉死 JDK 21 / Maven）

**Primary Dependencies**: Spring Boot 3.5.16 / Spring Framework 6.2.19；已存在的 `spring-boot-starter-web`、`spring-boot-starter-validation`、`springdoc-openapi-starter-webmvc-ui`；`sableos-core`（AgentService / SessionManager / ProfileRegistry / ToolCatalog / MemoryProvider）、新增 `sableos-tool`（`ToolRegistry.all()`）。**不新增任何第三方依赖**；不引入 `spring-ai-openai-spring-boot-starter`，因此不添加 `OpenAiAutoConfiguration` 排除项（当前 classpath 无该类，排除会导致启动失败）。

**Storage**: 复用既有 `sessions` 表（含 `status` / `archived_at`，18 节已落地）。本节不新增表、不改 Flyway 迁移、不动 `ddl-auto`。

**Testing**: JUnit 5 / Mockito 5 / AssertJ（父 POM 已提供）。新增 `SessionApiControllerTest`、`AgentCallExecutorTest`、`HttpProviderHealthProbeTest`；扩展既有 `GlobalExceptionHandlerTest`；`WebSmokeIT` 放 `sableos-boot`（跨模块真实装配 + 临时 SQLite + `@AutoConfigureMockMvc`）。父 POM surefire 增加 `**/*IT.java` include（`ProviderSmokeIT` 仍由 `@Tag("integration")` 默认排除）。真实模型、真实故障注入列 quickstart 人工项。

**Target Platform**: JVM 单二进制（`sableos-boot` fat JAR）；`serve` 常驻，默认 8080。

**Project Type**: Maven 多模块后端单体（9 模块骨架 + 既有业务模块）。

**Performance Goals**: 200 并发无状态调用不出现请求线程饥饿；`GET /info` 的 Provider 探测单条上限 5 秒（2s connect + 3s read），顺序执行，整体受提供方数量线性约束；`GET /sessions/{id}` 最多返回最近 100 条。

**Constraints**: 单条消息 32KB；Agent 调用 60 秒超时 → 504；无认证 / 无流式 / 无限流 / 无 RBAC；CORS 仅对 `/api/**` 全开放；`session_id` 只由 `SessionManager.sessionId` 拼接；不新建 `TaskDecorator`；不在 `synchronized` 内做阻塞调用；用户可控串入日志先 CRLF 净化。

**Scale/Scope**: 生产代码预计新增 6 个 Controller、1 个超时辅助类、1 个 CORS 配置、约 10 个 DTO、1 个 core 契约（`ProviderStatus` + `ProviderHealthProbe`）、1 个 core 异常、1 个 provider 探测实现；修改 `SessionManager` / `JpaSessionManager` / `SpringAiProviderServiceImpl` / `ProviderConfiguration` / `GlobalExceptionHandler` / `sableos-web/pom.xml` / 父 POM（surefire include）。无前端、无表结构、无配置键变更。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据 |
|---|---|---|
| I. JDK 21 + Spring Boot 3.x 单体 | ✅ | 只使用既有 Spring MVC + 虚拟线程；不引入响应式栈与外部中间件 |
| II. 自实现 ReAct 循环 | ✅ | Controller 只调用既有 `AgentService.process`；不触碰 `ReActLoop` / `ToolExecutor` |
| III. Spring AI 只用一半 | ✅ | 不新增 Spring AI 自动装配；不启用自动工具执行；无 Spring AI 类型进入 web |
| IV. Provider 显式映射 | ✅ | 探测按 `ProvidersProperties` 清单遍历，不扫描类型 |
| V. Tool 单模块 | ✅ | 不新增工具模块；web 只读 `ToolRegistry.all()` |
| VI. 审计 day one 落库 | ✅ | 发消息 / invoke 复用 `AgentService.process`，`llm_calls` 与 `tool_invocations` 照常落库 |
| VII. Sandbox 接口先行 | ✅ | 本节不新增外部 IO 执行路径；工具执行仍由 24 节 Sandbox 约束 |
| VIII. 范围收敛 | ✅ | 不做认证、流式、限流、RBAC、Agent 增删改、会话列表、前端 |
| IX. 每个 user story 可演示 | ✅ | 三个 user story 由 controller 单测 + `WebSmokeIT` 覆盖；真模型联调列人工项 |
| AGENTS §5.1 #6 表结构唯一权威 | ✅ | 不改表、不新增迁移；复用 `status` / `archived_at` |
| AGENTS §5.2 虚拟线程 / traceId | ✅ | 使用 Boot 既有 `applicationTaskExecutor` 与唯一 `TaskDecorator`；不新建线程池；traceId 仍由 `TraceIds` 生成 |
| AGENTS §5.2 阻塞调用与锁 | ✅ | 无 `synchronized`；超时等待走 `Future.get(timeout)`，不在锁内做阻塞 IO |
| AGENTS §5.2 日志 CRLF 净化 | ✅ | 新增日志点（探测失败原因、异常）统一 `replaceAll("[\r\n]", "_")` |
| AGENTS §5.3 质量门槛 | ✅ | 实现结束跑 `mvn clean verify`，Spotless/Checkstyle/P3C/SpotBugs 全绿 |

**Post-Phase 1 re-check**: 设计未引入新违规。需要记录的取舍见 Complexity Tracking：`sableos-web` 新增 `sableos-tool` 编译依赖、`SessionManager` 新增 `archive` 方法、Provider 调用失败改为包装成 core 异常。

## Project Structure

### Documentation (this feature)

```text
specs/010-lesson26-web-service/
├── plan.md              # 本文件
├── research.md          # Phase 0 决策记录
├── data-model.md        # Phase 1 请求/响应与状态模型
├── quickstart.md        # Phase 1 验证指南
├── contracts/
│   └── rest-api.md      # 10 个端点的契约
├── checklists/
│   └── requirements.md  # spec 质量检查
└── tasks.md             # Phase 2（/speckit-tasks 产出）
```

### Source Code (repository root)

```text
sableos-core/src/main/java/com/sableos/core/
├── provider/
│   ├── ProviderFailureException.java      # 新增：提供方调用失败（→503）
│   ├── ProviderHealthProbe.java           # 新增：实时连通探测契约
│   └── ProviderStatus.java                # 新增：探测结果值对象
└── session/
    └── SessionManager.java                # 修改：新增 archive(String) → boolean

sableos-provider/src/main/java/com/sableos/provider/
└── HttpProviderHealthProbe.java           # 新增：基于 JDK HttpClient 的连通探测
sableos-provider/src/main/java/com/sableos/provider/
└── SpringAiProviderServiceImpl.java       # 修改：调用失败包装为 ProviderFailureException

sableos-web/src/main/java/com/sableos/web/
├── WebConfiguration.java                  # 新增：CORS（仅 /api/**）
├── GlobalExceptionHandler.java            # 修改：新增 ProviderFailureException → 503
└── api/
    ├── SessionApiController.java          # 新增
    ├── AgentApiController.java            # 新增
    ├── ProfileApiController.java          # 新增
    ├── MemoryApiController.java           # 新增
    ├── ToolApiController.java             # 新增
    ├── SystemApiController.java           # 新增
    ├── AgentCallExecutor.java             # 新增：包内可见，60s 超时
    └── dto/                               # 新增：请求/响应记录

sableos-storage/src/main/java/com/sableos/storage/
└── JpaSessionManager.java                 # 修改：实现 archive（软归档）

sableos-boot/src/main/java/com/sableos/boot/provider/
└── ProviderConfiguration.java             # 修改：装配 ProviderHealthProbe

sableos-boot/src/test/java/com/sableos/boot/
└── WebSmokeIT.java                        # 新增：真实装配冒烟

sableos-web/src/test/java/com/sableos/web/
├── api/SessionApiControllerTest.java      # 新增
├── api/AgentCallExecutorTest.java         # 新增
└── GlobalExceptionHandlerTest.java        # 修改：补 ProviderFailureException

sableos-provider/src/test/java/com/sableos/provider/
└── HttpProviderHealthProbeTest.java       # 新增
```

**Structure Decision**: 沿用既有 Maven 多模块。Controller 与 DTO 收在新包 `com.sableos.web.api`，与既有 `com.sableos.web.sandbox`、`com.sableos.web.trace` 并列；跨模块契约（Provider 探测、Provider 失败异常）放 `sableos-core`，实现放对应能力模块，保持 `web → core` 为主、仅额外允许 `web → tool`（读取工具清单）。

## Complexity Tracking

| 取舍 | 为什么需要 | 更简单的替代为何被否 |
|---|---|---|
| `sableos-web` 新增 `sableos-tool` 依赖 | `GET /tools` 要列全部已注册工具，`ToolRegistry.all()` 在 tool 模块；core 的 `ToolCatalog` 只有按 Profile 过滤与按名查找 | 给 core `ToolCatalog` 加 `all()` 会破坏既有匿名实现与测试，且契约层不需要“列全部”语义；`web→tool→core` 不成环 |
| `SessionManager` 新增 `archive(String): boolean` | 软归档是会话生命周期的一部分，必须收口在会话身份的唯一实现处；web 不应直接操作 storage 实体 | 在 web 层直接改库会让 `SessionManager` 之外出现第二套会话写路径，违反 H4④ 的收口原则 |
| Provider 调用失败包装为 core `ProviderFailureException` | web 不能依赖 provider（会形成 web→provider 的额外边且异常类型不可达）；core 契约 + advice 映射才能稳定输出 503 | 把 `ProviderUnavailableException` 放 core 会改动既有 web 异常与测试；用异常类名反射分类脆弱且不可测 |
| 父 POM surefire 增加 `**/*IT.java` | 课件要求 `WebSmokeIT` 作为常规验收的一部分，而 surefire 默认只跑 `*Test` | 改名为 `WebSmokeTest` 偏离课件锚点；`@Tag("integration")` 会让它在 CI 默认跳过，失去“及早发现仓库扫描失败”的价值 |
