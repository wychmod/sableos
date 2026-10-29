# Implementation Plan: 定时任务调度——第三种触发源

**Branch**: `009-lesson25-scheduler` | **Date**: 2026-10-29 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/009-lesson25-scheduler/spec.md`

## Summary

在 `sableos-core` 新增 `AgentScheduler`：启动时遍历 `ProfileRegistry` 中所有 Profile 的 `schedules`，把每条配置（id / cron / zone / message）经 `CronTrigger` 动态注册到 Spring `TaskScheduler`；到点触发时按 `(Agent 名, 任务 id)` 取 `ReentrantLock` 做 `tryLock`，拿不到即跳过（不排队），拿到后再按会话取第二把锁（阻塞）串行化同一 Agent 多条 schedules 对共享会话的读写，随后以固定三元组 `("scheduler","scheduler",profileName)` 取会话、调用 `AgentService.process`，异常只记日志、`finally` 依次释锁。每次触发用 `TracedExecutors.wrapRoot` 开新根链路，审计沿用既有 `llm_calls` / `tool_invocations` 路径。装配在 `sableos-boot` 新增 `SchedulerConfiguration`；`serve` / `gateway` 启动后显式调用 `registerAll()`，`chat` 不调度。新增 `AgentSchedulerTest` 覆盖课件的四类验收点（另加注册期非法配置隔离、每次触发新链路、同 Agent 不同任务串行访问会话三条）。

## Technical Context

**Language/Version**: Java 21（父 POM enforcer 钉死 JDK 21 / Maven）

**Primary Dependencies**: Spring Boot 3.5.16 / Spring Framework 6.2.19。`sableos-core` **新增 compile 作用域的 `org.springframework:spring-context`**（版本由 Boot BOM 管理，不写 `<version>`）——`TaskScheduler`、`CronTrigger`、`ZoneId` 解析所需；`ThreadPoolTaskScheduler` 实例在 `sableos-boot` 装配。不新增第三方调度框架、不新增模块。测试沿用父 POM 的 `spring-boot-starter-test`（JUnit 5 / Mockito 5 / AssertJ）。

**Storage**: N/A。不新增表、不改迁移；`scheduled_tasks` / `task_executions` 归第 28 节（§8.5 / §9.2）。

**Testing**: `sableos-core/src/test/.../scheduler/AgentSchedulerTest`（Mockito mock `TaskScheduler` / `AgentService` / `SessionManager`）。覆盖：①注册参数（cron 表达式 + 时区行为断言）；②锁被占时跳过；③异常不外抛且再触发能进入；④会话三元组与同一会话复用；⑤非法配置只跳过该条。无新增 `@Tag("integration")`；真实到点触发列入 quickstart 人工项。

**Target Platform**: JVM 单二进制（`sableos-boot` fat JAR / CLI）

**Project Type**: Maven 多模块后端单体

**Performance Goals**: 无用户可感延迟目标。触发为事件驱动，注册期只做内存遍历；单次触发开销即一次 `AgentService.process`。调度线程池 ≥ 2，保证不同任务互不阻塞。

**Constraints**: 不新增数据表 / REST 接口 / 配置键 / Profile 字段（`schedules` 已由 16 节落地并有单测）；不引入分布式锁、不做失败重试；不新增 `TaskDecorator` bean；`session_id` 只由 `SessionManager` 拼接；`synchronized` 零使用（用 `ReentrantLock`）；日志中的用户可控串先做 CRLF 净化。

**Scale/Scope**: 生产代码新增 1 个类（`AgentScheduler`）+ 1 个装配配置类（`SchedulerConfiguration`）；修改 `sableos-core/pom.xml`、`ServeCommand`、`GatewayCommand`；测试新增 1 个类；无前端、无存储变更。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据 |
|---|---|---|
| I. JDK 21 + Spring Boot 3.x 单体 | ✅ | 只引入 Boot BOM 管理的 `spring-context`，不引入新运行时；不新增模块 |
| II. 自实现 ReAct 循环 | ✅ | 触发后直接调用 `AgentService.process`，不触碰 `ReActLoop` / `ToolExecutor` / `PromptBuilder` |
| III. Spring AI 只用一半 | ✅ | 本节不出现 Spring AI 类型；无自动工具执行路径 |
| IV. Provider 显式映射 | ✅ | 本节无 Provider 改动 |
| V. Tool 单模块 | ✅ | 不新增模块、不拆 Tool |
| VI. 审计 day one 落库 | ✅ | 复用 `AgentService` 内既有审计路径；不为定时触发新增记账逻辑（FR-008） |
| VII. Sandbox 接口先行 | ✅ | 本节不涉外部 IO；工具执行仍经既有 `sandbox.enforce` |
| VIII. 范围收敛 | ✅ | 只做单实例进程内调度；分布式协调 / 重试 / 任务状态落库 / 运行期增删全部明确不做 |
| IX. 每个 user story 可演示 | ✅ | 三个故事由 `AgentSchedulerTest` 的用例组独立可验；真实触发列入 quickstart 人工项 |
| AGENTS §5.1 #6 表结构唯一权威 | ✅ | 本节不建表、不动 Flyway |
| AGENTS §5.2 线程 / traceId | ✅ | 每次触发 `TracedExecutors.wrapRoot` 新开根链路；不新增 `TaskDecorator`；无 `synchronized`；无 `UUID.randomUUID()` 生成 traceId |
| AGENTS §5.2 日志 CRLF 净化 | ✅ | 任务 id / 消息入日志前 `replaceAll("[\r\n]", "_")` |

**Post-Phase 1 re-check**: 设计未引入新违规。唯一需要记录的取舍是 `sableos-core` 新增 `spring-context` 依赖（技术方案 §8.5 明确 `AgentScheduler` 归 core 且基于 Spring 调度能力），见 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/009-lesson25-scheduler/
├── spec.md
├── plan.md                       # 本文件
├── research.md                   # Phase 0（D-01 ~ D-09）
├── data-model.md                 # Phase 1（无新表）
├── contracts/agent-scheduler.md  # Phase 1（类契约 + 装配契约）
├── quickstart.md
├── checklists/requirements.md
└── tasks.md                      # /speckit-tasks 输出
```

