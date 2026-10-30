# Phase 0 Research: Web Service 门面

**Feature**: `010-lesson26-web-service` | **Date**: 2026-10-29

本文件记录实现前需要定死、且课件与技术方案没有逐字给出答案的技术决策。每条给出 Decision / Rationale / Alternatives considered。

## D-01 Controller 的模块依赖边界

**Decision**: 六个 Controller 放 `sableos-web`；`sableos-web` 在既有 `sableos-core` 之外只新增 `sableos-tool` 依赖。Profile 查询走 core 的 `ProfileRegistry`，记忆查询走 core 的 `MemoryProvider`，Provider 探测走 core 的 `ProviderHealthProbe`，会话走 core 的 `SessionManager`；只有工具清单直接读 `ToolRegistry.all()`。

**Rationale**: `web → tool → core` 无环；core 的 `ToolCatalog` 本意是 ReAct 侧“按 Profile 取可用工具”，没有“列全部”的语义，硬加 `all()` 会迫使既有匿名实现与测试同步改动。其余查询能力在 core 已有或可由 core 契约表达，避免 web 依赖 memory/provider 模块。

**Alternatives considered**: ① web 直接依赖 memory/provider/tool 三个模块——能跑通，但 web 变成“装配感知型”模块，依赖面扩大；② 给 `ToolCatalog` 加 `all()`——破坏 core 契约最小性，并连带改测试。

## D-02 60 秒超时的实现机制

**Decision**: 新增包内可见 `AgentCallExecutor`，注入 `AgentService` 与 Spring 的 `AsyncTaskExecutor`，用 `submit(Callable)` + `Future.get(60, SECONDS)`；超时 `cancel(true)` 并抛既有 `AgentTimeoutException`。异常解包：`ExecutionException` 取 cause，`RuntimeException` 原样抛。

**Rationale**: 执行器用 Spring 的 `AsyncTaskExecutor`（`SimpleAsyncTaskExecutor` + 虚拟线程），并复用容器唯一 `TraceTaskDecorator`，traceId / MDC 能跨线程传播；每任务一个虚拟线程、不是线程池，也不使用 `CompletableFuture` / Reactor（H4⑤）。超时后底层 LLM 调用可能不响应中断，允许其在后台收尾；请求线程立即释放。

**实现修正（2026-10-29）**: 原计划注入 Boot 的 `applicationTaskExecutor`，但 `TaskExecutionAutoConfiguration` 带 `@ConditionalOnMissingBean(Executor.class)`，而容器已有调度用 `agentTaskScheduler`（也是 `Executor`），Boot 根本不会创建该 bean——直接按名注入会在 `WebSmokeIT` 启动期失败。改为在 `ApiConfiguration` 显式声明仅服务 Agent 调用的虚拟线程执行器（`setVirtualThreads(true)` + 注入 `TaskDecorator`），与调度线程池隔离，长调用不会占住定时任务槽位。

**Alternatives considered**: ① `Executors.newVirtualThreadPerTaskExecutor()`——自建线程池，违反 H4⑤；② 把 Provider 的 HTTP read timeout 改成 60 秒——只能约束单次模型调用，ReAct 多轮仍可能超过 60 秒，且无法返回 504；③ 不实现真实超时、只保留映射——不满足课件人工验收项“构造超 60 秒调用拿 504”。

## D-03 Provider 调用失败的 503 分类

**Decision**: 在 core 新增 `ProviderFailureException`；`SpringAiProviderServiceImpl` 在 `model.call` 抛 `RuntimeException` 时，先落 `success=false` 审计，再抛 `new ProviderFailureException("模型提供方调用失败: " + 原始 message, cause)`。`GlobalExceptionHandler` 新增该异常映射，响应体固定为“模型提供方调用失败”，不回显原始 message。既有 `ProviderUnavailableException → 503` 与 `AgentTimeoutException → 504` 映射保留。

**Rationale**: web 不能依赖 provider 模块，core 异常是唯一能跨模块传递的类型；审计仍先落库（原则 VI）；通用话术避免把连接串、密钥、堆栈反射给调用方（FR-010）。不把 `IllegalStateException` 整体映射为 503——那会把真实的内部状态错误误报成提供方故障。

**Alternatives considered**: ① 在 web 用异常类名 / 包名反射判断——脆弱且难测；② 把 web 的 `ProviderUnavailableException` 下沉到 core——改动既有异常与测试面更大；③ 所有 `RuntimeException` 都映射 503——会把 500 类错误误分类。

## D-04 Provider 实时连通探测的语义

