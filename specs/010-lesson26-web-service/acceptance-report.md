# 验收报告：第 26 节 Web Service 与第一版管理平台（仅后端）

**Feature**: `specs/010-lesson26-web-service` | **Branch**: `010-lesson26-web-service` | **Date**: 2026-10-29

> 范围说明：按用户决定，本节**只交付后端**。管理平台前端、静态资源托管、SPA 回落与前端风格 skill 不在本次交付与验收范围内，由独立 agent 基于本节 10 个端点另行实现。

## 1. 全量门禁

命令：`mvn -B clean verify` → `BUILD SUCCESS`（01:59 min）

| 模块 | 单测结果 |
|---|---|
| sableos-core | 104 run, 0 fail, 0 err, 0 skip |
| sableos-provider | 24 run, 0 fail, 0 err, 0 skip |
| sableos-tool | 152 run, 0 fail, 0 err, 0 skip |
| sableos-storage | 38 run, 0 fail, 0 err, 0 skip |
| sableos-memory | 35 run, 0 fail, 0 err, 0 skip |
| sableos-channel-cli | 2 run, 0 fail, 0 err, 0 skip |
| sableos-web | 69 run, 0 fail, 0 err, 0 skip |
| sableos-cli | 1 run, 0 fail, 0 err, 0 skip |
| sableos-boot | 38 run, 0 fail, 0 err, 0 skip |

合计 **463** 个测试全绿；Spotless / Checkstyle / P3C(PMD) / SpotBugs(FindSecBugs) 全部通过（每个模块 `BugInstance size is 0`、`0 Checkstyle violations`），OWASP 按本地默认跳过（CI 独立 job 为权威）。

> 计数口径：按 `target/surefire-reports/TEST-*.xml` 的 `tests` 属性汇总（`.txt` 会把 `@Nested` 父类记成 0，不用）。

**实现期间修复的问题（未放宽任何断言）**：

1. **`applicationTaskExecutor` 不存在**：`TaskExecutionAutoConfiguration` 带 `@ConditionalOnMissingBean(Executor.class)`，容器已有 `agentTaskScheduler`（也是 `Executor`），Boot 不会创建该 bean，`WebSmokeIT` 启动期报 `NoSuchBeanDefinition`。改为 `ApiConfiguration` 显式声明仅服务 Agent 调用的虚拟线程执行器（复用唯一 `TraceTaskDecorator`），与调度线程池隔离。
2. **SpotBugs 11 条**：`PERMISSIVE_CORS`（技术方案 §7.4 明确要求，属有意放宽）与 6 个 Controller 的 `SPRING_ENDPOINT`（核心阶段无认证是既定前提，§7.5）。按项目先例做**类级定点抑制**并写明理由，没有全局关规则。
3. **Spotless 行尾**：新建文件被写为 CRLF，`validate` 阶段直接失败；`mvn spotless:apply` 统一为 LF 后恢复。

## 2. 课件 harness 映射

| 课件测试类 / 验收点 | 落地位置 | 方法（英文名，`@DisplayName` 保留课件原文） |
|---|---|---|
| `SessionApiControllerTest`·超 32KB → 400 | `sableos-web` | `oversizedMessageReturns400WithoutCallingAgent` |
| 同上·Session 不存在 → 404 | 同上 | `missingSessionReturns404OnSend` |
| 同上·`agentService.process` 恰被调一次 | 同上 | `normalSendCallsAgentExactlyOnce` |
| 补充·建会话三元组与幂等入口 | 同上 | `createUsesWebChannelAndAnonymousUser` |
| 补充·未注册 Agent → 404 / 缺 profile → 400 | 同上 | `createUnknownAgentReturns404` / `createWithoutProfileReturns400` |
| 补充·历史最多 100 条 | 同上 | `historyReturnsAtMostLast100Messages` |
| 补充·软归档成功 / 不存在 404 | 同上 | `archiveReturns200` / `archiveMissingSessionReturns404` |
| `GlobalExceptionHandlerTest`·每类异常映射 | `sableos-web`（既有类扩展） | 既有 15 例 + `providerFailureMaps503WithoutLeakingDetails` |
| `GlobalExceptionHandlerTest`·500 不泄漏 | 同上 | 既有 `unhandledMaps500WithoutLeakingDetails`（未改动） |
| `WebSmokeIT`·真实装配可达 | `sableos-boot` | `healthIsReachable` / `infoWithoutProvidersReturnsEmptyArray` / `profilesAreEmptyInCleanWorkspace` / `toolsAreRegistered` / `memoryWithoutWritesReturnsEmptyContent` / `openApiDocumentListsEndpoints` / `corsPreflightAllowsAnyOriginForApiPaths` |
| 补充·60 秒超时机制 | `sableos-web` | `AgentCallExecutorTest#timeoutThrowsAgentTimeout` / `providerFailureIsUnwrapped` / `successfulReplyIsReturned` |
| 补充·无状态调用 404 / 参数 / 一次性会话 | `sableos-web` | `AgentApiControllerTest` 4 例 |
| 补充·info 状态映射 | `sableos-web` | `SystemApiControllerTest` 3 例 |
| 补充·Provider 连通探测 | `sableos-provider` | `HttpProviderHealthProbeTest` 3 例（本地 HttpServer，不碰外网） |

## 3. “本节交付物”存在性核对（后端子集）

