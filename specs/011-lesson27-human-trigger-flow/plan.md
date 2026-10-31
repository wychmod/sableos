# Implementation Plan: 全流程串联（一）——打通 Agent 人推主流程

**Branch**: `011-lesson27-human-trigger-flow` | **Date**: 2026-10-31 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/011-lesson27-human-trigger-flow/spec.md`

## Summary

集成课，**不新增模块、不引入新概念**。交付"一句话走通想 → 查 → 答 → 记账"这件事本身，以及把它固化成可一键重演的对账 harness。三处代码改动：① 会话列表端点 `GET /api/v1/sessions`（`SessionManager.listRecent` 双重重载 + `SessionRepository.findByStatus`，过滤/排序/截断全在存储侧）；② 第三类 provider `mock`（`ProvidersProperties.TYPE_MOCK` + `MockChatModel` + 工厂接线 + 健康探测直判可达）；③ 工作区根支持 `sableos.root` 系统属性覆盖（让整机测试指向临时工作区）。测试侧新增三个 harness：`HumanTriggerFlowIT`（真 key 集成）、`MockProviderFlowTest`（手工装配、快）、`MockAgentE2ETest`（真端口整机），外加 `MockChatModelTest` 钉 mock 脚本行为。文档侧补 README 管理台构建链与 11 端点口径。

## Technical Context

**Language/Version**: Java 21（父 POM enforcer 钉死）

**Primary Dependencies**: Spring Boot 3.5.16 / Spring Framework 6.2.19 / Spring Data JPA（既有）；Spring AI（既有，mock 走 `ChatModel` 接口，不加自动装配）。**不新增第三方依赖**。

**Storage**: 复用 `sessions` / `llm_calls` / `tool_invocations` 三表，**不加表、不加列、不改 Flyway**。新增的只是查询方式（`findByStatus` + `Pageable`）。

**Testing**: JUnit 5 / Mockito 5 / AssertJ / MockMvc / `TestRestTemplate`。新增 4 个测试类；扩展 `SessionManagerTest`、`SessionApiControllerTest`、`ProviderChatModelFactoryTest`、`ProvidersPropertiesTest`。父 POM surefire 的 `excludedGroups` 由硬编码改为 `${excludedGroups}` 项目属性。

**Target Platform**: JVM 单二进制（`sableos-boot` fat JAR）；`serve` 常驻。

**Project Type**: Maven 多模块后端单体（14 模块口径中已有 9+ 个在库）。

**Performance Goals**: 会话列表默认 100 条摘要、不读正文；`MockChatModel` 无实例状态，可并发。

**Constraints**: 会话 id 只在 `SessionManager.sessionId` 拼接；mock 不得引入新审计口径；`sableos.root` 覆盖时工作区四类资源必须一起跟随；集成测试无 key 必须跳过而非失败。

**Scale/Scope**: 生产代码新增 1 个 core 接口方法 + 1 个存储查询 + 1 个 Controller 端点 + 1 个 DTO + 1 个 `MockChatModel` + 3 处配置类改读 `workspace.root` + 1 个轻命令解析方法；测试新增 4 个类。无新模块、无新表、无新依赖。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据 |
| --- | --- | --- |
| I. JDK 21 + Spring Boot 3.x 单体 | ✅ | 无新依赖、无新中间件 |
| II. 自实现 ReAct 循环 | ✅ | harness 只调用既有 `ReActLoop` / `AgentService.process`，不新增循环逻辑 |
| III. Spring AI 只用一半 | ✅ | `MockChatModel` 实现 Spring AI 的 `ChatModel` 接口，但**不启用自动 tool 执行**；工具调度仍只在 `ToolExecutor` |
| IV. Provider 显式映射 | ✅ | `mock` 加进 `ProvidersProperties` 显式映射表，不做类型扫描 |
| V. Tool 单模块 | ✅ | 不新增工具模块；mock 复用既有 `save_memory` |
| VI. 审计 day one 落库 | ✅ | 无 key 全链路仍真写 `llm_calls` / `tool_invocations`；失败路径有专门断言 |
| VII. Sandbox 接口先行 | ✅ | 失败路径断言包含「沙箱拦截留 `success=false`」，未绕过沙箱 |
| VIII. 范围收敛 | ✅ | 不做定时链路、不做 Demo 准备（归 28 节）；不加认证/流式 |
| IX. 每个 user story 可演示 | ✅ | US1/US3/US4 由 `MockAgentE2ETest` + `HumanTriggerFlowIT` 覆盖；US2 由存储/控制器测试 + 管理台人工验收覆盖 |
| AGENTS §5.1 #6 表结构唯一权威 | ✅ | 不改表；查询只用 `findByStatus` + `Pageable` |
| AGENTS §5.2 虚拟线程 / traceId | ✅ | harness 不新建线程池；`ApiConfiguration` 既有执行器不变 |
| AGENTS §5.2 阻塞调用与锁 | ✅ | 无新增锁；`listRecent` 只做只读查询 |
| AGENTS §5.3 质量门槛 | ✅ | 实现结束跑 `mvn clean verify` 全绿（含两个无 key harness） |

## Project Structure

### Documentation (this feature)

```text
specs/011-lesson27-human-trigger-flow/
├── spec.md              # 用户故事与验收场景
├── plan.md              # 本文件
├── research.md          # D-01 ~ D-06 关键技术决策
├── data-model.md        # 无新表；会话摘要视图口径
├── contracts/
│   └── rest-api.md      # GET /api/v1/sessions 契约（11 端点口径）
├── quickstart.md        # 无 key 手动验收步骤
├── checklists/
│   └── requirements.md  # 需求质量检查
├── tasks.md             # 任务拆解
└── acceptance-report.md # 本节验收记录（含实测证据）
```

### Source Code (repository root)

```text
sableos-core/
└── src/main/java/com/sableos/core/session/SessionManager.java      # 改：listRecent(int) 默认 + listRecent(int, status)