**Decision**: 新增 core 契约 `ProviderHealthProbe` + 记录 `ProviderStatus(name, type, reachable, latencyMs, error)`；provider 模块 `HttpProviderHealthProbe` 对每个已配置 Provider 的 base-url 发一次 `HEAD`（部分端点点不支持 HEAD 时也接受任意 HTTP 响应为“可达”），connect timeout 2s、read timeout 3s；任何 HTTP 响应（含 401/404）判可达，连接失败 / 超时判不可达。顺序探测，单条失败只影响该条。不调用模型、不消耗 token。DashScope 未配 base-url 时用 `https://dashscope.aliyuncs.com`。

**Rationale**: 用户已确认要“实时探测连通性”；HTTP 连通探测能真实反映“网络与端点是否可达”，又不产生推理成本，CI / 无 key 环境也不会因探测失败而整体报错。`error` 只保留异常类型与短消息，入响应前做 CRLF 净化与截断。

**Alternatives considered**: ① 真发一次 `chat` 请求——耗 token、慢、需要有效模型名，CI 不可跑；② 只报配置状态——用户明确否掉；③ 并行探测——收益有限且需自建/注入线程池，核心阶段提供方数量少，顺序足够。

## D-05 会话归档语义

**Decision**: `SessionManager` 新增 `boolean archive(String sessionId)`；`JpaSessionManager` 命中行时置 `status=archived`、首次归档写 `archived_at`，重复归档保持原 `archived_at` 并返回 true；未命中返回 false。Controller 将 false 映射 404。归档后 `GET /sessions/{id}` 仍返回历史；归档状态不阻止后续消息（本节不做状态迁移与访问控制）。

**Rationale**: 技术方案 §9.2 的 `sessions` 表本就有 `status` / `archived_at`；软归档保留可追溯性；`boolean` 返回值避免 storage 反向依赖 web 的异常类型。27 节课件明确提到“沿用 26 节扩展 `archive()` 的路子”，说明该扩展是本节的预期改动。

**Alternatives considered**: ① 物理删除——丢审计痕迹，与 §9.2 字段冲突；② 归档后查询 404——用户已选择“行保留、仍可查”；③ `void archive` + 抛领域异常——storage 需要依赖 web 或新增异常，增加跨模块耦合。

## D-06 会话历史与消息的响应形状

**Decision**: `GET /sessions/{id}` 返回会话元数据 + `messages`（按时间正序，最多最近 100 条）。每条消息字段：`role`（USER/ASSISTANT/TOOL）、`content`、`toolName`、`success`；不返回模型提出的原始 `toolCalls`（对外门面只给可读结果，原始调用参数留在内部历史与审计表中）。

**Rationale**: 32KB 限制与 100 条上限是课件明确的防呆边界；工具调用参数可能是内部约定，不扩大对外暴露面。27 节的 harness 只断言历史里能看到用户消息、模型回复与工具结果，当前形状足够。

**Alternatives considered**: 返回完整 `SessionMessage`（含 `toolCalls`）——信息全但对外契约变宽，且部分工具参数可能含敏感值。

## D-07 `GET /memory` 的入参与缺失处理

**Decision**: `profile` 查询参数可选，缺省 `default`。已注册 Profile → 调 `MemoryProvider.memorySection(profile)`；未注册 Profile 或文件不存在 → 200 + 空字符串内容。响应 `{profile, content}`。

**Rationale**: 用户已确认默认 `default`；27 节 harness 不传参即可用。未写入记忆返回空内容而不是错误，符合 FR-013 与 SC-006（干净环境只读端点可用）。不引入按关键词检索接口（27 节明确“不强制”）。

**Alternatives considered**: profile 必填——会让 27 节既有调用失败；未知 profile 404——在干净环境把“没写过”误报成“资源不存在”。

## D-08 建会话与无状态调用的身份来源

**Decision**: 建会话请求体 `{profile, userId?}`，`channel` 固定 `"web"`，`userId` 缺省 `"anonymous"`；调 `SessionManager.getOrCreate("web", userId, profile)`，同一三元组幂等。无状态调用请求体 `{message}`，用 `userId = "invoke-" + UUID.randomUUID()`（仅作一次性身份，不用于 traceId）后走同一 `getOrCreate`，保证会话 id 仍只由 `SessionManager.sessionId` 拼接。

**Rationale**: 用户已确认入参口径；幂等与 18 节的会话身份契约一致；一次性调用也留下会话与审计痕迹（可追溯），但不会复用历史。`UUID.randomUUID()` 只用于非 trace 的一次性身份，不违反 AGENTS §5.2 对 traceId 生成器的约束。

**Alternatives considered**: ① `userId` 必填——调用方要自己编用户标识，增加集成成本；② 无状态调用不落会话——`AgentService.process` 的 `sessionStore.save` 会写库，且审计需要 `session_id`，硬造 id 又违反 H4④；③ 用 `TraceIds` 生成一次性身份——语义混淆（trace-id 不是用户标识）。

