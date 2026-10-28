# Tasks: Sandbox 白名单校验

**Input**: Design documents from `specs/007-lesson24-sandbox/`

**Prerequisites**: [plan.md](./plan.md)、[spec.md](./spec.md)、[research.md](./research.md)、[data-model.md](./data-model.md)、[contracts/sandbox-whitelist.md](./contracts/sandbox-whitelist.md)

**Tests**: 本节 harness 明确要求测试先行/伴随实现，测试任务已包含。

**Organization**: 按用户故事分阶段；三个故事共享同一个 `WhitelistSandbox` 类，实现任务按规则组串行落地，测试按规则组独立可跑。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1 / US2 / US3）
- 所有任务均带精确文件路径

## 交付物映射（对照课件"本节交付物"）

| 课件交付物 | 本节任务 | 状态 |
|---|---|---|
| `Sandbox` 接口 / `SandboxAction` / `ActionType` / `SandboxViolationException` | 无（沿用第 20 节，逐字不改） | 已存在 |
| `WhitelistSandbox` | T004、T008、T011、T014 | 新增 |
| 三个 `@ConfigurationProperties`（file/shell/http） | T003 | 新增 |
| `application.yaml` 三键 | 无（已存在且注释写明 deny-all） | 已存在 |
| `FileTools`/`ShellTools`/`HttpTools`/`NotifyTools` 首行 `enforce` | 无（19/20 节已完成接线） | 已存在 |
| `WhitelistSandboxTest` | T006、T007、T010、T013 | 新增 |
| 四个 Tool 的拦截回归 | T007（FileTools 回归）、T012（ShellTools 回归）、T016（HttpTools + NotifyTools） | 三个既有 + 一个补建 |

---

## Phase 1: Setup（共享准备）

**Purpose**: 依赖、临时装配迁移、配置值对象

- [X] T001 在 `sableos-tool/pom.xml` 新增 `org.springframework.boot:spring-boot` 依赖（版本由父 POM 的 Boot BOM 管理，注释写明仅用于 `@ConfigurationProperties`，先例见 `sableos-provider/pom.xml`）；不新增第三方构件
- [X] T002 将 `sableos-tool/src/main/java/com/sableos/tool/sandbox/PermissiveSandbox.java` 复制到 `sableos-tool/src/test/java/com/sableos/tool/sandbox/PermissiveSandbox.java`（同包同 FQN、内容不变），确认前序 5 个测试类零改动仍可编译；主源码副本暂留至 T005，保证 Phase 1 检查点可构建
- [X] T003 [P] 在 `sableos-tool/src/main/java/com/sableos/tool/sandbox/` 新增三个属性记录：`FileSandboxProperties`（`prefix="file"`，`List<String> allowedPaths`）、`ShellSandboxProperties`（`prefix="shell"`，`List<String> allowedCommands`）、`HttpSandboxProperties`（`prefix="http"`，`List<String> allowedDomains`）；三个记录的紧凑构造器把 null 列表归一为空列表并过滤 null 条目

**Checkpoint**: 依赖与配置值对象就绪，测试源码已有放行替身；主源码副本随 T005 删除

---

## Phase 2: Foundational（阻塞性前置）

**Purpose**: 白名单实现骨架与装配替换；所有用户故事都依赖此阶段

**⚠️ CRITICAL**: 此阶段完成前，用户故事的规则实现无法运行

