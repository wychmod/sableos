# Tasks: Web Service 对外 REST 门面（仅后端）

**Input**: Design documents from `specs/010-lesson26-web-service/`

**Prerequisites**: plan.md、spec.md、research.md、data-model.md、contracts/rest-api.md

**Tests**: 本 feature 明确要求验收 harness，故每个 user story 均含测试任务；测试先于或伴随实现（Java 允许先建最小可编译骨架使测试先红）。

**Organization**: 按 user story 分组；每个 story 可独立验收。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1 / US2 / US3
- 所有路径均为仓库相对路径

## Path Conventions

Maven 多模块：`sableos-core/src/main/java/com/sableos/core/...`、`sableos-provider/...`、`sableos-web/...`、`sableos-storage/...`、`sableos-boot/...`。

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 把本节需要的模块边与测试发现配置一次到位。

- [X] T001 在 `sableos-web/pom.xml` 增加 `com.sableos:sableos-tool` 编译依赖（`ToolRegistry.all()` 需要；`web→tool→core` 不成环）。判据：`mvn -q -pl sableos-web -am dependency:tree` 能看到 `sableos-tool` 且无环。
- [X] T002 在根 `pom.xml` 的 `maven-surefire-plugin` 配置中增加 `<includes>`：`**/*Test.java`、`**/*Tests.java`、`**/*IT.java`（保留既有 `excludedGroups=integration`）。判据：`mvn -pl sableos-boot test -Dtest=WebSmokeIT` 能发现并执行该类；`ProviderSmokeIT` 仍被跳过。

---

## Phase 2: Foundational（阻塞性前置）

**Purpose**: 所有 user story 共用的契约、异常、超时机制、CORS 与装配。完成前不得开始 story 实现。

