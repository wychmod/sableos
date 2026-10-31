# 验收报告：第 29 节 插件化 Agent——一个目录定义一个会自己跑的 Agent

**Feature**: `specs/013-lesson29-pluggable-agent` | **Branch**: `029-lesson29-pluggable-agent` | **Date**: 2026-10-31

> 范围说明：本节不新建能力模块，而是补上"定义一个 Agent、把它装上去、让它跑起来"的机制。改造仅落在 `AgentLoader` / `ProfileRegistry` / `AgentScheduler` 三个既有类与 `AgentConfiguration` 一处装配；`AgentService` / `ReActLoop` / `PromptBuilder` / `ToolExecutor` / `ContextLoader` 零改动。

## 1. 全量门禁

命令：`mvn -B clean verify` → **BUILD SUCCESS**（10 个模块全绿，**559 例**，0 fail / 0 error / 0 skip）

> **本机环境说明（非本节引入）**：本机 JDK 21 对 Byte Buddy 的 inline mock maker 存在动态 attach 限制，全量构建时部分模块的 Mockito 用例会随机报 `Could not initialize plugin: MockMaker`。已用 **`main` 基线 worktree 复现同样故障**（core 模块 30 个 error），确认与本改动无关。正式门禁与 CI（ubuntu / macOS / windows 三平台）不受影响；本机需显式挂 agent 才能稳定取数：
>
> ```bash
> mvn -B clean verify -DargLine="-XX:+EnableDynamicAgentLoading -javaagent:<path>/byte-buddy-agent-1.17.8.jar"
> ```

**本节新增用例**：

| 测试类 | 用例数 |
| --- | --- |
| `DeriveProfileTest` | 4 |
| `AgentScanRegisterTest` | 5（新增类） |
| `ProfileRegistryRuntimeTest` | 5（新增类） |
| `AgentSchedulerRegisterTest` | 5（新增类） |
| `ProgressiveDisclosureTest` | 5（新增类） |
| `AgentLoaderTest` | 10（原 7 + 新增 3：未知工具告警、全部已注册无告警、四类非法点名日志） |

Spotless / Checkstyle / P3C(PMD) / SpotBugs(FindSecBugs) 全部通过；OWASP 按本地默认跳过（CI 独立 job 为权威）。

## 2. 课件 harness 映射

| 课件验收点 | 测试类 / 方法 |
| --- | --- |
| 正确拆出 frontmatter 与正文；认出 `scripts/`/`skills/`/`REFERENCE.md`；缺必填报错点名 | `AgentLoaderTest`（既有）+ `ProgressiveDisclosureTest#systemPrompt_containsBodyButNotReferencesOrScripts` |
| frontmatter 各字段正确映射；`schedules` 原样带进派生 Profile | `DeriveProfileTest#deriveProfile_mapsEveryDeclaredField`、`#deriveProfile_carriesSchedulesVerbatim` |
| 扫 N 个目录得 N 个 Agent；带 `schedules` 的进调度器 | `AgentScanRegisterTest#scanNDirs_yieldsExactlyNAgents`、`#scan_mixedLegalAndIllegal_registersExactlyLegalOnes` |
| `register()` 后立即 `get()` 可见；非法配置报错与启动路径完全一致 | `ProfileRegistryRuntimeTest#register_thenGetSeesItImmediately`、`#invalidConfig_runtimeAndStartupThrowIdentical` |
| `registerProfile` 后 `scheduledTasks` 有句柄；cron/时区来自 `Profile.schedules` | `AgentSchedulerRegisterTest#registerProfile_exposesHandlePerSchedule`、`#registerProfile_usesCronAndZoneFromProfile` |
| 正文进 system prompt；参考/脚本不预载；改正文即时生效 | `ProgressiveDisclosureTest#systemPrompt_containsBodyButNotReferencesOrScripts`、`#systemPrompt_bodyEditTakesEffectImmediately`、`#sampleAgent_followsProgressiveDisclosure` |
| 重复登记取消旧句柄 | `AgentSchedulerRegisterTest#registerProfile_twice_cancelsPreviousHandle` |
| `tools` 引用未注册能力 → 加载告警 | `AgentLoaderTest#loadAll_unknownTool_warnsButStillRegisters` |

## 3. 本节交付物存在性核对