- [X] T004 新增 `sableos-tool/src/main/java/com/sableos/tool/sandbox/WhitelistSandbox.java`：`@Component`，构造注入三个属性记录；构造器将 `allowedPaths` 逐项 `toAbsolutePath().normalize()`（跳过空白条目；null 已由属性记录过滤）、`allowedCommands` 转 `Set.copyOf`、`allowedDomains` 统一小写（跳过空白；null 已由属性记录过滤）；三个列表均 null 安全（null → 空集合）；`enforce(SandboxAction)` 按 `ActionType` 路由到三个私有方法（`FILE_READ`/`FILE_WRITE` → `checkFilePath`，`SHELL_COMMAND` → `checkShellCommand`，`HTTP_REQUEST` → `checkHttpUrl`）；三个私有方法先以"抛 `SandboxViolationException`"的 deny-all 占位，后续故事逐组实现
- [X] T005 修改 `sableos-boot/src/main/java/com/sableos/boot/tool/ToolConfiguration.java`：删除 `Sandbox` 的 `@Bean`（返回 `PermissiveSandbox`）及对应 import；在类上添加 `@EnableConfigurationProperties({FileSandboxProperties.class, ShellSandboxProperties.class, HttpSandboxProperties.class})`；保留其余 bean 定义与注册顺序不变；随后删除主源码 `sableos-tool/src/main/java/com/sableos/tool/sandbox/PermissiveSandbox.java`（此时生产代码已无任何引用），测试源码副本保留
- [X] T006 新增 `sableos-tool/src/test/java/com/sableos/tool/sandbox/WhitelistSandboxTest.java` 测试骨架：提供构造白名单实例的私有帮助方法与三类规则的测试分组占位；方法名用英文驼峰，课件中文用例名放 `@DisplayName`
- [X] T007 修改 `sableos-core/src/main/java/com/sableos/core/tool/ToolExecutor.java`：仅把第 51-53 行过期的"24 节接线"TODO 注释改为说明"白名单校验在工具入口完成（20 节接线、24 节 `WhitelistSandbox` 实现），本层只负责把失败收敛为审计结果"；不改变任何代码逻辑

**Checkpoint**: 白名单 bean 可装配，装配测试可升起；三类规则仍为 deny-all 占位

---

## Phase 3: User Story 1 - 文件读写只能落在获准目录内 (Priority: P1) 🎯 MVP

**Goal**: 路径白名单按绝对化后的路径段比较；`..` 穿越不能爬出根；相对根可正确匹配；空配置全拒。

**Independent Test**: 配置一个临时目录为白名单根，分别对根内、根外、`..` 穿越、相似前缀、相对根发起 `FILE_READ`/`FILE_WRITE` 校验；根内放行、其余拒绝；空列表全拒。

### Tests for User Story 1

> **NOTE: 先写测试并确认失败，再实现规则**

- [X] T008 [US1] 在 `sableos-tool/src/test/java/com/sableos/tool/sandbox/WhitelistSandboxTest.java` 增加文件路径组用例：根内 `FILE_READ` 放行；根外拒绝；`root/../outside/secret.txt` 拒绝；`root-evil/x` 不被 `root` 误命中；目标等于根放行；相对根与相对目标按当前工作目录解析后可匹配；`FILE_WRITE` 与 `FILE_READ` 共用同一白名单；空 `allowed_paths` 全部拒绝
- [X] T009 [US1] 运行 `mvn -B -pl sableos-tool -am test -Dtest=WhitelistSandboxTest`，确认文件路径组用例因实现为占位而失败（红灯证据）

### Implementation for User Story 1

- [X] T010 [US1] 在 `sableos-tool/src/main/java/com/sableos/tool/sandbox/WhitelistSandbox.java` 实现 `checkFilePath`：目标与白名单根均 `toAbsolutePath().normalize()`；`target.startsWith(root)` 任一命中则放行；null/空白目标拒绝；失败消息 `路径不在白名单内: <rawPath>`
- [X] T011 [US1] 回归 `FileToolsTest`（既有"白名单外路径被拦且文件零接触"用例）与 `WhitelistSandboxTest`，确认全绿

**Checkpoint**: 路径规则独立可验证，文件工具回归保持绿

---

## Phase 4: User Story 2 - Shell 只能执行获准的可执行文件 (Priority: P2)

**Goal**: 取命令首 token（去除前导空白）与白名单精确、大小写敏感比对；空配置全拒。

**Independent Test**: 配置只允许 `ls`，对 `ls`、`rm`、`"  ls"`、`LS`、`ls -la` 发起 `SHELL_COMMAND` 校验；前两者与带参数者按首 token 判定，大小写变体拒绝，空列表全拒。

### Tests for User Story 2