- [X] T003 [P] 新增 `sableos-core/src/main/java/com/sableos/core/provider/ProviderStatus.java`：record `ProviderStatus(String name, String type, boolean reachable, Long latencyMs, String error)`。判据：可被 provider/web 编译引用。
- [X] T004 [P] 新增 `sableos-core/src/main/java/com/sableos/core/provider/ProviderHealthProbe.java`：`List<ProviderStatus> probe()`，javadoc 说明“实时 HTTP 连通探测、不调模型、不耗 token”。判据：接口编译通过。
- [X] T005 [P] 新增 `sableos-core/src/main/java/com/sableos/core/provider/ProviderFailureException.java`：`extends RuntimeException`，构造 `(String message, Throwable cause)`。判据：可被 provider/web 引用。
- [X] T006 修改 `sableos-core/src/main/java/com/sableos/core/session/SessionManager.java` 增加 `boolean archive(String sessionId)`（javadoc：软归档、命中 true、未命中 false、重复归档幂等）；修改 `sableos-storage/src/main/java/com/sableos/storage/JpaSessionManager.java` 实现：置 `status=archived`，首次写 `archived_at`，重复归档保留原值。判据：`mvn -pl sableos-storage -am test` 既有会话测试仍绿。
- [X] T007 [P] 新增 `sableos-provider/src/test/java/com/sableos/provider/HttpProviderHealthProbeTest.java`：用 JDK `com.sun.net.httpserver.HttpServer` 随机端口模拟可达（任意 HTTP 响应 → `reachable=true`）、用已关闭端口模拟不可达（`reachable=false` 且 `error` 非空）；断言探测不请求外网。判据：测试可编译并预期先红。
- [X] T008 新增 `sableos-provider/src/main/java/com/sableos/provider/HttpProviderHealthProbe.java`：遍历 `ProvidersProperties`，按类型取 base-url（openai 用配置值；dashscope 缺省 `https://dashscope.aliyuncs.com`），JDK `HttpClient` connect 2s / request 3s 发 `HEAD`，任何 HTTP 响应判可达；`error` 做 CRLF 净化并截断；顺序探测，单条失败不影响其它。判据：T007 转绿。
- [X] T009 修改 `sableos-provider/src/main/java/com/sableos/provider/SpringAiProviderServiceImpl.java`：`model.call` 抛 `RuntimeException` 时先落 `success=false` 审计，再抛 `ProviderFailureException`（message 含原始原因、cause 保留）。判据：`ProviderServiceTest#failedCall_auditRecordsFailureAndRethrows` 仍绿（`RuntimeException` 断言与 message 包含性不变）。
- [X] T010 修改 `sableos-boot/src/main/java/com/sableos/boot/provider/ProviderConfiguration.java`：新增 `@Bean ProviderHealthProbe providerHealthProbe(ProvidersProperties)`，返回 `new HttpProviderHealthProbe(...)`。判据：`mvn -pl sableos-boot -am test -Dtest=ProviderAssemblyTest` 绿。
- [X] T011 新增 `sableos-web/src/test/java/com/sableos/web/api/AgentCallExecutorTest.java`：①mock `AgentService.process` 阻塞 200ms、超时设 50ms → 抛 `AgentTimeoutException`；②`AgentService` 抛 `ProviderFailureException` → 原类型透出（不被包成 `ExecutionException`）；③正常返回透传。判据：先红后绿。
- [X] T012 新增 `sableos-web/src/main/java/com/sableos/web/api/AgentCallExecutor.java`（包内可见）：构造注入 `AgentService` 与 `AsyncTaskExecutor`，60 秒常量；`submit` + `Future.get`，超时 `cancel(true)` 抛 `AgentTimeoutException`，中断恢复标志并抛 `IllegalStateException`，`ExecutionException` 解包 cause。判据：T011 转绿。
- [X] T013 修改 `sableos-web/src/test/java/com/sableos/web/GlobalExceptionHandlerTest.java`：新增“ProviderFailureException → 503、message 固定‘模型提供方调用失败’、响应体不含原始内部文本”用例；保留既有 500 不泄漏断言。判据：先红。
- [X] T014 修改 `sableos-web/src/main/java/com/sableos/web/GlobalExceptionHandler.java`：新增 `ProviderFailureException` 映射（503 + 通用话术，日志记录原始异常）。不改 `IllegalStateException` 映射。判据：T013 转绿。
- [X] T015 [P] 新增 `sableos-web/src/main/java/com/sableos/web/WebConfiguration.java`：`WebMvcConfigurer` 仅对 `/api/**` 配置 `allowedOrigins("*")`、`allowedMethods(GET,POST,DELETE,OPTIONS)`、`allowedHeaders("*")`、`allowCredentials(false)`。判据：编译通过；`WebSmokeIT` 的 OPTIONS 预检用例（T025）绿。

**Checkpoint**: 契约、异常、超时、CORS 就绪，三个 user story 可并行开工。

---

## Phase 3: User Story 1 - 会话连续对话（Priority: P1）🎯 MVP

**Goal**: 建会话、发消息、查历史、软归档四个端点走通，且发消息复用既有 `AgentService.process`。

**Independent Test**: `SessionApiControllerTest` 全绿；`WebSmokeIT` 能建会话并查到空历史。

### Tests for User Story 1

- [X] T016 [US1] 新增 `sableos-web/src/test/java/com/sableos/web/api/SessionApiControllerTest.java`（先红）：超 32KB → 400；会话不存在 → 404；正常发消息时 `AgentService.process` 恰被调用一次；同三元组建会话两次返回同一 id；归档不存在 → 404；归档成功 → 200；`GET /sessions/{id}` 最多返回 100 条。判据：用例覆盖上述 7 点。

### Implementation for User Story 1

