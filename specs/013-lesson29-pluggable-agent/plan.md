# Implementation Plan: 插件化 Agent——一个目录定义一个会自己跑的 Agent

**Branch**: `029-lesson29-pluggable-agent` | **Date**: 2026-10-31 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/013-lesson29-pluggable-agent/spec.md`

## Summary

本节不新建能力模块，而是**补上"定义一个 Agent、把它装上去、让它跑起来"的机制**：把配置段派生成底座认识的运行配置、补齐运行期注册与单 Agent 调度登记、并把"渐进式披露"（正文常驻、参考/子指令/脚本按需取）固化成可执行验收。

技术路径是**改造三个既有类 + 装配一处**，底座主链路（`AgentService` / `ReActLoop` / `PromptBuilder` / `ToolExecutor`）零改动：

- `AgentLoader`：三参构造（新增已注册工具名单），补「未注册工具只告警不丢弃」，并把 `deriveProfile` 改为按目录派生（保留既有字符串入口）。
- `ProfileRegistry`：补 `remove(String)`；`register` / `exists` / `get` / `all` 已具备。
- `AgentScheduler`：抽出 `registerProfile(Profile)` 供 `registerAll` 与运行期新增共用；新增 `scheduledTasks`（`scheduleId` → 句柄）与 `hasScheduledTask`；重复登记取消旧句柄。
- 装配：`AgentConfiguration` 把 `ToolRegistry` 的工具名单注入 `AgentLoader`（boot → core，方向正确）。

## Technical Context

**Language/Version**: JDK 21（构建锁定 Java 21）

**Primary Dependencies**: Spring Boot 3.x（BOM 管理版本）、Spring AI Alibaba（仅 Provider 抽象与协议转换）、SnakeYAML（已在 core，用于 frontmatter 解析）、SLF4J；本节**不新增任何第三方依赖**

**Storage**: SQLite + Spring Data JPA（既有）；本节不新增表、不新增迁移

**Testing**: JUnit 5 + AssertJ + Mockito；`mvn clean verify`（Spotless / Checkstyle / P3C / SpotBugs / FindSecBugs）为完成定义

**Target Platform**: 单二进制 fat JAR，Linux / macOS / Windows 单机部署

**Project Type**: Maven 多模块单体（14 模块口径，本轮实际触及 3 个）

**Performance Goals**: 启动扫描为一次性 O(Agent 数)，无热路径影响；`ContextLoader` 每次现读文件属既有设计，不改

**Constraints**:
- 不新增对外概念：不新增 public 类型、配置键、数据表、REST 路径、Profile 字段
- 不修改已定字面量：`Profile` 记录字段、`AGENT.md` / `SKILL.md` 文件名、`schedules` 字段名逐字保真
- `skills/` 下只接受指向公共 Skill 根的相对软连接（技术方案 §11.1），普通 `.md` 会被判越界
- 解释器默认不入 `shell.allowed_commands`；本节不放开默认值

**Scale/Scope**: 单实例、Agent 数量为个位数到数十；仅进程内句柄，无分布式协调

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 说明 |
| --- | --- | --- |
| I. JDK 21 + Boot 3.x 单体 | ✅ | 无新增依赖、无新增模块 |
| II. 自实现 ReAct | ✅ | 不触碰 `ReActLoop` |
| III. Spring AI 只用一半 | ✅ | 不触碰 Provider / tool 执行路径 |
| IV. Provider 显式映射 | ✅ | 不新增 provider 发现机制 |
| V. Tool 三档 + 单一模块 | ✅ | 不新增 Tool；`AGENT.md` 与 `AgentLoader` 仍在 core 的上下文层，不进 Tool 体系 |
| VI. 审计 day one 落库 | ✅ | 无新审计需求；定时执行仍走既有 `task_executions` |
| VII. Sandbox 接口先行 | ✅ | 不新增 Sandbox 分支；脚本仍走既有 `shell` + 白名单 |
| VIII. 范围收敛 | ✅ | 不做热加载 / 版本 / 共享 / 容器隔离 |
| IX. 每 user story 可演示 | ✅ | US1/US2 用临时目录即可独立演示；US3/US4 用单测演示 |

**AGENTS §5.2 专项**：

| 检查项 | 结论 |
| --- | --- |
| 不让子线程继承 MDC | ✅ 本节不引入跨线程执行；`registerProfile` 复用既有 `TracedExecutors.wrapRoot` |
| 唯一 `TaskDecorator` | ✅ 不新增 |
| `TraceIds` 而非 `UUID.randomUUID()` | ✅ 不碰 trace 生成 |
| `synchronized` 内不阻塞 | ✅ 新增代码零 `synchronized`；句柄表用 `ConcurrentHashMap` |
| Provider 显式映射 | ✅ 不涉及 |
| 用户可控串入日志先 CRLF 净化 | ✅ 新增告警日志沿用既有 `safe(...)` 形态 |

## Project Structure

### Documentation (this feature)

```text
specs/013-lesson29-pluggable-agent/
├── plan.md              # 本文件
├── research.md          # Phase 0：冲突裁定与选择依据
├── data-model.md        # Phase 1：既有类型与新增成员
├── quickstart.md        # Phase 1：验证指南
├── contracts/
│   ├── agent-loader.md          # 目录派生与校验契约
│   ├── profile-registry.md      # 运行期注册契约
│   └── agent-scheduler.md       # 单 Agent 登记与句柄契约
└── tasks.md             # Phase 2（/speckit-tasks 产出）
```

### Source Code (repository root)

```text
sableos-core/src/main/java/com/sableos/core/
├── agent/AgentLoader.java                  # 修改：三参构造、未知工具告警、deriveProfile(Path)
├── profile/ProfileRegistry.java            # 修改：补 remove(String)
├── scheduler/AgentScheduler.java           # 修改：registerProfile / scheduledTasks / hasScheduledTask
├── context/ContextLoader.java              # 不改（现状已满足渐进式披露）
└── profile/Profile.java                    # 不改（字段集冻结）

