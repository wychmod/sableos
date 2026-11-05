# Implementation Plan: 动态管理 Agent——上传即上线、可生成、可浏览

**Branch**: `014-lesson30-dynamic-agent` | **Date**: 2026-10-31 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/014-lesson30-dynamic-agent/spec.md`

## Summary

把「定义一个 Agent」从一个目录的手工维护，升级为**三条录入路径殊途同归**的运行时能力：API 创建（模板脚手架）、手工丢目录（`WatchService` 实时监听）、启动扫描——三者都汇到同一个 `AgentLifecycleService.register(agentDir)`，全程免重启。在此之上补齐查询/更新/删除（删除按「注销定时 → 移出索引 → 归档」定序）、大模型按描述重生成文件草稿（人在环里预览）、工作区文件浏览与编辑（防目录穿越）、每 Agent 专属记忆（`agents/<name>/MEMORY.md`）与固定管理台会话，最终落在管理台「Agent 列表 + 详情五页签」。

技术路径：新增 `AgentStore`（文件读写/归档，core）+ `AgentLifecycleService`（编排，core）+ `WorkspaceWatcher`（`WatchService` 守护线程，core）+ `ToolExecutionContext`（per-agent 记忆的线程上下文，core）；`AgentScheduler` 补 `unregisterProfile`；web 侧扩展 `AgentApiController` 并新增 `WorkspaceApiController`；装配落在 `sableos-boot` 既有 `AgentConfiguration` 旁；前端在既有 Vue 管理台上扩展 Agent 页与详情抽屉。

## Technical Context

**Language/Version**: JDK 21（`maven.compiler.release=21`），禁用 Java 22+ 语法与非 preview 特性。

**Primary Dependencies**: Spring Boot 3.5.16、Spring AI Alibaba 1.1.x（只用 Provider 抽象与 `@Tool` schema）、Spring Data JPA + Flyway、SnakeYAML（core 已有，用于脚手架 frontmatter dump）、Vue 3.5 + Vite 6 + vue-router 4（管理台，构建链不在 Maven 内）。

**Storage**: 无新增数据表。Agent 定义与记忆是文件（`.sableos/agents/<name>/`、`.sableos/archive/<name>/`）；审计仍走既有 `llm_calls` / `tool_invocations`。

**Testing**: JUnit 5 + AssertJ + Mockito（`InOrder` 钉顺序）+ `MockMvcBuilders.standaloneSetup`（web 薄门面）+ `@TempDir`（真实文件系统）。集成冒烟沿用 `@Tag("integration")`。

**Target Platform**: Linux / Windows / macOS 单机（`WatchService` 平台差异已在设计中显式处理）。

**Project Type**: Maven 多模块单体 + 内嵌管理台 SPA。

**Performance Goals**: 创建/更新/删除为低频管理操作，单次控制在百毫秒级（不含模型调用）；监听事件处理不阻塞其他 Agent（单守护线程、逐事件容错）。

**Constraints**:
- `core` 不得依赖任何能力模块（`ArchitectureTest` 红线）：`AgentStore` / `AgentLifecycleService` / `WorkspaceWatcher` / `ToolExecutionContext` 全部落 `com.sableos.core..`，只依赖 JDK + 已在 core 的 Spring（`spring-context`）与 SnakeYAML。
- 不做异步编程模型：监听循环是基础设施守护线程（与 25 节调度线程同类），请求链路仍同步阻塞。
- 生成的模型调用必须经既有 `ProviderService.chat` 并落 `llm_calls` 审计（原则五）。
- 无新增第三方依赖。

**Scale/Scope**: 13 行端点表 / 14 个 HTTP 端点；3 个新 core 类 + 1 个 web 新 Controller；1 张新增前端详情抽屉（5 页签）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 依据 |
| --- | --- | --- |
| I. JDK 21 + Boot 3.x 单体 | ✅ | 不新增模块、不引入非 21 语法 |
| II. 自实现 ReAct | ✅ | 不触碰 `ReActLoop`；「生成」只是一次单轮 `ProviderService.chat`，不经 ReAct |
| III. Spring AI 只用一半 | ✅ | 未启用自动 tool 执行；`ToolExecutor` 仍是唯一执行口 |
| IV. Provider 显式映射 | ✅ | 生成用 provider 由新增配置键显式指定，缺省取 `sableos.providers` 首个（非类型扫描） |
| V. Tool 单模块 + Agent 目录归 core | ✅ | 新类全部落 core；不新增 tool 模块实现 |
| VI. 审计 day one 落库 | ✅ | 生成同样走 `ProviderService`，`llm_calls` 由既有实现写入 |
| VII. Sandbox 接口先行 | ⚠️ 需说明 | 工作区读写由 web 层 `WorkspaceApiController` 直接做路径边界校验，**不经** `Sandbox`。理由见 Complexity Tracking |
| VIII. 范围收敛 | ✅ | 鉴权、zip 上传、启停位、版本历史均不做 |
| IX. 每个 US 可演示 | ✅ | 五个 US 均有独立 harness |

**Verdict**: PASS（VII 的说明已落入 Complexity Tracking，属于既有 24 节 `Sandbox` 面向**工具执行**的口径边界，不是绕开）。

## Project Structure

### Documentation (this feature)

```text
specs/014-lesson30-dynamic-agent/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── rest-api.md
│   └── lifecycle-store-watcher.md
└── tasks.md
```

### Source Code (repository root)

```text
sableos-core/src/main/java/com/sableos/core/
├── agent/
│   ├── AgentStore.java              # 新增：Agent 目录读写 / 归档 / 回滚删除
│   ├── AgentLifecycleService.java   # 新增：create / register(Path) / update / delete / list / get / generate
│   ├── WorkspaceWatcher.java        # 新增：WatchService 守护线程（启动全量扫 + 实时监听）
│   └── ToolExecutionContext.java    # 新增：ThreadLocal<String> agentName（per-agent 记忆）
├── scheduler/AgentScheduler.java    # 修改：新增 unregisterProfile(Profile)
└── tool/ToolExecutor.java           # 修改：执行前后置入/清除 ToolExecutionContext