- [X] T012 [US2] 在 `WhitelistSandboxTest.java` 增加 Shell 组用例：白名单内 `ls` 放行；白名单外 `rm` 拒绝；`"  ls"` 去前导空白后放行；`LS` 因大小写敏感拒绝；`ls -la` 只看首 token 放行；空 `allowed_commands` 全部拒绝
- [X] T013 [US2] 运行 `mvn -B -pl sableos-tool -am test -Dtest=WhitelistSandboxTest`，确认 Shell 组用例因占位实现而失败

### Implementation for User Story 2

- [X] T014 [US2] 在 `WhitelistSandbox.java` 实现 `checkShellCommand`：null/空白命令拒绝；`command.strip().split("\\s+")[0]` 取首 token；`allowedCommands.contains(firstToken)` 放行；失败消息 `命令不在白名单内: <firstToken>`
- [X] T015 [US2] 回归 `ShellToolsTest`（既有"白名单外可执行文件起进程前被拦"用例）与 `WhitelistSandboxTest`，确认全绿

**Checkpoint**: 命令规则独立可验证，Shell 工具回归保持绿

---

## Phase 5: User Story 3 - HTTP 请求与通知推送只能发往获准域名 (Priority: P3)

**Goal**: 解析 URL host，支持精确匹配与 `*.example.com` 子域通配；通配必须带点号边界；不可解析/无 host/非 HTTP(S) 拒绝；通知推送先校验后发送。

**Independent Test**: 配置 `*.example.com`，对 `api.example.com`、`evil-example.com`、白名单外域名、裸域、畸形 URL 发起校验；合法子域放行、形似域拒绝；通知工具用假 adapter 证明被拒时零发送，并用顺序断言确认 enforce 先于 send。

### Tests for User Story 3

- [X] T016 [US3] 在 `WhitelistSandboxTest.java` 增加 HTTP 组用例：精确域名放行；`*.example.com` 命中 `api.example.com` 与多级子域；`*.example.com` 不命中 `evil-example.com`；不命中裸域 `example.com`；白名单外域名拒绝；大小写差异不区分；`ftp://example.com/x` 拒绝；无 host / 不可解析 URL 拒绝；空 `allowed_domains` 全部拒绝
- [X] T017 [US3] 运行 `mvn -B -pl sableos-tool -am test -Dtest=WhitelistSandboxTest`，确认 HTTP 组用例因占位实现而失败
- [X] T018 [US3] 新增 `sableos-tool/src/test/java/com/sableos/tool/builtin/NotifyToolsTest.java`：无渠道时明确报错且零发送；缺省渠道取第一个；真实 `WhitelistSandbox`（空域名白名单）下 webhook 零发送；Mockito `InOrder` 断言 `sandbox.enforce` 先于 `adapter.send`，且 enforce 动作为 `HTTP_REQUEST`、目标为渠道 url

### Implementation for User Story 3

- [X] T019 [US3] 在 `WhitelistSandbox.java` 实现 `checkHttpUrl` 与 `matchesDomain`：null/空白 URL 拒绝；`URI.create` 解析异常转 `SandboxViolationException`；scheme 必须为 `http`/`https`（大小写不敏感）；host 非空并转小写；`*.` 模式按 `endsWith(pattern.substring(1))` 匹配、精确模式按 `equals`；失败消息 `域名不在白名单内: <host>`（无法解析时点名原始 URL）
- [X] T020 [US3] 回归 `HttpToolsTest`（既有"白名单外域名被拦且请求零发出"用例）、`WebhookNotifyAdapterTest`、`NotifyToolsTest` 与 `WhitelistSandboxTest`，确认全绿

**Checkpoint**: 三类规则与四个工具的接线全部可独立验证

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 全量门禁、跨节回归与验收材料

