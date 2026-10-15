# Feature Specification: Notify——结果主动送出去的统一出口

**Feature Branch**: `004-lesson19-notify`

**Created**: 2026-10-15

**Status**: Draft

**Input**: User description: "第19节需求：Notify（出站通知）——入站有 Channel 负责『消息怎么进来』，Notify 补对称的另一半：『结果怎么主动送出去』。背景与价值：CLI 和 Web Service 都是『人推』——有人发起一次调用、Agent 处理完把响应同步返回给发起者，不需要额外的推送机制；但一旦触发源变成『到点自动』（定时查天气、每日科技新闻汇总），这条链路就断了——没有人在另一端等着接收响应，Agent 必须主动把结果送到人能看到的地方。没有这个模块，每个业务方定义 Agent 时都要自己手写『调 http_post 打 webhook URL』或各配一份 IM 的 MCP server——重复且不统一。Notify 把『往外推一条消息』这件最常见的事统一掉，也是后续 Memory、Sandbox 会反复用到的『接口先行』设计习惯的第一次亮相，是 25 节定时模块与 31 节 Demo 的出口。用户场景：①每天早上自动查天气、生成穿搭建议并主动推送到配置好的群，无人等待、无需人工干预；②对话里直接让 Agent 推送一条测试消息并确认『已推送』；③运营方把通知从 A 群换到 B 群只改运行时配置，不碰代码、不改对话、不重启。功能需求：FR1 出站通知抽象（接口语汇零渠道特有词，通知目标=渠道类型+一份配置映射）；FR2 核心阶段一档通用 webhook 实现（把内容包成 JSON 发一次 POST、地址取自配置不得硬编码、对端非成功响应与连接失败必须异常上抛绝不静默吞掉、失败走工具执行统一审计路径）；FR3 notify 内置工具（content 必传、channel 可选按全局注册表渠道名引用、缺省取第一个、未注册/未配置明确报错零请求；注册表本体随后续节）；FR4 发出前预留域名白名单校验位（与 http_post 共享机制，实现归 23/24 节）；FR5 渠道配置属运行时配置、不出现在对话与工具签名、凭证走环境变量占位。明确不做：企微/飞书/钉钉专用 API（签名算法、AccessToken 刷新）留扩展阶段；Sandbox 校验本身不实现；入站/出站不合并抽象；notify 完整接线（@Tool 注册归 20 节、Sandbox.enforce 归 23/24 节）不在本轮可独立验证范围。验收标准：可自动化部分由课件『验收 harness』承载（mvn test 全绿即通过）——第一批：POST 一次/body 带 content、地址来自配置非硬编码、5xx 异常上抛、缺 url 报错点名零请求；第二批（24 节后补跑）：渠道解析与 enforce 先于 send 的 InOrder 顺序断言。依赖与假设：16 节 Provider/审计路径、17 节 ToolExecutor/ToolResult/SableTool/ProfileContext、18 节会话/CLI 均已交付；渠道模型按技术方案 §6.8 与 AGENTS.md §6 走全局注册表按名引用（课件『Profile 新增 notify_channels 字段』为过时漂移，已拍板）；假 webhook 用 JDK 内置 HttpServer（零新第三方依赖）；notify 实现保持纯 POJO（装配归 20 节 ToolRegistry）。"

## Clarifications

### Session 2026-10-15

