# Phase 0 Research: 人推链路串联与对账 harness

**Feature**: `011-lesson27-human-trigger-flow` | **Date**: 2026-10-31

本文件记录实现前需要定死、且课件与技术方案没有逐字给出答案的技术决策。每条给出 Decision / Rationale / Alternatives considered。

## D-01 会话列表的 status 过滤放在哪一层

**Decision**: 过滤、排序、截断三件事一起下推到存储层：`SessionRepository.findByStatus(status, Pageable)`，`PageRequest.of(0, limit, Sort.desc("lastActiveAt"))`；无 status 时用 `findAll(page)`。`SessionManager` 保留单参默认方法 `listRecent(int)` 转发到 `listRecent(limit, null)`。

**Rationale**: 先取前 N 条再在内存里筛状态，会让 `?status=archived` 的结果数量被未命中的新行吃掉——归档会话越多越明显，是典型的"数据量小时看不出、上线后才炸"的语义 bug。同时 `SessionManager` 是 core 接口，单参重载保留为 default 方法可让既有调用方（含测试替身）零改动。

**Alternatives considered**: ① 只在 Controller 内存过滤（初版做法）——简单但有上述缺陷，已推翻；② 给 `SessionManager` 加单独的 `listByStatus` 方法——会与 `listRecent` 形成两套排序口径，容易漂移；③ 引入分页游标——核心阶段会话量小，`Pageable` 已够。

**修订记录（2026-10-31 复核）**: 初版实现确为 `findAll()` + 内存 `filter`，复核时按本条改为下推，并补了"不被未命中行挤掉"的回归断言。

## D-02 mock provider 挂在哪张表上

**Decision**: 作为 `ProvidersProperties` 里的第三类 `type`（`TYPE_MOCK`）挂在**显式映射表**名下，与 `openai` / `dashscope` 同级；`ProviderChatModelFactory` 见到 mock 直接 `new MockChatModel()`，不建 HTTP 客户端；`ProvidersProperties.validate()` 对 mock 跳过 api-key / base-url 校验；`HttpProviderHealthProbe` 对 mock 直接判可达。

**Rationale**: 宪法 III（Provider 显式映射，禁止类型扫描）要求所有 provider 都经映射表登记；把 mock 做成第四种"特例注入"会绕过这条不变量，也让 `/info` 的连通状态少一项。健康探测必须特判，否则 mock 无 base-url 会回落到 DashScope 默认地址去探测一个无关端点。

**Alternatives considered**: ① mock 作为测试专用 `ChatModel` bean 注入——会让"只有模型是假的"这句话失真，且 `serve` 起真实进程时无法演示；② 给 mock 填一个假 base-url——探测会真发 HTTP，语义错。

## D-03 MockChatModel 的判定方式

**Decision**: 完全**无实例状态**，只看 prompt 文本：含 `Tool[save_memory]:` 标记即返回固定最终答复；否则解析最后一条 `User:` 行、剥掉口语化的"记住："前缀，发一次 `save_memory` 工具调用。

**Rationale**: 有状态的"第几轮"计数器在同一批服务跑多个会话时会串味，且并发不安全。按 prompt 内容判定是纯函数，天然可并发、行为确定。标记串必须与 `PromptBuilder` 渲染工具结果的前缀严格一致（`Tool[<name>]:` 后跟空格），否则第二轮永远判不出来、ReAct 转满最大轮数。

**Alternatives considered**: ① 用 `AtomicInteger` 计数轮次——多会话互相干扰；② 用线程局部状态——虚拟线程下不可靠，且违背"无状态"。

## D-04 工作区根的三级优先级

**Decision**: `Workspace.root()` 依次解析：系统属性 `sableos.root` → 环境变量 `WORKSPACE_ROOT` → 默认 `.sableos`。Spring 侧统一 `@Value("${workspace.root:.sableos}")`，而 `application.yaml` 里 `workspace.root: ${sableos.root:.sableos}`。

**Rationale**: 整机测试需要把 agents / skills / mcp_servers.yaml / SQLite 一起指到临时目录——只改 SQLite 而不改 agents 会导致 Profile 加载失败；只改 agents 不改 SQLite 会污染真实库。用系统属性而非环境变量，是因为 surefire 里 `-D` 比改环境变量更直接。优先级让显式的系统属性胜出，同时保留既有的 `WORKSPACE_ROOT` 兼容。

**Alternatives considered**: ① 只改环境变量——测试里要改进程环境，不便；② 每个配置类各自读属性——易漂移，故统一改为读 `workspace.root`。

## D-05 三层 harness 的分工

**Decision**:

- `MockProviderFlowTest`：**手工装配**（不起 Spring），几秒跑完，断言 ReAct 两轮 / 工具恰一次 / `MEMORY.md` 真写入 / 会话完整 / 审计各一条，并复用同一批服务起 `/sessions`、`/memory`、`/tools` 三个端点。
- `MockAgentE2ETest`：`@SpringBootTest` **真 HTTP 端口 + 临时 SQLite**，完全模拟人手动点一遍。
- `HumanTriggerFlowIT`：`@Tag("integration")`，**真 key、真网络**，覆盖三支柱 + 三类失败记账 + CLI/REST 三面同源；无 key 时 `assumeTrue` 跳过。

**Rationale**: 三者分别回答"逻辑对不对"（快、可定位）、"装配对不对"（真容器、真端口）、"真模型下还成不成立"（外部依赖）。只有第二层进日常门禁才能保证"整台机器起得来"这件事不被静默破坏；第一层保证定位速度快。

**Alternatives considered**: ① 只留集成测试——CI 无 key 就完全失去保护；② 只留手工装配测试——装配层（JPA 扫描、SPA 回落、配置绑定）不被覆盖。

## D-06 surefire 的 excludedGroups 为什么要改成项目属性

**Decision**: 父 POM 定义 `<excludedGroups>integration</excludedGroups>` 项目属性，surefire 用 `<excludedGroups>${excludedGroups}</excludedGroups>`。

**Rationale**: 直接写死 `integration` 时，命令行 `-DexcludedGroups=` 无法覆盖插件配置里已存在的值，导致文档里"手动跑集成测试"的命令实际什么也不跑。改经项目属性中转后，`mvn test -DexcludedGroups= -Dgroups=integration -Dtest=<IT>` 真正可用（本次已实测通过）。

**Alternatives considered**: ① 用 profile 切分——要维护两套插件配置；② 让集成测试不受 tag 控制——会让 `mvn verify` 需要真 key，破坏无 key 可构建。