| 交付物 | 证据 |
| --- | --- |
| `AgentLoader` 解析目录 | `sableos-core/src/main/java/com/sableos/core/agent/AgentLoader.java`（三参构造器 + `deriveProfile(Path)` + `warnUnknownTools`） |
| `deriveProfile(agentDir) → Profile` | 同上 `deriveProfile(Path)` |
| 启动扫描并注册（装配层） | `sableos-boot/src/main/java/com/sableos/boot/agent/AgentConfiguration.java`（注入 `ToolRegistry` 工具名单） |
| `ProfileRegistry.register/remove/exists` | `sableos-core/src/main/java/com/sableos/core/profile/ProfileRegistry.java`（`remove` 为新增） |
| `AgentScheduler.registerProfile` + `scheduledTasks` 句柄表 | `sableos-core/src/main/java/com/sableos/core/scheduler/AgentScheduler.java`（`registerProfile` / `scheduledTasks` / `hasScheduledTask`） |
| `ContextLoader` 注入 Agent 正文 | **前序节已交付**（本次复核确认满足要求，未改动，见第 7 节） |
| 示例 Agent（三件夹具 + 公共 Skill 根） | `sableos-core/src/test/resources/agents/daily-reconcile/`（`AGENT.md` / `REFERENCE.md` / `scripts/reconcile.py`）；子指令规范在 `sableos-core/src/test/resources/skills/report-format/SKILL.md`。`ProgressiveDisclosureTest#sampleAgent_followsProgressiveDisclosure` 复制夹具后建 `skills/report-format` 软连接，断言只注入元数据、正文不预载 |
| 缺 `name`/`provider` 报错点名 | `AgentLoaderTest#loadAll_eachIllegalDirLeavesNamedError` |
| `tools` 未注册告警 | `AgentLoaderTest#loadAll_unknownTool_warnsButStillRegisters` |

## 4. 前序节回归

`mvn clean verify` 全模块通过；`ArchitectureTest` 的模块依赖方向（core 不依赖 tool）、唯一 `TaskDecorator`、9 模块导入守卫均绿；`MockAgentE2ETest` / `MockProviderFlowTest` / `MultiAgentIsolationTest` / `ScheduledTaskE2ETest` 仍绿。

## 5. H4 全局不变量与 AGENTS §5.1/5.2 自查

| 条目 | 结论 | 证据 |
| --- | --- | --- |
| 涉外 IO 首行过 `Sandbox.enforce` | ✅ | 本节零新增涉外 IO；脚本仍走既有 `shell` + 白名单 |
| LLM / 工具审计不建旁路 | ✅ | 未触碰审计路径 |
| 无明文 key | ✅ | 示例 Agent 的 provider 不含 key（走环境变量） |
| `session_id` 只在 core 单点拼接 | ✅ | 本节不涉及；`AgentScheduler` 仍调 `SessionManager.sessionId(...)` |
| 无 Reactor / `CompletableFuture` / 自建线程池 | ✅ | 新增代码零 |
| 无 Spring AI 自动工具执行 | ✅ | 未触碰 `ReActLoop` / `ToolExecutor` |
| traceId 跨线程显式传播 | ✅ | `registerProfile` 仍用 `TracedExecutors.wrapRoot(..., CHANNEL_SCHEDULER)` |
| 唯一 `TaskDecorator` | ✅ | 未新增 |
| `TraceIds` 而非 `UUID.randomUUID()` | ✅ | 未改 trace 生成 |
| `synchronized` 内无阻塞 | ✅ | 新增句柄表用 `ConcurrentHashMap`，零 `synchronized` |
| Provider 显式映射 | ✅ | 本节无 Provider 改动 |
| 日志 CRLF 净化 | ✅ | 新增告警沿用既有 `safe(...)` 的 `replaceAll("[\r\n]", "_")` 形态 |
| core 不反向依赖 tool 模块 | ✅ | `AgentLoader` 只收 `Set<String>`；`ArchitectureTest` 绿 |

### 5.1 静态门禁妥协点：`EI_EXPOSE_REP2` 连锁（5 处定点抑制）

`ProfileRegistry` 从"启动期只读"变为支持运行期 `remove`（课件明确要求的改造），SpotBugs 据此把它判为**可变类型**，于是所有"构造器持有它"的类都开始报 `EI_EXPOSE_REP2`。用 `git stash` 二分确认：唯一触发源就是 `remove` 那一行。

处理方式按 AGENTS §5.3 的既有先例——**方法级定点抑制 + 写明理由与撤销条件**，不引入抽象层、不改依赖方向：

| 类 | 位置 |
| --- | --- |
| `AgentService` | 构造器 |
| `AgentApiController` | 构造器 |
| `MemoryApiController` | 构造器 |
| `ProfileApiController` | 构造器 |
| `SessionApiController` | 构造器 |

