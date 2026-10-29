# 验收报告：第 25 节 定时任务调度

**Feature**: `specs/009-lesson25-scheduler` | **Branch**: `009-lesson25-scheduler` | **Date**: 2026-10-29

## 1. 全量门禁

命令：`mvn -B clean verify` → `BUILD SUCCESS`（01:43 min）

| 模块 | 单测结果 |
|---|---|
| sableos-core | 102 run, 0 fail, 0 err |
| sableos-provider | 21 run, 0 fail, 0 err |
| sableos-tool | 110 run, 0 fail, 0 err |
| sableos-storage | 38 run, 0 fail, 0 err |
| sableos-memory | 28 run, 0 fail, 0 err |
| sableos-channel-cli | 2 run, 0 fail, 0 err |
| sableos-web | 49 run, 0 fail, 0 err |
| sableos-cli | 无测试源（仅编译 + 门禁） |
| sableos-boot | 31 run, 0 fail, 0 err |

合计 **381** 个测试全绿；Spotless / Checkstyle / P3C(PMD) / SpotBugs(FindSecBugs) 全部通过，OWASP 按本地默认跳过（CI 独立 job 为权威）。

**过程中修复的两处红（未放宽任何断言）**：

1. 时区断言最初用 `0 9 * * * *`（= 每小时第 9 分），与「每天 9 点」的意图不符——改正为 `0 0 9 * * *`，断言保持原强度。
2. 「锁被占即跳过」用例：`ReentrantLock` 对同线程可重入，测试线程自己 `lock()` 后 `tryLock()` 仍成功。改为由另一线程持锁（CountDownLatch 同步），才等价于课件里「上一次还在跑」的场景。
3. SpotBugs `CRLF_INJECTION_LOGS`：首版净化写成 `"[\\r\\n]"`，功能正确（`javac` 实测两种写法都能把 CR/LF 替换为 `_`），但 findsecbugs **只识别单反斜杠字面形态**，于是全量告警。改正为与 `AgentLoader` 一致的 `"[\r\n]"`；仅剩「异常堆栈参数」一条，按项目先例做**方法级定点抑制**并写明撤销条件（堆栈只用于渲染、不参与 `{}` 插值，message 已净化后另列）。

## 2. 课件 harness 映射

`AgentSchedulerTest`（7 个用例，方法名英文、`@DisplayName` 保留课件原文）：

| 课件验收点 | 测试方法 |
|---|---|
| 注册时 `CronTrigger` 带上配置的 cron **和时区** | `registerAll_registersEveryScheduleWithCronAndZone`（用 `nextExecution` 行为断言时区，同 cron 在 UTC 下差 8 小时） |
| 锁被占时本次触发直接跳过、不排队 | `runOnce_whenPreviousStillRunning_skipsThisTrigger` |
| `runOnce` 内部抛异常：不外抛、锁在 finally 释放 | `runOnce_whenProcessThrows_doesNotRethrowAndReleasesLock`（含「二进宫」断言） |
| 会话三元组固定 `("scheduler","scheduler",profileName)`，两次触发同一 Session | `runOnce_usesSchedulerIdentityAndReusesSession` |
| 补充：每次触发开新根链路（FR-009 / H4 traceId） | `registeredTask_opensFreshTracePerTrigger` |
| 补充：非法配置只跳过该条（FR-010） | `registerAll_skipsOnlyInvalidSchedule` |
| 补充：同一 Agent 的不同任务并发时串行访问共享会话（FR-011） | `runOnce_differentSchedulesOfSameAgent_serializeSessionAccess` |

时区断言说明：Spring 6.2.19 的 `CronTrigger` 不暴露 `getTimeZone()`，且 `equals()` 只比较表达式（`javap` 已核），故以固定 `lastActualExecution` 求 `nextExecution` 的行为断言钉死时区。

**评审修复 M-01（会话丢失）**：任务锁按 `schedule.id()` 划分，而会话是 `scheduler:scheduler:<profile>`；同一 Agent 两条 schedules 并发时，`JpaSessionManager.getOrCreate` 各返回独立 `Session` 快照，`save` 整列覆盖 `messages_json`，后写者抹掉前者历史。修复为在任务锁内再取**会话锁**（键由 `SessionManager.sessionId(...)` 唯一公式生成，阻塞获取、不丢触发），两把锁固定「先任务后会话」顺序。回归用例的有效性已实证：临时去掉会话锁后该用例转红（AssertionFailedError），恢复后转绿。