- [X] T017 [P] [US1] 新增 `sableos-web/src/main/java/com/sableos/web/api/dto/` 下 `CreateSessionRequest`、`SendMessageRequest`、`SessionView`、`MessageView`、`ReplyView`（record；字段与 `data-model.md` §1 逐字一致）。判据：编译通过。
- [X] T018 [US1] 新增 `sableos-web/src/main/java/com/sableos/web/api/SessionApiController.java`：`POST /api/v1/sessions`（profile 必填、userId 缺省 anonymous、Agent 未注册 404）、`POST /{id}/messages`（空白/超 32KB→400、会话缺失 404、经 `AgentCallExecutor` 调 `AgentService.process`）、`GET /{id}`（最近 100 条、归档仍可查）、`DELETE /{id}`（`SessionManager.archive` true→200、false→404）。判据：T016 转绿。
- [X] T019 [US1] 运行 `mvn -pl sableos-web -am test -Dtest='SessionApiControllerTest,GlobalExceptionHandlerTest'`。判据：全绿。

**Checkpoint**: 会话四端点可独立演示。

---

## Phase 4: User Story 2 - 无状态调用（Priority: P2）

**Goal**: 按 Agent 名一次性调用，使用独立临时会话，不共享历史。

**Independent Test**: `AgentApiControllerTest` 全绿。

### Tests for User Story 2

- [X] T020 [US2] 新增 `sableos-web/src/test/java/com/sableos/web/api/AgentApiControllerTest.java`（先红）：Agent 未注册 → 404；message 空白/超 32KB → 400；两次调用传入的会话 id 不同；正常调用返回 `reply` 且 `AgentService.process` 恰一次。判据：用例覆盖上述 4 点。

### Implementation for User Story 2

- [X] T021 [P] [US2] 新增 `sableos-web/src/main/java/com/sableos/web/api/dto/InvokeRequest.java`（`message`）。判据：编译通过。
- [X] T022 [US2] 新增 `sableos-web/src/main/java/com/sableos/web/api/AgentApiController.java`：`POST /api/v1/agents/{name}/invoke`；校验 Agent 注册（404）与消息上限（400）；`SessionManager.getOrCreate("web", "invoke-" + UUID.randomUUID(), name)` 后经 `AgentCallExecutor` 调 `AgentService.process`。判据：T020 转绿。
- [X] T023 [US2] 运行 `mvn -pl sableos-web -am test -Dtest='AgentApiControllerTest,AgentCallExecutorTest'`。判据：全绿。

**Checkpoint**: 会话链路与无状态调用均可独立演示。

---

## Phase 5: User Story 3 - 状态与资源查询（Priority: P3）

**Goal**: Profile / Memory / Tool / health / info 五个只读端点；info 带 Provider 实时连通状态。

**Independent Test**: `WebSmokeIT` + `SystemApiControllerTest` 全绿。

### Tests for User Story 3

- [X] T024 [US3] 新增 `sableos-boot/src/test/java/com/sableos/boot/WebSmokeIT.java`（先红）：`@SpringBootTest(classes = SableOsRuntime.class)` + `@AutoConfigureMockMvc` + 临时 SQLite/工作区；断言 `/api/v1/health`、`/api/v1/info`、`/api/v1/profiles`、`/api/v1/tools`、`/v3/api-docs` 返回 200 且信封形状正确；`/api/**` 的 OPTIONS 预检带 CORS 头。判据：用例覆盖上述 6 点。
- [X] T025 [US3] 新增 `sableos-web/src/test/java/com/sableos/web/api/SystemApiControllerTest.java`（先红）：health 返回 ok；info 应用名/版本非空；mock `ProviderHealthProbe` 返回一条可达 + 一条不可达，断言原样映射且整体 200。判据：用例覆盖上述 3 点。

### Implementation for User Story 3

