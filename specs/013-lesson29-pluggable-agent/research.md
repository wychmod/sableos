# Research: 插件化 Agent

**Feature**: `specs/013-lesson29-pluggable-agent` | **Date**: 2026-10-31

> 本节是改造课而非新建课：底座已具备 `AgentLoader` / `ContextLoader` / `ProfileRegistry` / `AgentScheduler`。研究重点在**冲突裁定**与**改造边界**，而非技术选型。

## D-01 示例 Agent 目录的落位

**Decision**: 放 `sableos-core/src/test/resources/agents/daily-reconcile/`，同时充当 harness 夹具与手动参照物。

**Rationale**:
1. `.sableos/` 已在 `.gitignore` 第 10 行排除，写进去不会入库；即便强行入库也会污染实际运行工作区（该目录下有 `sableos.db` 等运行态文件）。
2. 参照仓库（oryxos）同样**不跟踪任何示例 Agent 目录**（`git ls-files | grep agents/` 无结果），示例只作为测试夹具。
3. 放在 `src/test/resources` 可被 `@TempDir` 复制后作为真实输入驱动 `AgentLoader`，一份文件两用：既验机制，又是手动路径的抄写范本。

**Alternatives considered**:
- `docs/examples/`：与测试脱节，改示例不会让测试红；且 `docs/` 是文档语料区，混入可执行脚本违反 AGENTS §3 的目录分工。
- 根 `.sableos/agents/`：见上，gitignore + 污染运行环境。

## D-02 未注册工具：告警而非丢弃

**Decision**: `AgentLoader` 构造期接收"已注册工具名集合"，派生后逐个比对，命中未注册能力记 WARN 但仍完成登记。

**Rationale**: FR-005 与课件「校验：`tools` 里引用底座未注册的能力 → 加载告警」一致；参照仓库同一口径（`AgentLoader.warnUnknownTools`：`LOG.warn("Agent {} 引用了未注册的能力: {}")` 后照常返回 profile）。工具可能由 MCP 在启动后晚于 Agent 加载才注册，此时丢弃 Agent 会造成"工具到齐了但 Agent 没了"的误伤。

**Alternatives considered**: 直接拒绝该 Agent —— 与课件明确要求的"告警但仍登记"相反。

## D-03 `ProfileRegistry.remove` 的返回语义

**Decision**: `boolean remove(String name)`，存在并移除返回 `true`，否则 `false`。`get` 保持返回 `null` 的既有语义不变。

**Rationale**: 参照仓库同签名（`public boolean remove(String name)`）。`get` 改为 `Optional` 会破坏既有全部调用方（`AgentService`、`AgentScheduler.reconstruct`、`MultiAgentIsolationTest`），属越界改造。`remove` 的布尔返回直接服务第 30 节的"注销成功与否"。

**Alternatives considered**: `void remove(...)` —— 第 30 节无法区分"移除成功"与"本就不存在"。

## D-04 `skills/` 下普通 `.md` 会导致整个 Agent 构造失败（实测确认）

**Decision**: 示例 Agent 的 `skills/report-format.md` **按软连接形态**落地为指向公共 Skill 根的相对软连接；示例文档里同时标注这条约束。课件 §1.3 的"在 `skills/` 下放普通 `.md`"形态**不采用**。

**Rationale**: 用 JDK 21 实测 `ContextLoader.systemPrompt` 对两种形态的反应：

```
情形 A（skills/report-format.md 为普通文件）：
  IllegalArgumentException: Skill 绑定越界（目标不在 Skill 根目录内）：report-format.md

情形 B（AGENT.md 同级放 REFERENCE.md）：
  正常返回，且 REFERENCE.md 内容【不进】system prompt（符合渐进式披露预期）
```

`ContextLoader` 对 `skills/` 下**每个条目**都调 `toRealPath()` 并校验落在公共 Skill 根内；普通文件不在 Skill 根下，直接判越界并抛 `IllegalArgumentException`。若照课件形态交付示例，`daily-reconcile` 每次触发都会抛异常——Agent 完全跑不起来。

权威顺序（AGENTS §4）：**技术方案 §11.1 明确"`skills/` 只放指向公共 Skill 实体的相对软连接，软连接集合是唯一绑定真相源，不再使用 frontmatter `skills:`"**。课件在此点上与之一致（§1.2 也说"skill 收进 Agent 内部当组成"），但 §1.3 的示例代码块用了普通文件写法，属示例笔误。按技术方案执行。

