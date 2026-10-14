# Feature Specification: Agent Provider——对接大模型的统一入口

**Feature Branch**: `001-lesson16-provider`

**Created**: 2026-10-14

**Status**: Draft

**Input**: User description: "第16节需求：Provider——对接大模型的统一入口。背景与价值：Agent 的大脑是 LLM，ReAct 循环每转一圈都要调一次模型；企业场景有三个现实约束——多家模型并存（不同 Agent 用不同家）、模型要能随时换（不锁任何一家厂商）、每次调用要可审计（花了多少 token、失败为什么）。没有统一抽象层，上层代码就会和某家 SDK 耦合死，换模型等于改代码。用户场景：同一实例上运维 Agent 用 deepseek、客服 Agent 用 qwen 并存不串台；改 Agent 的 provider/model 只改配置不碰代码；审计员事后可查每次调用的模型、token、耗时、失败原因。明确不做：ReAct 循环本身、工具的真正执行、fallback/熔断/hedge racing、成本聚合看板、流式响应。"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 多 Agent 多模型并存，按名路由不串台 (Priority: P1)

同一个 SableOS 实例上同时配置多个模型提供方；每个 Agent 通过自身运行配置声明本次用哪家 provider、哪个 model。运维 Agent 声明 deepseek、客服 Agent 声明 qwen，两者各自发起调用时，每次都落在自己声明的模型上，互不干扰。

**Why this priority**: 这是 Provider 存在的理由本身——"多 Provider 并存、显式映射"是全部企业约束里最先要立住的一条；没有它，后面的审计、换模型都是空中楼阁。

**Independent Test**: 配置两个可区分的模型提供方，让两个 Agent 各自发起一次调用，断言每次调用的实际落点与声明一致且另一家完全未被触碰。

**Acceptance Scenarios**:

1. **Given** 实例声明了 deepseek 与 kimi 两个 provider，且两个 Agent 分别声明使用其中之一，**When** 两个 Agent 各自发起一次调用，**Then** 每次调用的实际落点就是该 Agent 声明的那一家，另一家零调用。
2. **Given** 某个 Agent 的运行配置引用了实例清单里不存在的 provider 名，**When** 加载该配置，**Then** 系统显式报错并点名该 provider，绝不静默换用其他家或留空跑过去。

---

### User Story 2 - 每次调用可审计：成功与失败都留痕 (Priority: P2)

审计员事后能查到任意一次调用：用了哪家 provider、哪个 model、输入输出各多少 token、耗时多久；若该次调用失败（超时、限流、模型报错），能查到失败原因。失败的调用除了留痕，还要把错误继续抛给调用方处理，由调用方决定如何应对。

**Why this priority**: "私有可审计"是 SableOS 的定位本身；且失败留痕是最容易漏的一条——只记成功不记失败，一次真实事故在系统里就完全没留下痕迹。

**Independent Test**: 分别驱动一次成功调用与一次失败调用，断言审计记录两条都在、失败那条带原因；断言失败调用同时向调用方抛出了异常。

**Acceptance Scenarios**:

1. **Given** 一次正常的模型调用，**When** 调用成功返回，**Then** 审计新增一条记录，含 provider、model、token 用量、耗时、成功标识为真。
2. **Given** 模型端点返回错误或连接超时，**When** 调用失败，**Then** 审计先落账一条成功标识为假、带失败原因的记录，随后异常继续向上抛给调用方。

---

### User Story 3 - 工具说明只翻译不执行，执行权留在上层 (Priority: P3)

上层可以在请求里携带工具说明（告诉模型有哪些工具可用）。模型看完可能返回"我想调用某工具"的请求——本模块只把工具说明翻译成所选模型协议下的格式，模型返回的工具调用请求原样交回上层执行，本模块绝不执行任何工具，并确保底层接入能力不会自行执行工具。

**Why this priority**: 这是整个架构里最容易写错的一条边——一旦底层自行执行工具，同一个工具会被调用两次，且执行绕过了上层统一编排与审计。它必须在接入层就钉死，但相对路由与审计是可稍后验证的独立切片。

**Independent Test**: 发起一次携带工具说明的调用，断言翻译后的请求里工具说明完整、且自动执行被关闭；再发起一次模型要求调工具的调用，断言请求原样回到上层、全程没有任何执行发生。

**Acceptance Scenarios**:

1. **Given** 一次携带工具说明的调用请求，**When** 请求发往模型，**Then** 请求中带有翻译完整可用的工具说明，且自动执行处于关闭状态。
2. **Given** 模型返回了"想调用某工具"的请求，**When** 本模块处理该响应，**Then** 该请求被原样交回上层，本模块未执行任何工具。