| 课件交付物 | 证据 |
|---|---|
| 六个 Controller | `sableos-web/src/main/java/com/sableos/web/api/{Session,Agent,Profile,Memory,Tool,System}ApiController.java` |
| `GlobalExceptionHandler` 扩展、复用 `ApiResponse` | 既有类新增 `ProviderFailureException → 503`；信封未改形状 |
| springdoc-openapi 集成 | 既有依赖 + `OpenApiConfig`；`WebSmokeIT#openApiDocumentListsEndpoints` 断言 `/v3/api-docs` 含会话与 invoke 路径 |
| `SessionApiControllerTest` | 9 例 |
| `GlobalExceptionHandlerTest` | 15 例（含新增 1 例） |
| `WebSmokeIT` | 7 例（放 boot，见 D-11） |
| 虚拟线程 / 8080 | 既有 `application.yaml`（`spring.threads.virtual.enabled=true`、`server.port=8080`）；未改动 |
| 32KB / 100 条 | `ApiConstraints` 单一来源；`SessionApiControllerTest` 覆盖 |
| 前端 / 管理台 / 风格 skill | **按用户决定不交付** |

## 4. 前序节回归

`sableos-core 104` / `provider 24` / `tool 152` / `storage 38` / `memory 35` / `channel-cli 2` / `cli 1` / `boot 38` 全部绿；`ArchitectureTest` 4 例绿（`web→tool` 未成环，core 未反向依赖）。

## 5. H4 与 AGENTS 硬约束自查

| 检查项 | 结论 |
|---|---|
| ① 涉外 IO 过 `Sandbox.enforce` | 本节不新增工具执行路径；Agent 工具仍由 24 节 Sandbox 约束。Provider 连通探测是服务自身对运营商配置端点的可达性检查，不是 Agent 工具动作，不适用工具白名单 |
| ② LLM / 工具审计 | 发消息与 invoke 均走 `AgentService.process`，`llm_calls` / `tool_invocations` 照常落库（含 `trace_id`） |
| ③ 无明文 key | grep `api-key=...` / `sk-...` 无命中；探测不读取、不记录 key |
| ④ `session_id` 单点拼接 | Controller 只传三元组；全仓 `channel+user+profile` 拼接仍在 `SessionManager.sessionId` 一处 |
| ⑤ 无 Reactor / `CompletableFuture` / 自建线程池 | 代码零 `CompletableFuture`、零 Reactor；执行器是 Spring `SimpleAsyncTaskExecutor`（每任务一个虚拟线程，非池），且未新增 `TaskDecorator` bean |
| ⑥ 无 Spring AI 自动工具执行 | 未新增 Spring AI 装配；`internalToolExecutionEnabled(false)` 未被触碰 |
| traceId 跨线程 | 执行器复用唯一 `TraceTaskDecorator`（`TraceContext.snapshot()` 语义） |
| traceId 生成 | 未新增生成器；一次性会话的 `UUID.randomUUID()` 只作 `userId`，不用于 traceId |
| `synchronized` 阻塞 | 新增代码零 `synchronized` |
| Provider 显式映射 | 探测按 `ProvidersProperties` 清单遍历，无类型扫描 |
| 日志 CRLF 净化 | 探测 `error` 已 `replaceAll("[\r\n]", "_")` 并截断；`GlobalExceptionHandler` 既有净化未破坏 |

## 6. 真实进程冒烟（`serve`）

用临时工作区启动 fat JAR（端口 18080）：

- `/api/v1/health` → `code:0, data.status:"ok"`，信封含 `traceId`
- `/api/v1/info` → `app:SableOS`、`version:0.1.0-SNAPSHOT`、`providers:[]`
- `/api/v1/profiles` → `data:[]`
- `/api/v1/tools` → 14 个已注册工具（含 `http_get` / `save_memory` / `read_file` 等）
- `/api/v1/memory` → `profile:"default", content:""`
- `/v3/api-docs` → `200`
- `OPTIONS /api/v1/sessions`（带 `Origin`）→ `200` + `Access-Control-Allow-Origin: *`
- 缺 `profile` 建会话 → `400`；未知 Agent invoke → `404`；不存在会话查询 / 归档 → `404`

进程已停止，未留下常驻服务。

## 7. 剩余人工项（harness 判不了的）

- 配真实 Provider key 后，用真模型走通 `POST /sessions/{id}/messages` 与 `POST /agents/{name}/invoke`，确认 `llm_calls` / `tool_invocations` 有对应记录。
- CLI 聊过的会话能被 `GET /api/v1/sessions/{id}` 查到（两个入口共享存储）。
- 断开 Provider 网络 → 503，响应不含连接串 / 密钥。
- 构造超过 60 秒的调用 → 504，期间 `/health` 仍可响应。
- 200 并发 invoke 压测，`/health` 全程可响应。
- `GET /api/v1/info` 在配置真实 provider 后，`reachable/latencyMs/error` 符合预期。
- Swagger UI 页面人工打开确认（本节已断言 `/v3/api-docs`）。

## 8. 与课件/计划的偏差（均为有意）

1. **不添加 `OpenAiAutoConfiguration` 排除项**：本地依赖树确认 classpath 无该类，Spring Boot 3.x 排除不存在的自动配置类会让 `serve` 启动失败。课件该“坑”在本仓库不复现。
2. **`WebSmokeIT` 放 `sableos-boot`**：装配根在 `sableos-cli` 的 `SableOsRuntime`，web 模块内没有 `@SpringBootConfiguration`。
3. **父 POM surefire 增加 `**/*IT.java`**：否则课件锚点 `WebSmokeIT` 永远不会执行；`ProviderSmokeIT` 仍由 `@Tag("integration")` 默认跳过。
4. **`SessionNotFoundException` 未新建**：复用既有 `ResourceNotFoundException` 表达 404，避免重复概念。
5. **未映射 `IllegalStateException → 503`**：课件片段会把真实内部错误误报为提供方故障；只映射真正的 `ProviderFailureException`。
6. **新增 `sableos-tool` 依赖与 `ProviderHealthProbe` 契约**：为工具清单与 `/info` 实时探测所需，详见 plan / research。
