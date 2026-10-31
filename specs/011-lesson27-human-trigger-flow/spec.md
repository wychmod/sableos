# Feature Specification: 全流程串联（一）——打通 Agent 人推主流程

**Feature Branch**: `011-lesson27-human-trigger-flow`

**Created**: 2026-10-31

**Status**: Implemented (2026-10-31)

**Input**: User description: "第27节需求：全流程串联（一）打通 Agent 主流程——chat / REST 的一次完整对话"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 一句真实的问话走通"想 → 查 → 答 → 记账" (Priority: P1)

用户在 `sableos chat` 里问一句需要外部信息的话（如"今天北京天气怎么样，穿什么合适"）。系统按 ReAct 循环先让模型决定调工具、再执行工具拿数据、最后组织答复，把完整对话写进会话表；同时把两次模型调用与一次工具调用分别记进审计表。

**Why this priority**: 这是本节存在的理由——前面各节每个模块单独都能跑，但"每个模块单测全绿"不等于"整台机器能转起来"。只要有一处交接对不上，链路就断在接缝处。

**Independent Test**: 用 mock provider 走一次确定性链路（不联网、不烧 key），断言 ReAct 恰两轮、工具恰一次、会话历史完整、审计不多不少。

**Acceptance Scenarios**:

1. **Given** 工作区里有声明了 `provider.name: mock` 与 `tools: [save_memory]` 的 Agent，**When** 通过 REST 建会话并发送一条"记住…"消息，**Then** 返回非空答复，会话历史恰好 4 条（user / assistant / tool / assistant），工具结果那条的角色为 TOOL 且 `success=true`。
2. **Given** 上述对话已完成，**When** 查审计表，**Then** `llm_calls` 恰 2 条且全部成功，`tool_invocations` 恰 1 条 `save_memory` 且成功——不多也不少。
3. **Given** 使用真实模型与真实 HTTP 工具，**When** 走完一次天气问答，**Then** 同样满足"两次模型调用 + 一次工具调用 + 四条历史"的对账口径。

---

### User Story 2 - 会话列表可查，管理台"会话"页不再空着 (Priority: P1)

运营人员想在不记得会话 ID 的前提下找到刚才那次对话，于是在管理台的"会话"页里浏览，或直接调 `GET /api/v1/sessions`。列表按最后活跃时间倒序返回摘要，不带完整对话正文。

**Why this priority**: 对账与"三面同源"要能自动验证，前提是每一样痕迹都有 HTTP 接口查得到。26 节只做了按 ID 查单条，缺列表就没有"三面同源"的第三面。

**Independent Test**: 造两条不同状态的会话，断言列表按活跃时间倒序、摘要含 `messageCount` 不含 `messages`；`?status=` 过滤在存储侧生效，不被更新的未命中行挤掉。

**Acceptance Scenarios**:

1. **Given** 已有一条会话，**When** `GET /api/v1/sessions`，**Then** 该会话出现在列表里，摘要字段（id / profile 名 / 渠道 / 用户 / 状态 / 创建时间 / 最后活跃时间 / 消息条数）对得上，且响应里没有对话正文。
2. **Given** 存在活跃与归档两种会话，**When** `GET /api/v1/sessions?status=archived`，**Then** 只返回归档会话；即使有更多更新的活跃会话排在前面，归档结果数量也不因此减少。
3. **Given** `limit <= 0`，**When** 请求列表，**Then** 返回空列表，而不是把"取 0 条"解释成"取全部"。
4. **Given** 管理台已构建并随 jar 托管，**When** 打开 `/admin/sessions`，**Then** 刚才那次对话以摘要形式列出。

---

### User Story 3 - 记忆与工具都查得到，且跨会话可用 (Priority: P2)

用户让 Agent "记住：我在北京，怕冷"。之后换一个新会话问"我在哪个城市"，Agent 应当用上这条长期记忆。

**Why this priority**: 长期记忆是 Agent 区别于一次性 chatbot 的关键；只有"写得进 + 读得出 + 跨会话可用"三件事同时成立，记忆才算真的接上。

**Independent Test**: 写一条记忆后查 `GET /api/v1/memory` 断言内容包含该事实；再开新会话提问，断言答复用上该事实。

**Acceptance Scenarios**:

1. **Given** 一条触发 `save_memory` 的消息已处理完，**When** `GET /api/v1/memory?profile=...`，**Then** 返回内容包含刚写入的事实。
2. **Given** 记忆已写入，**When** 用同一 Profile 开一个新会话提问，**Then** 答复用上了这条记忆。
3. **Given** 系统已装配工具注册表，**When** `GET /api/v1/tools`，**Then** 返回的清单数量大于 0、包含关键工具，且覆盖每个 Profile 声明的工具集。

---

### User Story 4 - 失败也要记账，且三个入口看到同一份数据 (Priority: P2)

沙箱拦下、工具抛异常、Provider 连不上——这三类失败发生时，系统不崩，且审计表里各留一条 `success=false`。同一次对话从命令行、REST、管理台看，是同一份数据。

