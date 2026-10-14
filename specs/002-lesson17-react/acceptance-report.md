# 第 17 节验收报告：ReAct 循环（`002-lesson17-react`）

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Tasks**: [tasks.md](./tasks.md)

**报告日期**: 2026-10-14 | **补做说明**: 本节走完 `implement` 后未留 `/speckit-analyze` 结果与节级验收报告（宪法原则 IX 与 `sableos-lesson-dev` skill 第 7 步要求），本报告补做并留证。

> 本文件是**项目级新增留证件**（非 Spec-Kit 标准产物）；`/speckit-analyze` 本身是只读命令、默认不落文件，本报告把其结论与 H6 六项证据固化下来，供审计与后续节对照。

---

## 一、`/speckit-analyze` 一致性核对（补做，2026-10-14）

**范围**: `spec.md` + `plan.md` + `tasks.md` + `.specify/memory/constitution.md` + 实现代码。

### 1.1 发现清单

| ID | 类别 | 严重度 | 位置 | 摘要 | 建议 |
| --- | --- | --- | --- | --- | --- |
| S1 | 状态不一致 | ~~MEDIUM~~ 已修复 | `spec.md:7` | `Status` 原为模板默认 `Draft`，与已走完 clarify / plan / checklist / tasks / implement 的真实进度不符 | 已于 2026-10-14 置为 `Implemented`（本报告补做同日） |
| A1 | 格式 | LOW | `spec.md:9` | `Input` 段为单行超长文本（spec-kit 生成原样） | 不改（保持与生成器一致） |
| I1 | 术语 | LOW | `spec.md` / `plan.md` | spec 用「工具表 / 工具集合」，plan 用具名契约 `ToolCatalog` | 不改（spec 层不引类名是有意约束，plan 已具名） |

严重度统计：CRITICAL 0 ｜ HIGH 0 ｜ MEDIUM 0（S1 已于 2026-10-14 修复）｜ LOW 2。

### 1.2 需求覆盖表（FR / SC → 任务）

| Requirement | Has Task? | Task IDs |
| --- | --- | --- |
| FR-001 组装+调用，无工具调用即收尾 | ✅ | T011, T013 |
| FR-002 逐个执行工具并回填；执行权唯一在 ToolExecutor | ✅ | T012, T013 |
| FR-003 一轮多工具按顺序执行 | ✅ | T013 |
| FR-004 迭代上限（默认 10、Profile 可覆盖）+ 固定终止文案 | ✅ | T013, T014 |
| FR-005 每轮累积进会话 | ✅ | T004, T013 |
| FR-006 固定顺序组装 + 最近 N 条（默认 20） | ✅ | T011, T015 |
| FR-007 截断只作用于历史 | ✅ | T015 |
| FR-008 按名解析工具，未注册即失败 | ✅ | T012 |
| FR-009 白名单校验调用位（留 24 节接线）+ 不吞异常 | ✅ | T012 |
| FR-010 每次工具调用成败均落 `tool_invocations` | ✅ | T016–T020 |
| FR-011 统一入口 + `finally` 清理线程级上下文 | ✅ | T022 |
| FR-012 线程级当前 Agent 上下文 | ✅ | T009, T022 |
| FR-013 每次现读不缓存 | ✅ | T010, T023 |
| FR-014 Skill 缺失报错 / Bootstrap 缺失 WARN | ✅ | T023 |
| FR-015 会话保存在进程内承载 | ✅ | T021 |
| SC-001 恰好转满上限次数 | ✅ | T013, T014 |
| SC-002 会话历史完整按序保留 | ✅ | T013 |
| SC-003 只含最近 N 条且系统提示在 | ✅ | T015 |
| SC-004 审计完整要素、失败带原因 | ✅ | T016–T020 |
| SC-005 异常后上下文 100% 清空 | ✅ | T022 |
| SC-006 改文件后立即生效、无缓存 | ✅ | T023 |
| SC-007 换模型 / 上限只改 Profile | ✅ | T014 |