**Alternatives considered**:
- 放宽 `ContextLoader` 允许普通 `.md` —— 会摧毁"软连接集合是唯一绑定真相源"这条已冻结的项目约定（AGENTS §6），且 17 节的 `ContextLoaderTest` 已把越界拒绝钉死。
- 示例不含 `skills/` —— 与课件"四部分俱全"的交付物要求冲突。

## D-05 句柄表命名与并发

**Decision**: 新增 `ConcurrentMap<Long, ScheduledFuture<?>> scheduledTasks`（键为 `scheduleId`），与既有 `registeredTasks`（键 `scheduleId` → 含 profile/schedule 的 record）并存；提供 `hasScheduledTask(long scheduleId)`。

**Rationale**: 课件点名"新增一张 `Map<String, ScheduledFuture<?>>` 句柄表"；参照仓库同名字段 `scheduledTasks` 与同名查询 `hasScheduledTask`。**键类型取 `long` 而非 `String`**：SableOS 的 `scheduleId` 是自增 `long`（第 28 节定的），改成 `String` 要额外拼接、且与既有 `registeredTasks`、任务锁键不一致。字段名保真、键类型按本仓库既有字面量。

**Alternatives considered**: 复用 `registeredTasks` 并从 record 里取 `future()` —— 该 map 同时承载 profile/schedule 与 future，第 30 节注销时需要"仅凭 `scheduleId` 就能取消"的独立查表；且 record 是不可变的，替换句柄要重建整个 record。

## D-06 重复登记的取消语义

**Decision**: `registerProfile` 对每条 schedule 先 `reconcile` 拿 `scheduleId`，再 `scheduledTasks.put(scheduleId, newFuture)`；若 `put` 返回非 null 的旧句柄且与新句柄不同，则 `previous.cancel(false)`。

**Rationale**: FR-009 要求"不得泄漏句柄、不得双跑"。`cancel(false)` 不中断正在执行的那一次（那是既有语义：正在跑的任务允许跑完），只阻止后续触发。参照仓库同实现（`previous.cancel(false)`）。

**Alternatives considered**: `cancel(true)` —— 会中断正在写的会话，可能留下半条历史。

## D-07 运行时校验与启动扫描的一致性

**Decision**: 运行期登记不新增校验入口，直接调 `AgentLoader.deriveProfile`；异常由其内部抛出，外部（第 30 节 API 层）只做转换，不做再校验。

**Rationale**: FR-003 要求"同一异常类型与同一消息"。校验只有一处实现，才可能真正一致；任何"API 层再校验一遍"都会造成两条口径漂移。`ProfileRegistry` 只负责存取（类注释写明"非法配置的校验在上游完成"）。

**Alternatives considered**: `ProfileRegistry.register` 内部校验 —— 会让"注册表"承担校验职责，且启动扫描已在校验后才 register，形成双份校验。

## D-08 装配方向：boot 把工具名单注入 core

**Decision**: `AgentConfiguration` 在构造 `AgentLoader` 时，从 `ToolRegistry` 取全部工具名传入；`AgentLoader` 位于 core，只接收 `Set<String>`，**不 import 任何 tool 模块类型**。

**Rationale**: 依赖方向必须是 `boot → tool → core`（ArchitectureTest 红线：core 不得依赖任何其他模块）。传字符串集合而非 `ToolRegistry` 对象，core 就不会接触 tool 模块的类。

**Alternatives considered**: `AgentLoader` 直接注入 `ToolRegistry` —— 会形成 core → tool 反向依赖，ArchitectureTest 立即失败。

## D-09 `ContextLoader` 不改

**Decision**: 本节不动 `ContextLoader`。

**Rationale**: 实测确认它已满足 FR-010/FR-011：正文进 prompt、`REFERENCE.md` 与 `scripts/` 不进 prompt、每次现读无缓存。课件把「`ContextLoader` 注入 Agent 正文」列为交付物，但该能力 17 节已落地（`ContextLoaderTest` 已钉住无缓存回归）。重写会造成既有 29 例回归风险，且无收益。

**Alternatives considered**: 为对齐课件措辞而重构 —— 违反「不为对齐措辞改动已通过的前序实现」。