**Why this priority**: 成功路径的审计一般不会漏，最容易漏的是失败路径；而"三面同源"是"两个入口共用同一个引擎"这句口号的唯一可验证形式。

**Independent Test**: 各造一次失败，断言审计表里有对应 `success=false` 记录且调用方拿到失败结果；再用 CLI 写一条会话，从 REST 读回来核对一致。

**Acceptance Scenarios**:

1. **Given** 白名单未放行的目标，**When** 通过工具执行器发起请求，**Then** 返回失败结果，`tool_invocations` 留一条 `success=false`。
2. **Given** 工具内部抛异常，**When** 同上，**Then** 同样留一条 `success=false`，异常不吞。
3. **Given** Provider 不可达，**When** 发起一次模型调用，**Then** 抛 `ProviderFailureException`，`llm_calls` 留一条 `success=false`。
4. **Given** CLI 已写入一条会话，**When** 从 `GET /api/v1/sessions/{id}` 与列表读回，**Then** 与 CLI 侧是同一份会话与同一份记忆。

---

### Edge Cases

- 会话列表 `limit <= 0` → 返回空列表，不是全量。
- `?status=` 传空白串 → 按不过滤处理。
- mock provider 的 `HttpProviderHealthProbe`：无网络端点，直接判可达，不得回落到 DashScope 默认地址探测。
- 工作区根被 `sableos.root` 覆盖时，agents / skills / mcp_servers.yaml / SQLite 必须一起跟随，不能只改一半。
- 集成测试在无 `DEEPSEEK_API_KEY` 时必须跳过而不是失败。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 新增 `GET /api/v1/sessions`，按 `lastActiveAt` 倒序返回最近 N 条（默认 100）**摘要**，摘要含 id / profileName / channel / userId / status / createdAt / lastActiveAt / messageCount，**不含**完整对话正文。
- **FR-002**: 列表 MUST 支持可选 `?status=` 过滤，且**过滤、排序、截断三者都在存储侧完成**——不得先取 N 条再在内存里筛（否则 `status=archived` 会被更新的未命中行挤掉名额）。
- **FR-003**: `SessionManager` MUST 暴露 `listRecent(int limit)` 与 `listRecent(int limit, String status)`；`limit <= 0` 返回空列表；空白 `status` 等价于不过滤。
- **FR-004**: 系统 MUST 提供第三类 provider `mock`：挂显式映射表名下、不需要 api-key / base-url、不连模型；配置校验放行、健康探测直接判可达。
- **FR-005**: `MockChatModel` MUST 无实例状态（可安全并发），按 prompt 文本驱动：出现工具结果标记即返回最终答复，否则发一次 `save_memory` 工具调用；除模型外 ReAct / 工具 / 记忆 / 会话 / 审计全走真实实现。
- **FR-006**: 工作区根 MUST 支持系统属性 `sableos.root` 覆盖（默认 `.sableos`），agents / skills / mcp_servers.yaml / SQLite 一起跟随；轻命令 `Workspace.root()` 按 `sableos.root` → `WORKSPACE_ROOT` → `.sableos` 依次解析。
- **FR-007**: 测试基础设施 MUST 允许用 `-DexcludedGroups=` 覆盖默认排除的 `integration` 分组，使集成测试的手动命令真正可执行。
- **FR-008**: README MUST 写清管理台的构建链（前端构建 → 打包 → `serve` → 三面验收），并显式说明"`mvn package` 不会重建前端、`static/admin/**` 是入库产物"。

### Key Entities

- **Session（会话）**：`session_id = channel:user:profile`，含 status（active / archived）、createdAt、lastActiveAt、消息列表。
- **SessionSummaryView（会话摘要）**：列表视图，只有元数据与 `messageCount`，与带正文的 `SessionView` 刻意分开。
- **MockChatModel（mock provider）**：确定性脚本模型，不持状态。
- **审计三表**：`sessions` / `llm_calls` / `tool_invocations`，本节沿用既有结构，不加表、不加列。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: mock 全链路：一次"记住…"对话产生 `llm_calls` 恰 2 条（全成功）、`tool_invocations` 恰 1 条（成功）、会话历史恰 4 条、`MEMORY.md` 真写入。
- **SC-002**: `GET /api/v1/sessions` 的摘要不含正文；`?status=` 过滤不受未命中行影响（有专门回归断言）。
- **SC-003**: 三类失败路径各留一条 `success=false`，系统不崩。
- **SC-004**: `mvn clean verify` 全绿，且 `MockProviderFlowTest` / `MockAgentE2ETest` 在门禁内自动跑通。
- **SC-005**: `HumanTriggerFlowIT` 打 `@Tag("integration")`，无 key 时跳过、有 key 时跑通三支柱 + 失败路径 + 三面同源。
- **SC-006**: 启动日志 `Found N JPA repository interfaces` 的 N 大于 0。

## Assumptions

- 核心阶段 Web 层无认证是既定前提（技术方案 §7.5），端点暴露面由部署方式兜底。
- 会话量在核心阶段很小，`PageRequest` 已足够，不引入分页游标。
- 管理台渲染属人工验收项，门禁只覆盖后端与接口契约。