sableos-memory/src/main/java/com/sableos/memory/
├── MemoryServiceImpl.java           # 修改：读路径按 session.profileName()/profile.name() 置入上下文
└── store/MarkdownMemoryStore.java   # 修改：默认落 agents/<name>/MEMORY.md，无上下文回退全局

sableos-web/src/main/java/com/sableos/web/
├── api/AgentApiController.java      # 修改：+list/get/create/update/delete/generate-files/files/memory/session*
├── api/WorkspaceApiController.java  # 新增：tree / file(GET) / file(POST)
├── api/dto/{AgentView,AgentDetailView,FileNode,CreateAgentRequest,UpdateAgentRequest,GenerateFilesRequest,SaveFilesRequest,AgentSessionMessageRequest}.java
└── api/MemoryApiController.java     # 删除：全局 /api/v1/memory（§5.2.1）

sableos-boot/src/main/java/com/sableos/boot/agent/AgentConfiguration.java  # 修改：+AgentStore/AgentLifecycleService/WorkspaceWatcher 装配

sableos-web/src/main/frontend/src/
├── pages/AgentsPage.vue             # 重写：列表 + 新建
├── pages/AgentDetailPage.vue        # 新增：五页签
├── api/endpoints.ts, types/api.ts   # 修改：新增端点与类型
└── router/index.ts                  # 修改：/agents/:name
sableos-web/src/main/resources/static/admin/**  # 构建产物入库
```

**Structure Decision**: 沿用现有 Maven 多模块。新增逻辑全部落在既有模块内，不新增模块：`core` 承载与 Spring 无关的编排与文件语义（可脱离 web 单测），`web` 只做薄门面，`boot` 做装配，`memory` 只改路径解析。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 工作区读写不经 `Sandbox`（原则 VII） | `Sandbox` 是**工具执行期**的涉外动作白名单（`file`/`shell`/`http`），语义对象是「Agent 跑起来要碰什么」；工作区接口是**管理员经管理台**读写 `.sableos/`，语义对象是「运维能看/改工作区」。两者受众与威胁模型不同：前者防模型越权，后者防路径穿越。若强行走 `Sandbox.enforce`，需把 `agents/` 整棵子树加进 Agent 的工具白名单，反而放大了模型可触范围 | 复用 `Sandbox` 会让「管理台可读」自动等于「Agent 工具可写」，把两个权限域合并，安全上更差 |
| 新增配置键 `sableos.author.provider` / `sableos.author.model` | 系统没有「默认模型」概念（model 只写在各 `AGENT.md` 的 provider 段）；生成端点没有 model 就无法调用 | 复用首个 provider 的配置：该结构里根本没有 model 字段，无从获取 |