- [X] T021 [P] 运行 `mvn spotless:apply` 修正格式，再运行 `mvn -B -pl sableos-tool -am test`，确认 `sableos-tool` 全部测试类绿（含既有 12 个测试类）
- [X] T022 运行 `mvn clean verify`，确认 Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / PMD 与全部单测全绿（前序 16~22 节回归证据）
- [X] T023 [P] 按 [quickstart.md](./quickstart.md) 第 7 节执行 H4 与硬约束 grep 自查：涉外 IO 首行 `enforce`、通知 enforce 早于 send、无沙箱专用审计旁路、无明文 key、无 Reactor/CompletableFuture/自建线程池、无新增 `synchronized`、`ActionType` 恰好四值
- [X] T024 核对 `AGENTS.md` §10 的 SMTP 口径漂移记录与 `application.yaml` 的三键/deny-all 注释一致；确认无清单外 public 类型
- [X] T025 输出节级验收报告与变更总结（改动点 / 重点 review 清单 / 如何验证），列出剩余人工项；不执行 commit / push / package.sh

---

## Phase 7: 定稿补强（review 修复）

- [X] T026 [P] 三个属性记录在构造时过滤 null 条目（`List.copyOf(stream.filter(Objects::nonNull).toList())`），`WhitelistSandboxTest` 增加「配置列表含 null 条目」用例（R1）
- [X] T027 [P] `WhitelistSandboxTest` 三处拒绝用例补「异常消息点名被拒目标」断言（FR-007 / T1）
- [X] T028 新增 `SandboxViolationAuditTest`：真实 `WhitelistSandbox` 拒绝 → `ToolExecutor` 收敛为失败结果 → 审计 `success=false` 且 `error_message` 含被拒目标（SC-005 / C1）
- [X] T029 文档口径收尾：契约 / 数据模型 / 研究 / 任务 `trim()`→`strip()`（D1）；`application.yaml` 去掉 `sableos init` 生成白名单的过期承诺（D3）；T002/T005 顺序改为先复制、后删主源码，保证 Phase 1 可构建（O1）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始；T001 先加依赖；T002 只复制测试替身、主源码暂留，Phase 1 检查点保持可构建；T003 可与 T002 并行（不同文件）
- **Foundational (Phase 2)**: 依赖 Setup；T004 依赖 T003；T005 依赖 T004，完成装配替换后删除主源码副本；T006 依赖 T004；T007 独立
- **User Stories (Phase 3~5)**: 均依赖 Foundational；三个故事共享 `WhitelistSandbox` 文件，实现按 P1 → P2 → P3 串行；测试按组追加到同一测试类
- **Polish (Phase 6~7)**: 依赖三个故事全部完成；Phase 7 为验收后 review 修复，不改用户故事交付面

### Within Each User Story

- 测试先写并确认失败 → 实现规则 → 回归既有工具测试
- `checkFilePath` → `checkShellCommand` → `checkHttpUrl` 依次落地，避免同文件冲突

### Parallel Opportunities

- T003 三个属性记录是三个独立文件，可并行
- T007 与 T004/T005/T006 可并行（不同文件）
- T021 的格式修正与 T023 的 grep 自查可在全量门禁前并行准备
- 三个用户故事的测试编写理论上可并行，但因落在同一个 `WhitelistSandboxTest` 文件，实际按阶段串行追加

---

## Implementation Strategy

### MVP First（User Story 1）

1. 完成 Phase 1 + Phase 2
2. 完成 Phase 3（路径规则）
3. 跑 `WhitelistSandboxTest` 文件路径组 + `FileToolsTest`，独立验证
4. 此即最小可用防线：文件读写已被白名单约束

### Incremental Delivery

1. Setup + Foundational → 可装配
2. 加 US1 → 文件路径防线可演示
3. 加 US2 → Shell 防线可演示
4. 加 US3 → HTTP/通知防线可演示，四工具全部受控
5. 最后统一跑 `mvn clean verify` 与人工项清单

---

## Notes

- 本节不新增数据库表、不修改 Flyway 迁移、不新增 REST 端点、不新增第三方构件
- `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 与四个工具的 `enforce` 调用点均不改
- 测试方法名一律英文驼峰，课件中文名放 `@DisplayName`
- 校验失败不写日志，只抛异常交由 `ToolExecutor` 既有失败路径记录
- 不自动 commit / push / package.sh
