---
description: "Task list for 第27节 全流程串联（一）人推链路"
---

# Tasks: 全流程串联（一）——打通 Agent 人推主流程

**Input**: Design documents from `specs/011-lesson27-human-trigger-flow/`

**Prerequisites**: plan.md、spec.md、research.md（D-01 ~ D-06）、data-model.md、contracts/rest-api.md

**Tests**: 课件「验收 harness」明确要求三个无 key/真 key 测试类，故测试任务为必需项，与实现同任务组落地。

**Organization**: 按用户故事分阶段；多数任务分散在不同模块，标 `[P]` 的可并行。

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [ ] T001 父 POM 把 surefire 的 `excludedGroups` 由硬编码改为项目属性 `${excludedGroups}`（默认 `integration`），使 `-DexcludedGroups=` 可覆盖

## Phase 2: US2 - 会话列表（可独立验证，故先做）

- [ ] T002 [US2] `sableos-core`：`SessionManager` 新增 `listRecent(int)` 默认方法 + `listRecent(int, String status)` 抽象方法，javadoc 写明"过滤必须在存储侧"
- [ ] T003 [US2] `sableos-storage`：`SessionRepository` 增加 `findByStatus(String, Pageable)`
- [ ] T004 [US2] `sableos-storage`：`JpaSessionManager.listRecent(limit, status)` 改用 `PageRequest`，过滤/排序/截断全在 SQL 侧
- [ ] T005 [P] [US2] `sableos-storage`：`SessionManagerTest` 增加"按活跃时间倒序 + 上限""非正数上限返回空""状态过滤不被未命中行挤掉"三个用例
- [ ] T006 [P] [US2] `sableos-web`：新增 `SessionSummaryView` DTO、`ApiConstraints.MAX_SESSION_LIST`、`SessionApiController#list`
- [ ] T007 [US2] `sableos-web`：`SessionApiControllerTest` 增加"摘要不带正文""status 下推给存储""空白 status 视为不过滤"三个用例
- [ ] T008 [P] [US2] 前端：`endpoints.ts` 增加 `listSessions`、`types/api.ts` 增加 `SessionSummary`、`SessionsPage.vue` 用真实列表替换 localStorage 占位
- [ ] T009 [US2] 重新构建前端产物到 `static/admin/`

**Checkpoint**: `GET /api/v1/sessions` 可用；管理台"会话"页有真实列表。

## Phase 3: US1/US3 - mock provider 与全链路

- [ ] T010 [US1] `sableos-provider`：`ProvidersProperties` 增加 `TYPE_MOCK`，校验对 mock 放行（不需要 api-key / base-url）
- [ ] T011 [US1] `sableos-provider`：新增 `MockChatModel`（无状态，`Tool[save_memory]:` 标记判定第二轮）
- [ ] T012 [US1] `sableos-provider`：`ProviderChatModelFactory` 对 mock 直接构造 `MockChatModel`；`HttpProviderHealthProbe` 对 mock 直判可达
- [ ] T013 [P] [US1] `sableos-provider`：`MockChatModelTest` 钉脚本行为（第一轮发工具调用、第二轮收尾、空白兜底、两种"记住"前缀）
- [ ] T014 [P] [US1] `sableos-provider`：扩展 `ProviderChatModelFactoryTest` / `ProvidersPropertiesTest`
- [ ] T015 [US1] `sableos-boot`：`MockProviderFlowTest`（手工装配全链路 + `/sessions` `/memory` `/tools` 三端点对账）
- [ ] T016 [US1] `sableos-boot`：`MockAgentE2ETest`（真端口 + 临时 SQLite，HTTP 建会话/发消息/查回全部）

**Checkpoint**: 无 key 也能在门禁里跑通"想 → 查 → 答 → 记账"。

## Phase 4: US3 - 工作区根可覆盖

- [ ] T017 [US3] `application.yaml`：`workspace.root: ${sableos.root:.sableos}` + 注释示例（mock provider）
- [ ] T018 [P] [US3] `sableos-boot`：`AgentConfiguration` / `ReactConfiguration` / `ToolConfiguration` 的 `.sableos` 硬编码改为读 `workspace.root`
- [ ] T019 [P] [US3] `sableos-cli`：`Workspace.root()` 按 `sableos.root` → `WORKSPACE_ROOT` → 默认 解析

**Checkpoint**: 整机测试可指向临时工作区，agents / skills / mcp_servers.yaml / SQLite 一起跟随。

## Phase 5: US4 - 失败记账与三面同源

- [ ] T020 [US4] `sableos-boot`：`HumanTriggerFlowIT`（`@Tag("integration")`），覆盖三支柱 + 三类失败记账 + CLI/REST 三面同源；无 key 用 `assumeTrue` 跳过
- [ ] T021 [US4] 复核 `HumanTriggerFlowIT` 支柱一补齐"两次模型回复"断言、支柱三补齐"对外清单覆盖每个 Profile 工具集"断言

**Checkpoint**: 真 key 下手动跑可全绿；无 key 时跳过而非失败。

## Phase 6: Polish

- [ ] T022 [P] README：端点表补 `GET /sessions`（11 端点）、新增「构建管理台与启动」、能力表 10→11
- [ ] T023 [P] `docs/TechnicalSolution.md`：§7.2 与第 13 章 / 五大能力清单的端点数统一为 11
- [ ] T024 [P] `sableos-web/src/main/frontend/README.md`：端点表补列表接口、修正"不包含会话列表"的过时表述
- [ ] T025 [P] 课件第 27 节：`serve --port` / `bin/start.sh` / `config/application.yml` / `.sableos/profiles/` / `config/application.yml.example` 改为本仓库事实；Bootstrap 缺失口径改为两档
- [ ] T026 [P] AGENTS.md：更新 27 节要点（listRecent 语义、管理台构建链、四文件端点口径统一）
- [ ] T027 跑 `mvn clean verify` 全绿，并把实测证据写进 acceptance-report.md

## Dependencies

- T002 → T003 → T004 → T005/T006 → T007 → T008 → T009
- T010 → T011 → T012 → T014；T011 → T013
- T011 + T009 → T015 → T016
- T017 → T018/T019 → T016
- T016 + T013 → T020 → T021
- 全部 → T027

## Parallel Execution Examples

- T005 / T006 / T008 / T013 / T014 互不同文件，可并行
- T018 / T019 可并行
- T022 / T023 / T024 / T025 / T026 纯文档，可并行