- Q: 渠道配置归属——课件（Profile 新增 `notify_channels` 字段、由 ProfileContext 解析）与技术方案 §6.8 / AGENTS.md §6（渠道存 SQLite 全局注册表、按渠道名引用、`AGENT.md` frontmatter 不含该字段）冲突，以哪边为准？ → A: **按技术方案——全局注册表、按名引用**；不建 Profile 字段；渠道注册表本体（存储表、Web CRUD）随后续节落地，不在本特性范围。课件该处为过时漂移，按软门禁 #3 拍板；已核实兄弟仓库 oryxos 终态同构（core 注册表契约 + storage JPA + web CRUD，Profile 内联仅是它早期的历史兼容层）。
- Q: 假 webhook 用什么起？课件写 MockWebServer。 → A: **JDK 内置 HttpServer**（本地端口、真 HTTP，仍是单测层）——意图与课件一致（本地假 webhook、不算外网依赖），零新第三方依赖、不扩大 CI 依赖安全扫描面；对标 oryxos 同节 research D5 先例。断言逻辑与课件逐条一致（POST 一次、body 带 content、地址来自配置、5xx 上抛）。
- Q: 本轮实施范围与装配方式？ → A: **范围按课件"实现顺序说明"**：本轮可独立验证范围 = 通知抽象三件（接口/通知目标/通用 webhook 实现）+ 第一批 harness；NotifyTools 与第二批 harness 的完成时点在 24 节之后、27/28 节串联时全量验证。**装配保持纯 POJO**：不加 `@Component`、不建配置类，装配归 20 节 ToolRegistry 统一收编（对标 oryxos D6 先例）；`sableos-tool` 仅新增 `spring-web`（Boot BOM 管理、非新第三方构件）。
- Q: `channel` 参数传字面量 `"default"`（课件 harness 示例用词）应按什么语义解析？ → A: **与缺省/空白同口径——取注册表第一个渠道**（哨兵语义，优先于同名渠道匹配）：课件 InOrder 示例 `notify("hello", "default")` 与该场景"用默认渠道"意图一致，对标 oryxos 同节澄清先例（其终态实现对 `null`/空白/`default` 均取第一个）；本仓库按全局注册名模型落地，`default` 不应因未注册而报错。
- Q: 对端 3xx 重定向与 4xx/5xx 是否同口径（"非成功响应都异常上抛"是否含 3xx）？ → A: **本节失败口径 = 4xx/5xx 与连接失败**：依赖 RestClient 默认异常上抛（不注册自定义吞错处理器），默认判定口径已在本地 spring-web 6.2 构件逐字节码核实（`isError` = hundreds∈{4,5}，3xx 默认不抛）。**3xx 重定向的出网管控**（禁自动跟随、逐跳复检白名单、无 Location 明确报错）属出网安全策略，归 23/24 节 Sandbox 出网策略落地（与白名单检查位同批）；本节不做特判、不加未经验证的自定义逻辑（对标 oryxos：redirect 系列断言在其出网组件落地后补跑）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 定时结果主动送达 (Priority: P1)

用户对 Agent 说"每天早上帮我看看天气，穿搭建议直接发到我们群里"。之后每天到点自动查天气、生成建议，并把结果推送到配置好的群——没有人在另一端等响应，也不需要任何人工干预。

**Why this priority**: 这是 Notify 存在的全部理由：定时触发的结果若送不出去，跑完一整套 ReAct 循环结果也只能烂在会话里没人看到；25 节定时模块和 31 节两个 Demo 都指着这个出口。

**Independent Test**: 用本地假 webhook（单测层 HTTP 假服务）承接推送，断言收到一次 POST、body 含推送内容、目标地址来自该通知目标配置而非硬编码。

**Acceptance Scenarios**:

1. **Given** 一个配置好的 webhook 通知目标，**When** 通知能力被调用（content="今天 28°C，建议短袖"），**Then** 目标地址收到恰好一次 POST，body 中携带该内容。
2. **Given** 同上，**When** 检查发出的目标地址，**Then** 地址来自通知目标配置（改配置即改目标），实现中无硬编码地址。

---

### User Story 2 - 发送失败不许装成功 (Priority: P1)

对端 webhook 返回错误（4xx/5xx）或连接失败时，通知能力必须把失败显式抛出——Agent 不能以为发出去了，事后审计也必须能看到这次失败。

**Why this priority**: 静默吞掉发送失败是最危险的软故障：日报"发了"但群里没人收到，发现时已经断了一周。"发出去没送到"与"没发出去"对 Agent 是同一件事。

**Independent Test**: 假 webhook 固定返回 500，断言发送调用异常上抛（不被吞掉）。

**Acceptance Scenarios**:

1. **Given** 假 webhook 固定返回 500，**When** 发送一条通知，**Then** 调用以异常结束、错误信息可见；该失败经工具执行统一路径落 `tool_invocations`（success=false）。
2. **Given** 目标地址不可达（连接失败），**When** 发送一条通知，**Then** 同样异常上抛，零静默成功。

---

### User Story 3 - notify 工具的渠道解析 (Priority: P2)

Agent 在对话里调用 notify 工具：大多数时候只传 content——系统取第一个通知渠道（channel 缺省、空白或字面量 `default` 同此口径）；显式传 channel 时按渠道名从全局注册表选中对应渠道；渠道名不存在或一个渠道都没注册时明确报错，绝不静默失败。

**Why this priority**: 渠道解析是 notify 工具的业务核心；"未配置却装作发送成功"与 US2 是同一类必须钉死的软故障。本 story 的完成时点在 24 节之后（@Tool 注册挂载与白名单真实现分别归 20 节与 23/24 节），本轮先纳入规格与任务登记。