### Source Code (repository root)

```text
sableos-core/
├── pom.xml                                                   # 修改：+ spring-context（compile，BOM 管理版本）
└── src/
    ├── main/java/com/sableos/core/scheduler/
    │   └── AgentScheduler.java                               # 新增：registerAll / runOnce / lockFor
    └── test/java/com/sableos/core/scheduler/
        └── AgentSchedulerTest.java                           # 新增：课件 harness 四类 + 非法配置跳过

sableos-boot/src/main/java/com/sableos/boot/scheduler/
└── SchedulerConfiguration.java                               # 新增：ThreadPoolTaskScheduler + AgentScheduler bean

sableos-cli/src/main/java/com/sableos/cli/command/
├── ServeCommand.java                                         # 修改：上下文启动后 registerAll()
└── GatewayCommand.java                                       # 修改：同上
```

**Structure Decision**: 沿用既有 9 模块。核心类落 `sableos-core`（§8.5 明定），Spring 装配落 `sableos-boot` 的按域配置包（与 `agent` / `react` / `provider` / `tool` / `observability` 同构），入口触发点落 `sableos-cli` 的两个常驻子命令。不改 `Profile`、不改存储、不改 web。

## Complexity Tracking

| 取舍 | 为什么需要 | 更简单的替代为何不行 |
|---|---|---|
| `sableos-core` 新增 compile 作用域的 `spring-context` | 《技术方案》§8.5 明确 `AgentScheduler` 归 `sableos-core`，并规定基于 Spring `ThreadPoolTaskScheduler` + `CronTrigger` 动态注册；core 当前仅在 test 作用域可见该构件（`dependency:tree` 已核） | 把类挪出 core 违反 §8.5 与 skill 落位表；在 core 自建调度抽象接口再于 cli 实现，属 skill 明令禁止的「文档外抽象层」；手写 cron 解析与线程调度违反课件「别自己写调度器」 |
| 注册时机由 `serve`/`gateway` 显式调用而非 `@PostConstruct` | §8.6 规定定时任务只随 `serve`/`gateway` 常驻调度；三种模式共用同一装配根，Bean 自注册会让 `chat` 也跑定时任务 | 用 `@ConditionalOnProperty` 开关需要新增配置键（超出本节交付物）；在 Bean 内嗅探 `WebApplicationType` 属隐式魔法，可读性更差 |
