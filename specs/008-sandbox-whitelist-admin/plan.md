# Implementation Plan: Sandbox 白名单管理接口（增删改查）

**Branch**: `008-sandbox-whitelist-admin` | **Date**: 2026-10-29 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-sandbox-whitelist-admin/spec.md`，输入源为 `docs/requirements/Sandbox白名单管理接口设计.md`。

## Summary

为第 24 节交付的只读沙箱白名单补上运行时增删改查：条目落 SQLite（双轨 Flyway），运行时以 DB 为事实源，管理服务写库后重建不可变策略快照并通过 `AtomicReference` 无锁发布，`WhitelistSandbox.enforce` 每次只读一次快照。`application.yaml` 只在首次启动导入一次，用独立标记表记录"已导入"以区分「导入过 0 条」与「从未导入」。`Sandbox` 接口、工具侧 `enforce` 调用点、审计路径逐字不改。

技术路线：core 定义契约与领域异常 → storage 实现 JPA 存储 → tool 实现校验归一与快照刷新 → web 暴露 REST 并映射异常 → cli（实际装配根）负责导入与告警。

## Technical Context

**Language/Version**: JDK 21（Maven 编译目标锁定 21）

**Primary Dependencies**: Spring Boot 3.5.16、Spring Data JPA、Flyway（双轨 `db/migration/{vendor}`）、spring-boot-starter-web、spring-boot-starter-validation、JUnit 5 + AssertJ + MockMvc。**不新增任何第三方依赖**——全部由既有 parent POM 管理。

**Storage**: SQLite（默认档，`jdbc:sqlite:${workspace.root}/sableos.db`）与 PostgreSQL（部署档）双轨迁移。表结构唯一权威是 Flyway 脚本，禁用 `ddl-auto`。

**Testing**: JUnit 5 + AssertJ + MockMvc；规则单测、服务单测、快照单测、存储单测（`@SpringBootTest` + 临时 SQLite）、Controller 切片（`@WebMvcTest`）、集成（`@SpringBootTest` + MockMvc）。三平台 CI 矩阵跑聚焦测试。

**Target Platform**: Windows / macOS / Linux（行为一致，CI 矩阵验证）

**Project Type**: Maven 多模块单体（单二进制 fat JAR）

**Performance Goals**: 校验路径零新增开销（一次 `AtomicReference.get()`）；白名单为低基数配置，每类上限 1000 条。

**Constraints**: 必须在虚拟线程（JDK 21）下工作，校验路径禁止阻塞锁；`Sandbox` 接口与工具侧调用点零改动；响应信封恒为 5 字段。

**Scale/Scope**: 单实例部署；5 个模块（core / storage / tool / web / cli）各改一处；新增 2 张表、1 个配置键、2 个 REST 路径基址。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 说明 |
| --- | --- | --- |
| I. JDK 21 + Boot 3.x 单体 | ✅ | 不引入响应式栈，不新增模块 |
| II. 自实现 ReAct | ✅ | 不触碰 ReAct 循环 |
| III. Spring AI 只用一半 | ✅ | 不引入 Spring AI 自动 tool 执行 |
| IV. Provider 显式映射 | ✅ | 不涉及 |
| V. Tool 三档且唯一模块 | ✅ | 改动落在既有 `sableos-tool`，不拆模块 |
| VI. 审计 day one 落库 | ✅ | 不改变既有审计路径；本 feature 新增的写操作只写操作日志（FR-015），不新建审计表（设计稿 §11 已定） |
| VII. Sandbox 接口先行 | ✅ | `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 逐字不改（FR-017） |
| VIII. 范围收敛 | ✅ | 属于运行时内核能力，非治理层；不做认证/RBAC/多租户 |

**架构约束（AGENTS §5.2 / ArchUnit）**：
- 依赖方向 `web → core`、`tool → core`、`storage → core`、`cli → 全部`，`tool` 不依赖 `storage`（通过 core 的 `SandboxWhitelistStore` 倒置）→ 满足既能 `ArchitectureTest` 的 core 单向依赖与无环规则。
- 校验路径禁止 `synchronized` 内阻塞、禁止 Reactor/`CompletableFuture`。
- 用户可控字符串入日志前 `replaceAll("[\\r\\n]", "_")`。
- 全容器只允许 `ObservabilityConfig` 那一个 `TaskDecorator`（本 feature 不新增）。