## D-09 CORS 范围

**Decision**: 新增 `WebConfiguration implements WebMvcConfigurer`，只对 `/api/**` 配置 `allowedOrigins("*")`、`allowedMethods(GET, POST, DELETE, OPTIONS)`、`allowedHeaders("*")`、`allowCredentials(false)`。

**Rationale**: 技术方案 §7.4 明确核心阶段开放所有源方便调试；限定 `/api/**` 不把放宽策略扩散到 swagger / actuator 等路径。`allowCredentials(false)` 与通配来源兼容。

**Alternatives considered**: 全局 `CorsFilter`——覆盖过宽；不做 CORS——前端独立部署时无法联调。

## D-10 不添加 OpenAI 自动配置排除项

**Decision**: 不修改 `spring.autoconfigure.exclude`，不新增 `OpenAiAutoConfiguration` 排除项。

**Rationale**: 本地 `mvn dependency:tree` 显示当前只引入 `spring-ai-openai` raw jar 与 `spring-ai-alibaba-starter-dashscope`（后者由 `spring.ai.dashscope.enabled=false` 关闭），classpath 上没有 `spring-ai-openai-spring-boot-starter`，也没有 `OpenAiAutoConfiguration`。Spring Boot 3.x 对 `spring.autoconfigure.exclude` 中不存在于 classpath 的类会报非法排除并使启动失败，照抄课件片段会引入真实故障。虚拟线程已在既有 `application.yaml` 开启，端口也已是 8080，无需改动。

**Alternatives considered**: 照抄课件 YAML——会因排除不存在的类导致 `serve` 启动失败；引入 OpenAI starter 再排除——无必要地扩大依赖面。

## D-11 `WebSmokeIT` 的运行位置与 surefire 配置

**Decision**: `WebSmokeIT` 放 `sableos-boot/src/test/java/com/sableos/boot/`，使用 `@SpringBootTest(classes = SableOsRuntime.class)` + `@AutoConfigureMockMvc` + 临时 SQLite 与临时工作区。父 POM surefire 增加 `**/*IT.java` include。类不打 `@Tag("integration")`。

**Rationale**: 装配根在 `sableos-cli` 的 `SableOsRuntime`，`sableos-web` 内没有 `@SpringBootConfiguration`，`@SpringBootTest` 放在 web 模块找不到配置（008 节已踩过同类问题）。surefire 默认不匹配 `*IT`，不显式 include 的话这个测试永远不跑；它不依赖模型、不碰外网，适合作为常规门禁。`ProviderSmokeIT` 仍由 `excludedGroups=integration` 排除，不受影响。

**Alternatives considered**: ① 放在 web 模块并自定义 `classes`——web 不依赖 cli/storage，无法起真实 JPA；② 改名 `WebSmokeTest`——偏离课件测试类锚点；③ 打 `@Tag("integration")`——CI 默认跳过，失去发现“Found 0 repositories”类问题的价值。

## D-12 统一信封与异常映射的复用范围

**Decision**: 复用既有 `ApiResponse`（`code` / `message` / `data` / `timestamp` / `traceId`）与既有 `GlobalExceptionHandler`；不新建 `ErrorBody`、不新建 `SessionNotFoundException`（会话 / Agent 不存在统一用 `ResourceNotFoundException`）。保留既有 `IllegalArgumentException → 400`、`NoResourceFoundException → 404`、`Whitelist*` 映射。只新增 `ProviderFailureException → 503` 一条映射。

**Rationale**: 信封是 AGENTS §5.1 的活契约，新增第二套错误体必然漂移；`ResourceNotFoundException` 已精确表达 404 领域语义；把 `IllegalStateException` 整体映射 503 会把内部错误误分类（见 D-03）。

**Alternatives considered**: 照课件片段新建 `SessionNotFoundException` / 映射 `IllegalStateException`——重复概念且误伤 500。

## D-13 Provider 探测的可测性

**Decision**: `HttpProviderHealthProbe` 构造注入 `ProvidersProperties` 与一个 `java.net.http.HttpClient`（默认 2s connect）；测试用 JDK 内置 `com.sun.net.httpserver.HttpServer` 在随机端口模拟可达端点、用已关闭端口模拟不可达，不引入 MockWebServer。

**Rationale**: JDK 自带 HTTP 服务端，零新依赖；测试完全本地、确定性高，不碰外网。

**Alternatives considered**: MockWebServer——需要新增测试依赖；mock 掉 HttpClient——`HttpClient` 是抽象类，mock 成本高且测不到真实超时行为。
