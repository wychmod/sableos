# 验收报告：第 28 节 定时任务子系统与通知渠道注册表

**Feature**: `specs/012-lesson28-scheduler-subsystem` | **Branch**: `028-lesson28-scheduler-subsystem` | **Date**: 2026-10-31

> 范围说明：本节把 25 节的内存 cron 升级为可定义、能自己跑、记得住、可管理的子系统；并按用户 2026-10-31 的拍板，把 19 节后置的全局通知渠道注册表一并补齐。契约以《技术方案》§8.5 / §9.2 / §6.8 为准，课件与硬约束冲突处见第 7 节。

## 1. 全量门禁

命令：`mvn -B clean verify` → **BUILD SUCCESS**

| 模块 | 单测结果 |
| --- | --- |
| sableos-core | 108 run, 0 fail, 0 err, 0 skip |
| sableos-provider | 30 run, 0 fail, 0 err, 0 skip |
| sableos-tool | 156 run, 0 fail, 0 err, 0 skip |
| sableos-storage | 55 run, 0 fail, 0 err, 0 skip |
| sableos-memory | 35 run, 0 fail, 0 err, 0 skip |
| sableos-channel-cli | 2 run, 0 fail, 0 err, 0 skip |
| sableos-web | 99 run, 0 fail, 0 err, 0 skip |
| sableos-cli | 1 run, 0 fail, 0 err, 0 skip |
| sableos-boot | 44 run, 0 fail, 0 err, 0 skip |

合计 **530** 个测试全绿；Spotless / Checkstyle / P3C(PMD) / SpotBugs(FindSecBugs) 全部通过，OWASP 按本地默认跳过（CI 独立 job 为权威）。

> 计数口径：按 `target/surefire-reports/TEST-*.xml` 的 `tests` 属性汇总。`@Tag("integration")` 测试默认被 `excludedGroups` 排除，不计入上表。

## 2. 课件 harness 映射

| 课件验收点 | 测试类 / 方法 |
| --- | --- |
| 定时子系统：启动登记 → 立即执行 → 持久化 → 停用 | `ScheduledTaskE2ETest#fullFlow_registersRunsPersistsAndDisables`（mock、gate 内、真 HTTP + 临时 SQLite） |
| 定时链路：查天气 → notify → webhook 收到；会话复用；逐表对账 | `SchedulerFlowIT#scheduledFlow_pushesWeatherAndIsolatesWhitelistFailure`（`@Tag("integration")`、真 key、无 key 时 skipped） |
| 重启恢复：会话 / 记忆 / 定时状态与历史 / 审计不断档 | `RestartRecoveryIT#restart_restoresAllPersistedState`（`@Tag("integration")`、mock） |
| 多 Agent：工具 / 会话 / 定时三条隔离 | `MultiAgentIsolationTest#toolIsolation_availableToolsMatchDeclaredSet`、`#sessionIsolation_idsAreDistinct`、`#scheduleIsolation_aFailureDoesNotBlockB`（mock、gate 内） |
| 调度器注册 / 启用检查 / 失败隔离 / 立即执行 / 退役 | `AgentSchedulerTest`（13 例，含新增的 `runOnce_whenDisabled_skipsAndRecordsNothing`、`runNow_ignoresDisabledAndRecords`、`runNow_whenLockHeld_returnsEmpty`、`registerAll_retiresTaskMissingFromConfig`） |
| 任务存储：稳定标识 / 退役复活 / 历史与状态更新 | `JpaScheduledTaskStoreTest`（9 例，含 PRAGMA 列与 V6 迁移断言） |
| 通知渠道存储：CRUD / 顺序 / 创建时间保留 | `JpaNotifyChannelStoreTest`（5 例，含 PRAGMA 列与 V7 迁移断言） |
| notify 运行期解析 / 缺省渠道 / 零请求失败 | `NotifyToolsTest`（10 例，新增 4 例 store 构造器场景，保留既有 InOrder 顺序断言） |
| v2/v1 管理端点、409、404、400 | `ScheduleApiControllerTest`（14 例） |
| 通知渠道 CRUD、重名 409、不存在 404、非法 400 | `NotifyChannelApiControllerTest`（9 例） |

## 3. 本节交付物存在性核对