每条 `justification` 都写明：注册表是注入的协作者、本类不暴露它也不返回其内部集合、共享同一份注册表正是本节的设计要求；撤销条件是新增 getter 或 public 字段。`AgentScheduler` 同样持有注册表，但它的构造器已有覆盖七个参数的同款抑制，无需再加。

## 6. 自动化实测

- 本节 6 个 harness 全部随 `mvn clean verify` 在默认门禁内跑通（无 key、无外网）。
- 示例 Agent 的渐进式披露由 `ProgressiveDisclosureTest#sampleAgent_followsProgressiveDisclosure` 实测：正文进 prompt，`REFERENCE.md` 与 `scripts/reconcile.py` 的独有内容不进，软连接的 `report-format` 只进名称/描述/路径、正文不进。

## 7. 课件偏差与裁定记录

| 课件 | 本仓库落地 | 依据 |
| --- | --- | --- |
| 示例 `AGENT.md` 含 `notify_channels` 段 | **不含**；正文按渠道名（`ops-webhook`）引用全局注册表 | 技术方案 §6.8/§11.1 + AGENTS §6「通知渠道按全局注册名引用」；参照仓库同样裁定（「AGENT.md 干脆不要 notify_channels 字段」） |
| `skills/` 下放普通 `report-format.md` | 改为指向公共 Skill 根的**软连接**形态 | **实测**：普通 `.md` 会让 `ContextLoader` 抛「Skill 绑定越界」，整个 Agent 跑不起来；技术方案 §11.1 明定软连接是唯一绑定真相源 |
| `skills/` 位于 `daily-reconcile/` 内 | 示例**不含** `skills/` 目录：测试复制夹具后在临时根建 `skills/report-format` 软连接并断言只注入元数据；真实工作区按 quickstart §4 手工建链 | `core.symlinks=false` 时入库软连接会被还原成普通文件，反过来触发上一条的越界异常；git 也存不了空目录 |
| 示例目录落在运行期 `.sableos/agents/` | `sableos-core/src/test/resources/agents/daily-reconcile/` | `.sableos/` 已 gitignore；参照仓库亦不跟踪示例 Agent 目录；一份两用（夹具 + 手动范本） |
| 句柄表 `Map<String, ScheduledFuture<?>>` | `ConcurrentMap<Long, ScheduledFuture<?>>` | 本仓库 `schedule_id` 是自增 `long`（第 28 节定的），改 `String` 要与既有 `registeredTasks`、任务锁键不一致 |
| `ContextLoader` 列为交付物 | **不改**，复核确认已满足 | 实测：正文进 prompt、参考/脚本不进、每次现读；17 节 `ContextLoaderTest` 已钉死无缓存回归 |
| 脚本经通用 `shell` 跑 | 同（不新增 `python` 工具） | 技术方案 §12.3 明确否决专用脚本工具；解释器默认不入白名单 |

## 8. 剩余人工项

- **真模型定时链路**：需配 `DEEPSEEK_API_KEY`、把天气源/webhook 域名加入 `http.allowed_domains`、登记 `ops-webhook` 渠道，然后 `POST /api/v2/schedules/{id}/run` 核对 `task_executions` 与推送。见 `quickstart.md` §9。
- **手动丢目录验证**：按 `quickstart.md` §4~§7，在真实工作区放 `daily-reconcile/` 并手工建 `skills/report-format` 软连接（`mklink /D` 或 `ln -s`）。
- **脚本白名单**：需在部署配置里显式把解释器加入 `shell.allowed_commands`，并理解这等价于授予模型当前系统用户的代码执行权限（无文件/网络隔离）。quickstart §6 已写明该信任边界。
- **注销联动（留给第 30 节）**：`ProfileRegistry.remove` 只改注册表，不取消调度句柄；`AgentScheduler` 本节按契约不提供 `unregisterProfile`。第 30 节做注销入口时必须把两者串起来，否则被移除 Agent 的旧句柄仍会按旧 `Profile` 触发。

## 9. 结论

本节门禁全绿，US1~US4 各有独立可执行的 harness；改造范围严格限定在课件点名的三个类与一处装配，底座主链路零改动。课件 §1.3 的 `notify_channels` 与 `skills/` 普通文件两处写法与技术方案冲突，已按权威顺序裁定并留证（第 7 节）；其中 `skills/` 一条经 JDK 21 实测确认后果，非推测。