**覆盖度: 22 / 22 = 100%**（无零覆盖需求）。未映射任务：无（T001 依赖核对、T025–T027 门禁与留证属流程性任务，可接受）。

### 1.3 宪章对齐（原则 I–IX）

plan.md 的 *Constitution Check* 已逐条判 ✅；本次独立复核确认：I 目标字节码 21、II `ReActLoop` 自实现（core 不含 Spring AI 类型）、III 只消费 `ProviderService` 且 `ToolCallRequest` 原样交回、IV 未触碰 Provider 路由、V `ToolCatalog` 只落前向契约未新增模块、VI `V2__tool_invocations.sql` 双轨 + Jpa 审计 + `trace_id`、VII 仅留 Sandbox 调用位（`ToolExecutor` 内 `// TODO(24 节)`）、VIII 无并行工具/互托/流式/压缩/Policy、IX 见第二节证据。**无宪章冲突。**

### 1.4 指标

- 需求总数 22（15 FR + 7 SC）｜ 任务总数 27（T001–T027）
- 覆盖度 100% ｜ 歧义 0（阻塞性）｜ 重复 0 ｜ CRITICAL 0

### 1.5 Next Actions

无 CRITICAL/HIGH，可判定 implement 收敛；唯一 MEDIUM（S1 状态字段）待项目方确认后由人工改。

---

## 二、节级验收六项证据（H6）

### 证据 1：`mvn clean verify` 全绿（硬门禁）

| 项 | 结果 |
| --- | --- |
| 命令 | `mvn -B clean verify`（`-Dowasp.skip=true` 默认跳过本地 depcheck，CI 独立 job 强制跑） |
| 结果 | **BUILD SUCCESS**，10 个模块全 `SUCCESS`（core / provider / memory / tool / channel-cli / web / storage / cli / boot + 聚合 POM） |
| 测试 | `Tests run: 163, Failures: 0, Errors: 0, Skipped: 0`（25 个测试类） |
| Spotless / Checkstyle | 通过（绑 `validate`） |
| 阿里 P3C（PMD 6.55，8 个 `ali-*` ruleset） | 通过 |
| SpotBugs（effort Max / threshold Low）+ FindSecBugs | `BugInstance size is 0`，`Error size is 0` |

### 证据 2：课件 harness 映射（七个测试类，逐个对号）

| 测试类 | 位置 | `@Test` 数 |
| --- | --- | --- |
| `ReActLoopTest` | `sableos-core/.../core/react/` | 6 |
| `PromptBuilderTest` | `sableos-core/.../core/prompt/` | 6 |
| `ToolExecutorTest` | `sableos-core/.../core/tool/` | 6 |
| `AgentServiceTest` | `sableos-core/.../core/agent/` | 4 |
| `ContextLoaderTest` | `sableos-core/.../core/context/` | 4 |
| `ToolInvocationRepositoryTest` | `sableos-storage/.../storage/` | 3 |
| `ReactAssemblyTest` | `sableos-boot/.../boot/` | 3 |

**11 个关键回归测试**（方法名英文，`@DisplayName` 保课件中文）逐条落地并全绿：

1. `ReActLoopTest.run_modelAlwaysRequestsTools_stopsExactlyAtMaxIterations`
2. `ReActLoopTest.run_profileOverridesMaxIterations_usesDeclaredValue`
3. `PromptBuilderTest.build_historyExceedsLimit_keepsOnlyLatestNMessages`
4. `ToolExecutorTest.execute_success_auditsSuccessTrue`
5. `ToolExecutorTest.execute_toolThrows_auditsFailureWithReason`
6. `AgentServiceTest.process_duringRun_profileContextHoldsCurrentProfile`
7. `AgentServiceTest.process_loopThrows_contextStillCleared`
8. `AgentServiceTest.process_afterLoop_savesSession`
9. `ContextLoaderTest.systemPrompt_fileChanged_reflectsNewContentImmediately`
10. `ContextLoaderTest.systemPrompt_missingSkillBinding_throwsNamed`
11. `ContextLoaderTest.systemPrompt_missingBootstrap_logsWarn`