**Independent Test**: mock 沙箱与适配器，构造含多个渠道的注册表与空注册表，分别断言：缺省取第一个渠道、显式按名选中指定渠道、无渠道时报错点名；并用 `InOrder` 钉死"校验先于发送"的顺序。

**Acceptance Scenarios**:

1. **Given** 注册表中已注册多个通知渠道，**When** 只传 content（或 channel 传空白/`default`）调用 notify，**Then** 内容送往第一个渠道。
2. **Given** 同上，**When** 传 channel 指定某一渠道名，**Then** 内容送往该渠道。
3. **Given** 一个渠道都没有注册（或指定的渠道名不存在），**When** 调用 notify，**Then** 返回明确错误（点名原因），不发出任何请求。
4. **Given** 白名单校验位已接线（24 节起），**When** 调用 notify，**Then** 校验先于发送被调用——顺序反了就是漏洞。

---

### User Story 4 - 换渠道零代码 (Priority: P3)

运营方想把通知从 A 群换到 B 群：只改运行时渠道配置里的 webhook 地址（或对应环境变量），不碰任何代码、不改对话内容、不重启。

**Why this priority**: "配置即 Agent"总原则在出站方向的体现；接口先行保证以后加新渠道（企业微信/飞书专用 API）只增实现不改调用方。

**Independent Test**: 同一套代码、两份不同 url 的通知目标，分别发送后断言各自地址收到——地址完全由配置决定。

**Acceptance Scenarios**:

1. **Given** 通知目标配置中 url=A，**When** 发送，**Then** A 收到；**Given** 改为 url=B，**When** 再发送，**Then** B 收到（代码零改动）。
2. **Given** 抽象接口既定，**When** 未来新增某专用渠道实现（按渠道类型增挂），**Then** 接口签名与既有调用方不需要改（接口语汇不含任何一档实现特有的词——设计自查项）。

---

### Edge Cases

- 通知目标配置缺 url（webhook 档必需）→ 发送前明确报错点名，不发出任何请求。
- content 为空串 → 照常发送（是否有意义由调用方/模型决定，通知层不猜）。
- channel 参数指定的渠道名在注册表中不存在 → 明确报错点名，不回退默认渠道（回退会把消息发错地方）。
- channel 传字面量 `default` → 与缺省同口径取第一个渠道（哨兵语义，优先于同名渠道匹配）。
- 对端返回 4xx/5xx → 与连接失败同口径：异常上抛（依赖 HTTP 客户端默认判定、不注册吞错处理器——凡已判定的非成功都不许装成功）。
- 3xx 重定向 → 出网管控（禁自动跟随、逐跳复检白名单、无 Location 明确报错）归 23/24 节出网策略落地；本节不做特判、也不声称已管控。
- 连接失败（目标不可达/超时）→ 同样上抛，不许静默。
- 当前 Agent 上下文缺失（工具在处理链路外被调用）→ 明确报错，不发送（归 NotifyTools，24 节后落）。
- 白名单检查位：Sandbox 就位前不拦截（留调用位）；就位后校验先于发送——顺序回归留待 24 节用 `InOrder` 钉死。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供不携带具体渠道细节的出站通知抽象：表达"把一条内容送到某个通知目标"这一意图；通知目标只含渠道类型与一份配置映射（配置如何解释由实现决定）；接口语汇 MUST NOT 出现任何一档实现特有的词（接口先行——后来加新渠道只新增实现类，不改接口、不改调用方）。
- **FR-002**: 核心阶段 MUST 且仅 MUST 提供一档通用 webhook 实现：把内容包成 JSON 向目标地址发一次 POST（同步阻塞）；目标地址 MUST 取自通知目标配置，实现内不得硬编码；对端 4xx/5xx 与连接失败 MUST 异常上抛、MUST NOT 静默吞掉（依赖 HTTP 客户端默认异常判定、MUST NOT 注册吞错处理器；3xx 重定向的出网管控归 23/24 节出网策略落地）；失败经工具执行既有统一审计路径落库（`tool_invocations`，不新增审计逻辑）。
- **FR-003**: 系统 MUST 提供 notify 内置工具能力：content 必传；channel 可选、按渠道名引用**全局通知渠道注册表**；channel 缺省、空白或字面量 `default`（课件示例用词，哨兵优先于同名匹配）时取注册表第一个渠道；未注册渠道名 / 无任何已注册渠道 MUST 明确报错且零请求发出（绝不静默失败——Agent 不能以为发出去了）。渠道注册表本体（存储表、Web CRUD）不在本特性范围，随后续节落地。
- **FR-004**: 通知发出前 MUST 过域名白名单检查位（与 `http_post` 共享同一份白名单与校验机制；校验实现属 23/24 节，本特性只留接线位，MUST NOT 自行发明校验逻辑）。
- **FR-005**: 渠道配置（webhook 地址等）MUST 只存在于全局注册表（后续 CRUD 所管理的那一份）；MUST NOT 出现在对话内容或工具接口签名中；配置中的凭证 MUST 走环境变量占位，不明文落库、不进日志、不进 git。

