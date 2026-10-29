# Tasks: Sandbox 白名单管理接口（增删改查）

**Feature**: `008-sandbox-whitelist-admin` | **Input**: [spec.md](./spec.md), [plan.md](./plan.md), [data-model.md](./data-model.md), [contracts/](./contracts/)

**测试先行**：每个阶段的任务按「测试 → 实现」顺序排列，测试任务在对应实现任务之前或同期。

## Phase 0：core 契约（无内部依赖）

- [x] T001 在 `sableos-core/src/main/java/com/sableos/core/sandbox/` 新建 `WhitelistType.java`（三枚举值，不含 SMTP）
- [x] T002 [P] 新建 `WhitelistEntry.java`（record：id/type/value/description/createdAt/updatedAt）
- [x] T003 [P] 新建 `WhitelistCommand.java`（record：type/value/description）
- [x] T004 [P] 新建 `WhitelistValidationException.java`（extends RuntimeException）
- [x] T005 [P] 新建 `WhitelistEntryNotFoundException.java`（extends RuntimeException）
- [x] T006 [P] 新建 `WhitelistDuplicateException.java`（extends RuntimeException）
- [x] T007 新建 `SandboxWhitelistStore.java`（存储契约，9 个方法，见 contracts/core-contracts.md §4）
- [x] T008 新建 `SandboxWhitelistAdmin.java`（管理服务契约，5 个方法）
- [x] T009 新建 `sableos-core/src/test/java/com/sableos/core/sandbox/SandboxWhitelistContractTest.java`：断言三个异常均不继承 `IllegalArgumentException`

## Phase 1：storage（依赖 Phase 0）

- [x] T010 写 `sableos-storage/src/main/resources/db/migration/sqlite/V5__sandbox_whitelist.sql`（两张表 + 唯一索引 + CHECK）
- [x] T011 [P] 写 `sableos-storage/src/main/resources/db/migration/postgresql/V5__sandbox_whitelist.sql`（同版本，PG 语法）
- [x] T012 [P] 新建 `SandboxWhitelistEntryEntity.java`（`@PrePersist` / `@PreUpdate`）
- [x] T013 [P] 新建 `SandboxWhitelistSeedEntity.java`（id 固定 1，无 `@GeneratedValue`）
- [x] T014 [P] 新建 `SandboxWhitelistEntryRepository.java`（4 个查询方法）
- [x] T015 [P] 新建 `SandboxWhitelistSeedRepository.java`
- [x] T016 先写 `SandboxWhitelistEntryRepositoryTest.java`：V5 建两表、唯一索引拦截重复、`CHECK (id=1)` 生效、`entry_count=0` 可写入、时间戳回读正确
- [x] T017 实现 `JpaSandboxWhitelistStore.java`（实体 ↔ core record 映射；`insert` 捕获约束异常转 `WhitelistDuplicateException`；写方法 `@Transactional`），使 T016 转绿

## Phase 2：tool（依赖 Phase 0、1）

- [x] T018 先写 `WhitelistRulesTest.java`：三类校验、归一幂等、等价路径收敛（`//` / `./` / `a/../b` / 尾斜杠）、非 ASCII 域名拒绝、命令空白与大小写、域名通配与 lookalike、超长与空值
- [x] T019 实现 `WhitelistRules.java` + `WhitelistPolicy.java`，使 T018 转绿
- [x] T020 改造 `WhitelistSandbox.java`：三个不可变字段 → `AtomicReference<WhitelistPolicy>`；新增包内 `refresh(WhitelistPolicy)`；`enforce` 只在入口 `policy.get()` 一次
- [x] T021 扩展 `WhitelistSandboxTest.java`：`refresh` 后立即生效、空策略 deny-all、旧规则不失守（现有 29 例须全绿且不改断言）
- [x] T022 先写 `SandboxWhitelistAdminTest.java`：CRUD、409、404、更新后新旧值切换、删除后立即拒绝、每类 1000 上限、删光后不重播、快照刷新、失败不换快照
- [x] T023 实现 `DefaultSandboxWhitelistAdmin.java`，使 T022 转绿

## Phase 3：web（依赖 Phase 0）

- [x] T024 新建 `sableos-web/.../sandbox/dto/WhitelistEntryRequest.java`、`WhitelistUpdateRequest.java`、`WhitelistEntryResponse.java`
- [x] T025 先写 `SandboxWhitelistControllerTest.java`：**用 `MockMvcBuilders.standaloneSetup` 而非 `@WebMvcTest`**（sableos-web 无 `@SpringBootApplication`，与本模块既有 `GlobalExceptionHandlerTest` 同风格，照 oryxos 做法）——覆盖 201/200/400/404/409、信封 5 字段、400 不回显原始输入、`type` 不可变
- [x] T026 实现 `SandboxWhitelistController.java`（`@ConditionalOnProperty(name = "sableos.sandbox.admin.enabled", havingValue = "true", matchIfMissing = true)`），使 T025 转绿
- [x] T027 在 `GlobalExceptionHandler.java` 新增 3 个精确 handler（`WhitelistValidationException` → 400、`WhitelistEntryNotFoundException` → 404、`WhitelistDuplicateException` → 409），并补对应单测到 `GlobalExceptionHandlerTest.java`