---

### Edge Cases

- 某个 Agent 的运行配置文件损坏或引用了未登记的 provider：该 Agent 记错误日志、不生效；其余 Agent 照常加载，启动不被阻断。
- 模型调用失败（超时/限流/服务端报错）：异常上抛给调用方，同时审计留痕（成功标识为假 + 原因），两条路都要走。
- 调用请求未携带任何工具说明：按"无可用工具"处理，不影响调用本身。
- 模型返回的"想调工具"请求指向上层不认识的名字：本模块不解释、不执行，原样交回，由上层处理。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 每个 Agent 的模型选择由其运行配置（Profile）声明——用哪个 provider、哪个 model、什么温度；Profile 由工作区中的 Agent 定义（一个目录 = 一个 Agent）派生，系统启动时全量加载；单个定义解析失败只记错误日志、不阻断启动，其余 Agent 正常可用。
- **FR-002**: 实例级声明可用的 provider 清单及凭证来源（环境变量占位）；Profile 引用了清单里不存在的 provider 名，必须显式报错，不允许静默跑过。
- **FR-003**: 系统必须提供统一调用入口：上层传入会话标识、Profile、Prompt，系统按 Profile 选中对应模型、发起一次调用、把结果原样返回（响应中可能带"想调工具"的请求，交回上层处理）。
- **FR-004**: 调用请求可携带工具说明（告诉模型有哪些工具可用）；模型返回"想调某工具"时，该请求原样交回上层——本模块只做格式翻译、绝不执行工具，且必须确保底层接入能力不会自行执行工具。
- **FR-005**: 每次调用不论成败都必须落审计（`llm_calls` 表）：provider、model、token 用量、耗时、success 标识、失败原因，按会话关联。
- **FR-006**: 凭证只从环境变量读取，代码、配置文件、日志里都不出现明文。

### Key Entities *(include if feature involves data)*

- **Agent 运行配置（Profile）**：一个 Agent 的完整运行声明的载体。关键属性：`name`、`description`、`identity`（`agent_name`、`prompt`）、`provider`（`name`、`model`、`temperature`）、`tools`、`mcp_servers`、`channels`、`schedules`、`bootstrap`、`settings`（`max_iterations`、`max_history_turns`）。不含 `skills`（Skill 绑定由 Agent 目录内的相对软连接表达）与 `notify_channels`（通知渠道由全局注册表管理、Agent 在正文按名引用）。
- **模型提供方声明（Provider）**：实例级清单条目，描述本实例接了哪家模型、凭证从哪个环境变量取。与 Profile 的关系：Profile 按名称引用它，名称对不上即报错。
- **LLM 调用审计（`llm_calls`）**：每次模型调用一条，成败均记。属性：所属会话、provider、model、token 用量（输入/输出/合计）、耗时、成功标识、失败原因、链路标识（traceId）、创建时间。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 在多 provider 并存的实例上，任意一次调用的实际落点与该 Agent 声明的 provider 完全一致（路由零错配，100% 可验证）。
- **SC-002**: 任意一次已发生的调用都能在审计里查到完整要素（provider、model、token、耗时、成败）；失败记录 100% 带失败原因。
- **SC-003**: 给 Agent 换 model 或换 provider 只需修改该 Agent 自身的运行配置，上层代码零改动即可生效。
- **SC-004**: 单个 Agent 定义损坏不影响其余 Agent：启动不被阻断，其余 Agent 全部正常加载。

## Assumptions

- "一个目录 = 一个 Agent"（技术方案 §8.2 / 宪章原则 V）：Profile 由工作区中的 Agent 定义派生，启动时全量扫描并在内存中按名索引；运行期动态注册/热加载属后续特性（第 29 节），本特性只做启动加载这一条路径。
- 目标 provider 的接入依赖在项目锁定的 BOM 版本内可用——动手前先验证依赖能下载、能解析；课件里出现的 provider 名只是示意，不代表装了就可用（课件坑三）。
- 凭证经环境变量注入，代码、配置、日志不落明文。
- 本特性边界：不含 ReAct 循环、工具的真正执行、fallback/熔断/hedge racing、成本聚合看板、流式响应——均为后续特性。
- 审计记录随链路标识（traceId）关联，与既有链路基建（core 的 trace 内核）对齐。
- 本模块的直接下游消费者是第 17 节的 ReAct 循环；本特性验收以"能撑住 Demo 一（每日天气）里的那次大模型调用"为最终标准。