- [X] T026 [P] [US3] 新增 `sableos-web/src/main/java/com/sableos/web/api/dto/` 下 `ProfileView`、`ToolView`、`MemoryView`、`HealthView`、`SystemInfoView`、`ProviderStatusView`（字段与 `data-model.md` §1 一致；`ProfileView` 不得含 `identity.prompt`）。判据：编译通过。
- [X] T027 [US3] 新增 `sableos-web/src/main/java/com/sableos/web/api/ProfileApiController.java`：`GET /api/v1/profiles`，映射 `ProfileRegistry.all()`。判据：`WebSmokeIT` 对应断言绿。
- [X] T028 [US3] 新增 `sableos-web/src/main/java/com/sableos/web/api/MemoryApiController.java`：`GET /api/v1/memory?profile=default`；已注册 Profile 调 `MemoryProvider.memorySection`，未注册或未写入返回空字符串。判据：`WebSmokeIT` 对应断言绿。
- [X] T029 [US3] 新增 `sableos-web/src/main/java/com/sableos/web/api/ToolApiController.java`：`GET /api/v1/tools`，映射 `ToolRegistry.all()`。判据：`WebSmokeIT` 对应断言绿。
- [X] T030 [US3] 新增 `sableos-web/src/main/java/com/sableos/web/api/SystemApiController.java`：`GET /api/v1/health`、`GET /api/v1/info`（注入 `ProviderHealthProbe` 与 `${info.app.name}` / `${info.app.version}`）。判据：T025 转绿。
- [X] T031 [US3] 运行 `mvn -pl sableos-web,sableos-boot -am test -Dtest='SystemApiControllerTest,WebSmokeIT'`。判据：全绿。

**Checkpoint**: 10 个端点全部可独立演示。

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T032 运行 `mvn spotless:apply` 并检查格式改动。判据：无 Spotless 违规。
- [X] T033 运行 `mvn clean verify`。判据：Spotless / Checkstyle / P3C / SpotBugs / FindSecBugs 全绿，全部单测与 `WebSmokeIT` 通过，`ProviderSmokeIT` 被跳过。
- [X] T034 逐条自查 AGENTS §5.1/5.2 与 H4 不变量：无 `synchronized` 包裹阻塞调用、无自建线程池/`CompletableFuture`/Reactor、无第二个 `TaskDecorator`、traceId 仍由 `TraceIds`、日志用户输入已 CRLF 净化、`session_id` 只在 `SessionManager.sessionId` 拼接、无明文 key。判据：grep 结果与人工复核记录写入验收报告。
- [X] T035 更新 `AGENTS.md`：第 2 章记录本节落地、第 10 章记录 26 节新增契约（`ProviderHealthProbe` / `ProviderFailureException` / `SessionManager.archive`）与“课件 OpenAI 排除项不适用”的口径。判据：`AGENTS.md` 内容与代码一致。
- [X] T036 按 `quickstart.md` 过一遍无 key 可验证项；把需要真模型/真实故障注入的项目列成人工清单交给用户。判据：清单与实际环境能力一致。

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1** 无依赖。
- **Phase 2** 依赖 Phase 1；完成后 US1/US2/US3 可并行。
- **Phase 3（US1）**、**Phase 4（US2）**、**Phase 5（US3）** 彼此独立，仅共用 Phase 2 的 `AgentCallExecutor`、异常映射与 DTO 基础。
- **Phase 6** 依赖全部 story。

### Within Each Story

- 测试任务先写（T016/T020/T024/T025）。
- DTO 先于 Controller。
- Controller 完成即跑该模块测试。

### Parallel Opportunities

- T003 / T004 / T005 可并行。
- T007 与 T011、T013、T015 可并行（不同文件）。
- T017 / T021 / T026 的 DTO 可并行。
- US1 / US2 / US3 在 Phase 2 完成后可由不同人并行推进。

---

## Implementation Strategy

### MVP First

1. Phase 1 + Phase 2。
2. US1（会话四端点）→ `SessionApiControllerTest` 绿 → 可演示“建会话 → 发消息 → 查历史 → 归档”。

### Incremental Delivery

1. US1：会话链路。
2. US2：无状态调用。
3. US3：只读查询与系统状态。
4. Phase 6：全量门禁 + 文档同步 + 人工清单。

### 明确不在任务内

- 管理台前端、静态资源、SPA 回落、前端风格 skill。
- `GET /api/v1/sessions` 列表（27 节）。
- Agent 增删改（29/30 节）。
- 认证 / SSE / WebSocket / 限流 / RBAC。
- 新表、新 Flyway 迁移、新配置键。