## Phase 4：装配与导入（依赖 Phase 1、2、3）

- [x] T028 新建 `sableos-cli/.../runtime/SandboxWhitelistBootstrap.java`（`ApplicationRunner`）：标记表有行则跳过；否则严格模式下归一 + 单事务写入全部条目 + 标记行；任一非法 → 抛异常使启动失败；随后从 DB 重建策略并 `refresh`
- [x] T029 在 `SandboxWhitelistBootstrap` 内加启动 WARN（开关开启时提示 root 等价）
- [x] T030 在 `application.yaml` 增加 `sableos.sandbox.admin.enabled: true`
- [x] T031 写 `SandboxWhitelistSeedTest.java`：首次导入写入 N 条 + 标记；再次启动不重播；配置非法时启动失败；导入 0 条也写标记；并发启动只有一个成功

## Phase 5：集成与跨平台

- [x] T032 在 `sableos-boot` 建测试引导类 + `SandboxWhitelistApiIT.java`：CRUD 全链路；`ToolExecutor` 调 `read_file` 验证「加条目放行 / 删条目拒绝」；**「开关关闭时该路径返回 404」也在此验证**（`@ConditionalOnProperty` 是装配层行为，`standaloneSetup` 与 `@WebMvcTest` 都看不到，必须用真实上下文）
- [x] T033 在 `.github/workflows/ci.yml` 增加 `sandbox-compat` 矩阵 job（三平台、聚焦测试、`checkout@v5` / `setup-java@v6` / `cache: maven`）
- [x] T034 更新 `AGENTS.md` 第 10 章与本 feature 的报告：记录设计稿 §10 阶段 4 的 boot → cli 偏差、§9.1 的 `@WebMvcTest` → `standaloneSetup` 偏差

## 依赖图

```text
Phase 0 ──┬─→ Phase 1 ──┬─→ Phase 2 ──┐
          │             │             ├─→ Phase 4 ─→ Phase 5
          └─→ Phase 3 ──┴─────────────┘
```

## 与设计稿 §9 验收清单的比对

| 设计稿要求 | 对应任务 | 状态 |
| --- | --- | --- |
| `WhitelistRulesTest` | T018/T019 | ✅ |
| `SandboxWhitelistAdminTest` | T022/T023 | ✅ |
| `WhitelistSandboxTest` 扩展现有 29 例 | T021 | ✅ |
| `SandboxWhitelistEntryRepositoryTest` | T016/T017 | ✅ |
| `SandboxWhitelistControllerTest`（`@WebMvcTest`） | T025/T026 | ✅ |
| `SandboxWhitelistApiIT`（`@SpringBootTest` + MockMvc） | T032 | ✅ |
| CI 跨 OS 矩阵 | T033 | ✅ |
| V5 双轨迁移 | T010/T011 | ✅ |
| 两个实体 + 两个仓库 | T012~T015 | ✅ |
| `JpaSandboxWhitelistStore` | T017 | ✅ |
| `WhitelistRules` / `WhitelistPolicy` / `refresh` / `DefaultSandboxWhitelistAdmin` | T018~T023 | ✅ |
| `SandboxWhitelistController` + 异常映射 + 条件装配 | T024~T027 | ✅ |
| 装配 + 首次导入 + 启动 WARN | T028~T031 | ✅ |
| core 类型与异常 | T001~T009 | ✅ |
| `sableos.sandbox.admin.enabled` 配置键 | T030 | ✅ |

**设计稿未覆盖、本 tasks 新增**：
- T009（异常继承关系断言）— 来自 clarify 的 FR 强化
- T022 含「每类 1000 上限」— 来自 clarify 第 2 问
- T031（独立 `SandboxWhitelistSeedTest`）— 设计稿未单列，但 FR-004/FR-019 需要专门覆盖
- T034（偏差记录）— 设计稿 §10 写 boot，实际落 cli，需留痕

**发现的范围缺口**（需确认）：
- 设计稿 §9.1 提到「审计落库」作为集成测试守点，但设计稿 §11 明确「不做独立白名单审计表，本期只写日志」。两者矛盾。本 tasks 按 §11 执行：T022/T023 只断言日志存在，T032 不断言审计表。