### Key Entities *(include if feature involves data)*

- **通知目标（NotifyTarget）**: 渠道类型 + 一份配置映射；对上层是黑盒，由具体实现解释（webhook 档取配置里的目标地址）。
- **出站通知抽象（NotifyChannelAdapter）**: "送内容到目标"这一意图的唯一表达；核心阶段一档 webhook 实现，扩展阶段按渠道类型增挂实现类（不改接口、不改调用方）。
- **notify 工具（NotifyTools）**: LLM 可调用的内置工具；渠道解析（全局注册表按名 ← 当前 Agent 运行上下文）+ 白名单检查位 + 委托发送；`@Tool` 注册挂载归 20 节、白名单真实现归 23/24 节，完成时点在 24 节之后。
- **全局通知渠道注册表**: 通知出口的唯一真相源；每项含渠道名、类型、目标地址与可选描述、类型相关多字段配置；Agent 在 `AGENT.md` 正文按名引用（frontmatter 不含 `notify_channels` 字段）；本特性只依赖其"按名引用"语义，实现（存储与 CRUD）随后续节。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 配好通知渠道后，一次通知调用使目标地址收到恰好一次 POST 且内容完整——自动化断言（本地假 webhook）。
- **SC-002**: 发送失败（对端 4xx/5xx 或连接失败）100% 以显式错误结束并留下审计记录，零静默失败。
- **SC-003**: 换通知目标只改配置零代码：同一实现对不同配置发往不同地址——自动化断言。
- **SC-004**: 无已注册渠道 / 指定渠道名不存在 / 当前 Agent 上下文缺失三种情况 100% 明确报错且零请求发出（渠道解析断言在 24 节后随第二批 harness 补齐）。
- **SC-005**: 接口中立性：新增一档专用渠道实现无需修改抽象签名与调用方（设计自查项，人工确认）。

## Assumptions

- 前序交付（均已核实存在）：16 节 Provider 与审计落库路径（`llm_calls`）；17 节 `ReActLoop`/`ToolExecutor`/`ToolResult`/`SableTool`/`ProfileContext`/`ContextLoader` 与 `tool_invocations`；18 节会话层与 CLI。
- 渠道模型拍板：按技术方案 §6.8 与 AGENTS.md §6——渠道存 SQLite 全局注册表、按名引用、`AGENT.md` frontmatter 不含 `notify_channels` 字段（课件"Profile 新增 `notify_channels` 字段"为过时漂移，已确认为软门禁 #3 并拍板按技术方案执行；与兄弟仓库 oryxos 终态同构）。
- 测试假 webhook 机制拍板：JDK 内置 HttpServer（本地端口、真 HTTP，仍是单测层）；零新第三方依赖、不扩大依赖安全扫描面（对标 oryxos 同节 research D5 先例）。
- 装配口径拍板：notify 实现保持纯 POJO（不加 `@Component`、不建配置类）；装配归 20 节 ToolRegistry 统一收编；`sableos-tool` 模块仅新增 `spring-web`（RestClient，Boot BOM 管理，非新第三方构件）。
- 实现顺序（授课顺序 ≠ 构建顺序）：本特性可独立验证范围 = 通知抽象三件 + 第一批 harness；NotifyTools 完整接线（`@Tool` 注册归 20 节、白名单真实现归 23/24 节）与第二批 harness（含"enforce 先于 send"顺序断言）在 24 节后补齐，27/28 节串联时全量验证。
- HTTP 客户端使用项目锁定 BOM 内组件；发送同步阻塞（不引入异步/反应式模型）。
- 本节零数据库迁移；零新增第三方依赖。
- 明确不做（边界）：企业微信/飞书/钉钉专用 API（签名算法、AccessToken 刷新）留扩展阶段；入站 Channel 与出站 Notify 不合并抽象（语义方向相反，分开建模）；渠道注册表的存储表与 Web CRUD 随后续节。