**门禁决策**：无违反项，无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/008-sandbox-whitelist-admin/
├── spec.md              # 需求（已完成，含 4 条 clarify 结论）
├── plan.md              # 本文件
├── research.md          # Phase 0：关键技术决策
├── data-model.md        # Phase 1：表结构与实体映射
├── contracts/
│   ├── rest-api.md      # REST 契约
│   └── core-contracts.md# core 接口契约
├── quickstart.md        # Phase 1：验证步骤
└── tasks.md             # Phase 2：/speckit-tasks 产出
```

### Source Code (repository root)

```text
sableos-core/src/main/java/com/sableos/core/sandbox/
├── WhitelistType.java              # 枚举：FILE_PATH / SHELL_COMMAND / HTTP_DOMAIN
├── WhitelistEntry.java             # record：id / type / value / description / createdAt / updatedAt
├── WhitelistCommand.java           # record：type / value / description（写入意图）
├── SandboxWhitelistAdmin.java      # 管理服务契约：list / get / add / update / delete
├── SandboxWhitelistStore.java      # 存储契约：findAll / findById / insert / update / delete
│                                   #            / isSeeded / markSeeded / countByType
├── WhitelistValidationException.java
├── WhitelistEntryNotFoundException.java
└── WhitelistDuplicateException.java

sableos-storage/src/main/java/com/sableos/storage/sandbox/
├── SandboxWhitelistEntryEntity.java
├── SandboxWhitelistEntryRepository.java
├── SandboxWhitelistSeedEntity.java
├── SandboxWhitelistSeedRepository.java
└── JpaSandboxWhitelistStore.java

sableos-storage/src/main/resources/db/migration/
├── sqlite/V5__sandbox_whitelist.sql
└── postgresql/V5__sandbox_whitelist.sql

sableos-tool/src/main/java/com/sableos/tool/sandbox/
├── WhitelistRules.java             # 校验 + 归一（三类）
├── WhitelistPolicy.java            # 不可变策略快照
├── WhitelistSandbox.java           # 改造：字段换为 AtomicReference<WhitelistPolicy>，新增包内 refresh
└── DefaultSandboxWhitelistAdmin.java

sableos-web/src/main/java/com/sableos/web/
├── sandbox/SandboxWhitelistController.java
├── sandbox/dto/WhitelistEntryRequest.java
├── sandbox/dto/WhitelistEntryResponse.java
└── GlobalExceptionHandler.java     # 改造：新增 3 个精确 handler

sableos-cli/src/main/java/com/sableos/cli/
└── runtime/SandboxWhitelistBootstrap.java   # 首次导入 ApplicationRunner + 启动告警
```

> **与设计稿的偏差（已在 plan 内修正）**：设计稿 §4.1 与 §10 阶段 4 把首次导入与告警放在 `sableos-boot`。实际装配根自第 18 节起在 `sableos-cli/SableOsRuntime`（`@EnableJpaRepositories` / `@EntityScan` 唯一声明处），`sableos-boot/SableOsApplication` 已剥空。因此导入逻辑落在 `sableos-cli`，否则 JPA 仓库在该路径下不可用。这一偏差需在实施报告里显式记录。

## Phase 0：Research（见 research.md）

关键决策已确定，无 NEEDS CLARIFICATION：
1. 幂等归一的实现（`normalize()` + 分隔符统一 `/`，盘符大小写不归一）
2. 唯一约束与并发重复的兜底（DB 唯一索引 + 捕获约束异常转 409）
3. 快照发布的可见性（`AtomicReference` + 不可变集合）
4. 首次导入的严格模式与事务边界
5. 异常分层（领域异常不继承 `IllegalArgumentException`）

## Phase 1：Design（见 data-model.md / contracts/ / quickstart.md）

数据模型两张表；REST 契约 5 个端点；core 契约 2 个接口 + 3 个异常。

## Phase 2：Tasks

由 `/speckit-tasks` 生成，顺序按依赖：core 契约 → storage → tool → web → cli → harness。

## Complexity Tracking

无违反宪章之处，本节留空。