| 交付物 | 证据 |
| --- | --- |
| `scheduled_tasks` / `task_executions` 两表 | `sableos-storage/src/main/resources/db/migration/{sqlite,postgresql}/V6__scheduled_tasks.sql` |
| `notify_channels` 表 | `.../V7__notify_channels.sql` |
| `ScheduledTaskStore` + `ScheduledTaskView` / `TaskExecutionView` | `sableos-core/src/main/java/com/sableos/core/scheduler/` |
| `NotifyChannelStore` + `NotifyChannelView` | `sableos-core/src/main/java/com/sableos/core/notify/` |
| `JpaScheduledTaskStore` + 实体与仓库 | `sableos-storage/src/main/java/com/sableos/storage/scheduler/` |
| `JpaNotifyChannelStore` + 实体与仓库 | `sableos-storage/src/main/java/com/sableos/storage/notify/` |
| `AgentScheduler` 改造 | `sableos-core/src/main/java/com/sableos/core/scheduler/AgentScheduler.java`（`registerAll` / `runOnce` / `runNow` / `execute` / `retireMissing`） |
| v2 四端点 + Agent+key 立即执行 | `sableos-web/.../ScheduleApiController.java`、`ScheduleAgentRunController.java` |
| v1 兼容层 | `sableos-web/.../LegacyScheduleApiController.java` |
| 通知渠道 CRUD | `sableos-web/.../NotifyChannelApiController.java` + DTO |
| `NotifyTools` 运行期解析 | `sableos-tool/.../NotifyTools.java`；`ToolConfiguration` 注入 `NotifyChannelStore` |
| 装配 | `SableOsRuntime` 的 `scheduledTaskStore` / `notifyChannelStore` Bean；`SchedulerConfiguration` 注入 store |
| 管理台"定时任务"页 | `sableos-web/src/main/frontend/src/pages/SchedulesPage.vue`；路由 `/schedules`；导航项；`static/admin/**` 已重建 |
| README / AGENTS | `README.md` 端点表与使用示例；`AGENTS.md` §10；`sableos-web/src/main/frontend/README.md` |

## 4. 前序节回归

`mvn clean verify` 全模块通过，含 16~27 节既有用例；`ArchitectureTest` 的模块依赖方向、唯一 `TaskDecorator`、9 模块导入守卫均绿；`MockAgentE2ETest` / `MockProviderFlowTest` 仍绿。

## 5. H4 全局不变量与 AGENTS §5.2 自查

| 条目 | 结论 | 证据 |
| --- | --- | --- |
| 涉外 IO 首行过 `Sandbox.enforce` | ✅ | `NotifyTools.execute` 仍先 `enforce(HTTP_REQUEST, url)` 再 `adapter.send`；`NotifyToolsTest#enforcePrecedesSend` 保持 InOrder 断言 |
| LLM / 工具审计不建旁路 | ✅ | 仍由既有 `llm_calls` / `tool_invocations` 路径写；新增 `task_executions` 只记任务执行层 |
| 无明文 key | ✅ | `application.yaml` 凭证仍为 `${ENV_VAR:}`；新增测试中的 `test-key` 只在测试代码 |
| `session_id` 只在 core 单点拼接 | ✅ | 生产代码无 `"scheduler:scheduler:"` 字面拼接；调度器调 `SessionManager.sessionId(...)`；测试字面量仅用于断言 |
| 无 Reactor / `CompletableFuture` / 自建线程池 | ✅ | 新增代码无；`TracedExecutors`/`AgentCallExecutor` 的既有注释与实现未改 |
| 无 Spring AI 自动工具执行 | ✅ | 未触碰 `ReActLoop` / `ToolExecutor`；notify 仍是 `SableTool` |
| traceId 显式传播 | ✅ | 定时触发继续 `TracedExecutors.wrapRoot`；`runNow` 复用 Web 入口 trace；未新增 `TaskDecorator`；定时执行在子线程内用 `TracedExecutors.wrapCallable` 显式传播（虚拟线程不继承 MDC） |
| `TraceIds` 而非 `UUID.randomUUID()` | ✅ | 未改 trace 生成 |
| `synchronized` 内无阻塞 | ✅ | 新增代码零 `synchronized`；仍用 `ReentrantLock` |
| Provider 显式映射 | ✅ | 本节无 Provider 改动 |
| 日志 CRLF 净化 | ✅ | `AgentScheduler` 的 `safe` / `readableError`；`ReActLoop` / `ToolExecutor` 新增日志同样走 `safe`；`JpaNotifyChannelStore` 只记固定文案 |

## 6. 集成测试实测

- `RestartRecoveryIT`：**通过（1/1）**。同一工作区先后启动两个上下文，第二个读回会话、记忆、任务 `runCount/lastStatus/nextRunAt`、执行历史与 `llm_calls`（4 条）。
- `SchedulerFlowIT`：**跳过（1 skipped）**——本机未设置 `DEEPSEEK_API_KEY`；`assumeTrue` 生效，不算失败。有 key 时手动命令见 `quickstart.md`。
- 门禁内 `ScheduledTaskE2ETest`（1/1）与 `MultiAgentIsolationTest`（3/3）通过。