sableos-boot/src/main/java/com/sableos/boot/
└── agent/AgentConfiguration.java           # 修改：把已注册工具名单注入 AgentLoader

sableos-core/src/test/
├── java/com/sableos/core/agent/
│   ├── AgentLoaderTest.java                # 扩展：未知工具告警、资源识别
│   ├── DeriveProfileTest.java              # 新增
│   ├── AgentScanRegisterTest.java          # 新增
│   └── (AgentSchedulerRegisterTest)        # 新增，见 scheduler 包
├── java/com/sableos/core/scheduler/AgentSchedulerRegisterTest.java   # 新增
├── java/com/sableos/core/profile/ProfileRegistryRuntimeTest.java     # 新增
├── java/com/sableos/core/context/ProgressiveDisclosureTest.java      # 新增
└── resources/agents/daily-reconcile/       # 示例 Agent 四件套（测试夹具，同时是手动参照物）
    ├── AGENT.md
    ├── REFERENCE.md
    ├── skills/report-format.md             # 注意：见 research D-04（软连接形态）
    └── scripts/reconcile.py
```

**结构决策**：示例 Agent 放 `src/test/resources/agents/daily-reconcile/`，同时充当 harness 夹具与手动参照物；不写入运行期 `.sableos/`（`.gitignore` 已排除，且会污染实际运行环境）。

## Phase 0：Research（冲突裁定）

详见 [research.md](./research.md)。核心裁定：D-01 示例目录落位、D-02 未知工具告警口径、D-03 `remove` 语义、D-04 `skills/` 软连接与课件普通 md 的冲突、D-05 句柄表命名与并发、D-06 重复登记的取消语义、D-07 运行时校验一致性、D-08 装配方向。

## Phase 1：Design

- [data-model.md](./data-model.md)：既有类型盘点与本节新增成员（无新表、无新实体）
- [contracts/agent-loader.md](./contracts/agent-loader.md)
- [contracts/profile-registry.md](./contracts/profile-registry.md)
- [contracts/agent-scheduler.md](./contracts/agent-scheduler.md)
- [quickstart.md](./quickstart.md)

## Phase 2：Tasks

由 `/speckit-tasks` 产出 [tasks.md](./tasks.md)；本节固定软停点在 tasks 完成后比对「本节交付物」。

## Complexity Tracking

无违规项。本节刻意不引入抽象：句柄表复用既有 `ConcurrentHashMap`、派生逻辑复用既有 `deriveProfile`、告警复用既有 `safe(...)` 净化形态。唯一"新增对外成员"是 `ProfileRegistry.remove` 与 `AgentScheduler.registerProfile` / `hasScheduledTask`，三者均为课件「本节交付物」逐字点名的项。