### 证据 3：交付物存在性核对（20 / 20）

- **core**: `session/{MessageRole, SessionMessage, Session, SessionStore}`、`tool/{ToolCatalog, ToolInvocationAuditor, ToolExecutor}`、`memory/MemoryProvider`、`profile/ProfileContext`、`context/ContextLoader`、`prompt/PromptBuilder`、`react/ReActLoop`、`agent/AgentService` — 全在。
- **storage**: `ToolInvocation`、`ToolInvocationRepository`、`JpaToolInvocationAuditor`、`InMemorySessionStore`、`db/migration/{sqlite,postgresql}/V2__tool_invocations.sql` — 全在。
- **boot**: `boot/react/ReactConfiguration.java` — 在。

### 证据 4：前序节回归（跨节契约）

`mvn clean verify` 为**全量**执行：16 节 `ProviderServiceTest` / `ProviderChatModelFactoryTest` / `ProvidersPropertiesTest` / `ToolSchemaAdapterTest` / `LlmCallRepositoryTest` / `ProviderAssemblyTest` 与工程地基的 trace / web / observability 测试**全部绿**，无回归。（`ProviderService.chat(String, Profile, ProviderRequest)` 与本节消费口径一致。）

### 证据 5：H4 六条 + AGENTS §5.1/§5.2 自查

| # | 不变量 | 结果 |
| --- | --- | --- |
| ① | 涉外 IO 首行过 `Sandbox.enforce` | ✅ 本节留调用位（`ToolExecutor` 内 `// TODO(24 节)`，Sandbox 类型五件归 20 节、实现归 24 节） |
| ② | LLM 成败落 `llm_calls`、工具成败落 `tool_invocations`（含 `trace_id`） | ✅ 16 节 `llm_calls` + 本节 `tool_invocations`（`V2`） |
| ③ | grep 无明文 key | ✅ `grep -rn "sk-"` 零命中 |
| ④ | `session_id` 单点拼装 | ✅ 本节未散落拼装（真身归 18 节） |
| ⑤ | 无 Reactor / `CompletableFuture` / 自建线程池 | ✅ 仅注释提及，无实调用 |
| ⑥ | 无 Spring AI 自动工具执行路径 | ✅ core 不含 Spring AI 类型，工具调度唯一由 `ReActLoop` + `ToolExecutor` 控制 |
| 附 | `TraceIds` 非 `UUID.randomUUID()`；`synchronized` 内无阻塞（虚拟线程不钉住）；全容器唯一 `TaskDecorator`；CRLF 净化保 `replaceAll("[\\r\\n]", "_")` 形态 | ✅ |
| 附 | 测试方法名全英文 | ✅（无中文方法名） |

### 证据 6：变更留痕

- 本节产物见 `git status --short`（`specs/002-lesson17-react/` 及 core / storage / boot 的 `??` 新增），同步时机由项目方决定（本节不自动 commit）。
- 验收结论：**harness 已判卷，机器门禁全绿**。

---

## 三、剩余人工项（待人工过）

1. **真模型冒烟**：配真实 API key（环境变量）后跑一次端到端，验证真实 Provider 下的多轮工具循环。
2. **本地起服务**（quickstart 场景 D）：`java -jar sableos-boot/target/sableos-boot-0.1.0-SNAPSHOT.jar` 零 key 启动确认（场景 A–C 已由本节 `mvn verify` 覆盖）。
3. **审计表肉眼抽查**（quickstart 场景 B 留证）：
   `SELECT tool_name, success, error_message, trace_id, duration_ms FROM tool_invocations ORDER BY id DESC LIMIT 3;`
   —— `trace_id` 在 18 / 25 节入口接入前可能为 null，属预期。
4. ~~**S1 决议**：是否将 `spec.md` 的 `Status` 由 `Draft` 改为已实现。~~ 已于 2026-10-14 置为 `Implemented`。