## 7. 课件偏差与拍板记录

| 课件 | 本仓库落地 | 依据 |
| --- | --- | --- |
| `schema.sql` 加两表 | Flyway 双轨 `V6` / `V7` | AGENTS §5.1 #6：表结构唯一权威是 Flyway |
| `task_id` 主键、`runNow(taskId)` | `schedule_id` 稳定主键；`runNow(scheduleId)`；v1 按 key 解析 | 技术方案 §8.5 / §9.2 |
| v1 四端点 | v2 四端点 + Agent+key 立即执行；v1 按 key 兼容、撞 key 409 | 技术方案 §8.5 |
| 通知渠道注册表未在课件交付物中 | 并入本节（表 + core 契约 + JPA + CRUD + NotifyTools 改造） | 用户 2026-10-31 明确选择 A |
| `display_name` 无来源 | 核心阶段取任务键 | 技术方案未指定来源；避免扩 Profile frontmatter |

## 7. 稳定性（课件 §2.4）补齐记录

初版落地时这一根支柱只做了「外部 MCP 挂了不拖垮启动」与「错误信息可读」，超时与日志主线在本轮补齐（2026-10-31 复核发现）：

| 课件要求 | 落地 | 证据 |
| --- | --- | --- |
| 模型调用 / 工具执行 / notify 推送都要有超时 | 共享 `RestClient` 显式配 connect 10s / read 30s，`http_get` / `http_post` / `notify` / web search 同一实例全覆盖 | `ToolConfiguration#restClient` |
| 一次 Agent 调用整体 60 秒封顶 | 定时路径与 Web 入口同口径：新增 `AgentScheduler.CALL_TIMEOUT`，执行体提交到 `agentCallTaskExecutor` 后用 `Future#get` 截断，超时取消并写 `success=false` + 可读原因；任务锁仍在 `finally` 释放 | `AgentScheduler#processWithTimeout`；`AgentSchedulerTest#runNow_whenCallExceedsTimeout_recordsFailureAndKeepsSchedulerUsable` |
| 错误信息要人看得懂 | 超时原因形如「定时任务执行超过 60000 毫秒被中止」，沙箱拒绝沿用「域名不在白名单内: x」 | `AgentScheduler#readableError` |
| 一次触发在日志里是一条清晰主线 | `ReActLoop` 每轮打一条（轮次 + 工具数 / 收尾）、`ToolExecutor` 每次调用打一条（工具名 + 成败 + 耗时）；会话 id 由 MDC 投影，dev 与 prod 都能按 sessionId 串起 | `ReActLoop#run`、`ToolExecutor#logOutcome` |
| 外部 MCP 挂了不能拖垮自己 | 既有实现：连接 / `listTools` 的 `RuntimeException` 只 WARN 并跳过该 server | `McpClientService#registerServer`、`McpClientServiceTest` |

## 8. 剩余人工项

- **真 key 链路**：设置 `DEEPSEEK_API_KEY` 后手动跑 `SchedulerFlowIT`，确认天气 → notify → webhook、逐表对账与白名单失败隔离。
- **进程级 kill + serve**：按 `quickstart.md` 第 7 节手工验证；IT 内用"关闭上下文再启动"替代，能证明状态不在第一个上下文内存里，但不等价于 `kill -9`。
- **管理台浏览器验收**：`/admin/schedules` 的列表、立即执行、启停、历史展开需人工看一次；门禁只覆盖 `vue-tsc` 类型检查与 Vite 构建。
- **Demo 前置**：31 节 Demo 前把天气源 / webhook / 新闻源域名加入 `http.allowed_domains`，并通过 `/api/v1/notify-channels` 登记真实 webhook；通知渠道管理台页面不在本节范围。
- **外部 MCP 不可用**：未在本节新增自动化用例；quickstart 已列为人工项。
- **超时端到端**：门禁用「短超时 + 阻塞桩」证明截断与记账路径；真实 60 秒到点的端到端未跑（要真等一分钟），属人工项。

## 9. 结论

本节门禁全绿，US1~US5 均有可独立执行的 harness；`SchedulerFlowIT` 仅因本机无 key 跳过。课件 §2.4 的四项稳定性要求（超时、可读错误、日志主线、MCP 容错）均已落地并有对应证据，未发现需要放宽断言或禁用测试的情况。