sableos-storage/
├── src/main/java/com/sableos/storage/SessionRepository.java        # 改：findByStatus(status, Pageable)
├── src/main/java/com/sableos/storage/JpaSessionManager.java        # 改：listRecent 走 PageRequest
└── src/test/java/com/sableos/storage/SessionManagerTest.java       # 改：状态过滤不被未命中行挤掉

sableos-provider/
├── src/main/java/com/sableos/provider/ProvidersProperties.java     # 改：TYPE_MOCK + 校验放行
├── src/main/java/com/sableos/provider/ProviderChatModelFactory.java# 改：mock → MockChatModel
├── src/main/java/com/sableos/provider/HttpProviderHealthProbe.java # 改：mock 直判可达
├── src/main/java/com/sableos/provider/MockChatModel.java           # 新增
└── src/test/java/com/sableos/provider/MockChatModelTest.java       # 新增

sableos-web/
├── src/main/java/com/sableos/web/api/SessionApiController.java     # 改：GET /sessions
├── src/main/java/com/sableos/web/api/ApiConstraints.java           # 改：MAX_SESSION_LIST
├── src/main/java/com/sableos/web/api/dto/SessionSummaryView.java   # 新增
├── src/main/frontend/src/{api/endpoints.ts,pages/SessionsPage.vue,types/api.ts}  # 改：接真实列表
└── src/main/resources/static/admin/**                              # 改：重新构建产物

sableos-boot/
├── src/main/java/com/sableos/boot/{agent,react,tool}/*Configuration.java  # 改：读 workspace.root
├── src/main/resources/application.yaml                             # 改：mock 示例 + sableos.root
├── src/test/java/com/sableos/web/api/MockProviderFlowTest.java     # 新增
├── src/test/java/com/sableos/boot/MockAgentE2ETest.java            # 新增
└── src/test/java/com/sableos/channel/cli/HumanTriggerFlowIT.java   # 新增

sableos-cli/
└── src/main/java/com/sableos/cli/command/Workspace.java            # 改：sableos.root 优先

pom.xml                                                              # 改：excludedGroups 项目属性
README.md                                                            # 改：11 端点 + 管理台构建链
```

## Phase 0 研究结论

见 [research.md](./research.md)：D-01 列表过滤下推到存储、D-02 mock 挂显式映射表、D-03 `MockChatModel` 无状态判定、D-04 `sableos.root` 的优先级、D-05 三层 harness 的分工、D-06 `excludedGroups` 改项目属性。

## Phase 1 设计

- 数据模型：无新表，只加只读投影视图 → [data-model.md](./data-model.md)
- 接口契约：`GET /api/v1/sessions` → [contracts/rest-api.md](./contracts/rest-api.md)
- 快速验证：无 key 手动流程 → [quickstart.md](./quickstart.md)

## 与课件的差异（已对齐）

课件按另一套仓库布局书写，本次核对后已就地把课件改成本仓库事实：

| 课件原文 | 本仓库实际 | 处理 |
| --- | --- | --- |
| `serve --port 8080` | `serve` 无该选项，只认 `server.port` / `SERVER_PORT` | 改课件 |
| `bin/start.sh` | 仓库无 `bin/` | 改课件为 `java -jar ... serve` |
| `config/application.yml` | 无此文件；配置在 `sableos-boot/src/main/resources/application.yaml` | 改课件 |
| `.sableos/profiles/<name>/` | Agent 目录是 `.sableos/agents/<name>/AGENT.md` | 改课件 |
| `config/application.yml.example` | 不存在；做法是 yaml 内注释示例 + README | 改课件 |
| Bootstrap 缺失「直接报错」 | 实现为 WARN（与 17 节契约一致），Skill 绑定断裂才报错 | 改课件 |
| `listRecent` 用 `findAll` 内存排序 | 过滤下推到 `findByStatus` + `PageRequest` | 两边都改 |

## Complexity Tracking

无宪法违规，无需额外说明。