## 3. 本节交付物存在性

| 交付物 | 证据 |
|---|---|
| `AgentScheduler.registerAll` | `AgentScheduler.java:82` |
| `AgentScheduler.runOnce` | `AgentScheduler.java:134`（包级可见） |
| 按任务 id 的 `ReentrantLock` 表 | `AgentScheduler.java:164` `lockFor` + `ConcurrentMap` 字段 |
| 会话维度串行锁（M-01） | `AgentScheduler.java:174` `sessionLockFor`（键 = `SessionManager.sessionId(...)`） |
| `ScheduleConfig`（id/cron/zone/message） | `Profile.java:51`（16 节已落地，本节消费） |
| Profile `schedules` 字段 | 既有 `Profile.java:30` + `AgentLoader.parseSchedules` + `AgentLoaderTest` 既有断言 |
| `AgentSchedulerTest` | 7 个 `@Test` |
| 会话身份固定 `scheduler/scheduler/profileName` | `runOnce` 调 `getOrCreate(CHANNEL_SCHEDULER, SCHEDULER_USER_ID, profile.name())`；测试以字面量 `"scheduler","scheduler",profileName` 断言 |
| 失败只记日志不崩调度器 | `catch (Exception)` + `LOG.error` + `finally unlock` |

未新增：数据表 / 迁移脚本 / REST 路径 / 配置键 / Profile 字段 / 模块。

## 4. 前序节回归

`mvn clean verify` 全模块通过，含 007/008 的沙箱白名单全量用例、18 节 CLI、17 节 ReAct、16 节 Provider 等；`ObservabilityConfigTest` 的「全容器唯一 TaskDecorator」断言仍绿。

## 5. H4 全局不变量自查

| 项 | 结论 | 说明 |
|---|---|---|
| ① 涉外 IO 过 `Sandbox.enforce` | ✅ N/A | 调度器不做直接 IO；工具执行仍走既有白名单 |
| ② LLM/工具审计落库 | ✅ | 经 `AgentService.process` 继承 `llm_calls` / `tool_invocations`，本期无新增记账路径 |
| ③ 无明文 key | ✅ | grep 无命中 |
| ④ `session_id` 单点拼接 | ✅ | 只调 `SessionManager.getOrCreate`，调度器内无 `":"` 拼接 |
| ⑤ 无 Reactor / `CompletableFuture` / 自建线程池 | ✅ | 主代码无；`ThreadPoolTaskScheduler` 是技术方案 §8.5 点名的 Spring 调度器（bean 装配，非手写池） |
| ⑥ 无 Spring AI 自动工具执行 | ✅ | 本节不涉 Spring AI |
| traceId 跨线程 | ✅ | 注册时 `TracedExecutors.wrapRoot(...)`，每次触发新根链路；跳过分支同链 |
| 唯一 `TaskDecorator` | ✅ | 未新增；`ObservabilityConfigTest` 通过 |
| `TraceIds` 而非 `UUID` | ✅ | 未生成 traceId |
| `synchronized` 内阻塞 | ✅ | 零 `synchronized`；任务锁用 `ReentrantLock`（锁内确实跑 ReAct，正是该锁的用途） |
| Provider 显式映射 | ✅ | 未触碰 |
| 用户可控串入日志前净化 | ✅ | `safe()` → `replaceAll("[\r\n]", "_")`（与 `AgentLoader` 同形态） |

## 6. 剩余人工项（harness 判不了）

1. 真实到点触发一次：`schedules` 设每分钟，`serve` 启动后观察 Agent 自动发起对话，`llm_calls` / `tool_invocations` 有账（`session_id = scheduler:scheduler:<agent>`）。
2. 改 cron 表达式不重新编译、重启后按新时间触发。
3. 端到端预演：到点 → ReAct → 审计，为 31 节两个定时 Demo 打地基。
4. 重叠跳过、失败隔离、锁释放、时区、会话身份已由 `AgentSchedulerTest` 覆盖。

## 7. 备注：两处净化写法并存

`DefaultSandboxWhitelistAdmin` 的 `sanitize` 使用 `"[\\r\\n]"`，与本节/`AgentLoader` 的 `"[\r\n]"` 写法不同。`javac` 实测两者**功能等价**（都把 CR/LF 替换为 `_`），差异只在静态检查能否识别——前者需显式 `@SuppressFBWarnings`（该类已有）。属 008 既有实现，本节不改动。
