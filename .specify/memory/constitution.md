# SableOS Constitution

> SableOS 的不可协商原则集。所有 spec、plan、tasks、implement 产出都必须符合本宪章；
> `/speckit.analyze` 以本文件作为一致性检查的基准。

## Core Principles

### I. JDK 21 + Spring Boot 3.x 单体应用（NON-NEGOTIABLE）

- 运行时 JDK 21，框架 Spring Boot 3.x，Maven 多模块，单二进制 fat JAR 部署。
- 高并发用虚拟线程处理。
- 禁用一切非 JDK 21 特性；构建必须锁定 Java 21 目标（本机曾出现 JDK 8 下静默产出 Java 8 字节码的先例）。
- 模块划分以《技术方案》第 10 章为准（14 个模块，persona / knowledge / 三渠道模块按实施节奏分批落地）；骨架期的 9 模块口径不是最终形态。

理由：企业自托管场景下，单二进制 + 虚拟线程的部署与并发成本最低，且不引入外部中间件依赖。

### II. 自实现 ReAct 循环（NON-NEGOTIABLE）

- 不依赖 Spring AI 的 Agent 抽象。
- 循环引擎、迭代次数控制、消息累积、错误处理全部由 `sableos-core` 自行实现。

理由：循环是 Agent OS 的核心可审计点，必须完全可控、可替换。

### III. Spring AI 只用一半（NON-NEGOTIABLE）

- **允许**使用：Provider 抽象、协议转换、`@Tool` schema 生成。
- **禁止**使用：Spring AI 的自动 tool 执行。
- tool 调度完全由 `ReActLoop` + `ToolExecutor` 控制。

理由：这是最容易被写错的一条。一旦启用自动执行，同一个 tool 会被调用两次，且审计链路断裂。

### IV. Provider 显式映射（NON-NEGOTIABLE）

- 维护 provider name 到 `ChatModel` 的显式映射表，多 Provider 并存。
- 禁止用类型扫描（classpath scanning）自动发现 Provider。

理由：隐式发现会让「哪个 provider 生效」不可预测，也让配置校验失去落点。

### V. Tool 三档接入且只有一个模块（NON-NEGOTIABLE）

- 三档接入：内置 Tool、SKILL.md、MCP；主推 SKILL.md + MCP 的零代码方式。
- builtin / skill / mcp 合并为唯一的 `sableos-tool` 模块，禁止再拆成多个模块。
- `.sableos/agents/<name>/AGENT.md` 与 `AgentLoader` 归 `sableos-core` 的 `ContextLoader`，不算 Tool。

### VI. 审计 day one 落库（NON-NEGOTIABLE）

- 持久化：SQLite + Spring Data JPA + `MEMORY.md` 文件。
- `tool_invocations` 与 `llm_calls` 两张审计表在核心阶段就写入数据库，不是只写日志。
- 向量检索放扩展阶段。

理由：私有可审计是 SableOS 的定位本身，审计能力不能等到治理阶段再补。

### VII. Sandbox 接口先行（NON-NEGOTIABLE）

- 抽象 `Sandbox` 接口 + 应用层 `WhitelistSandbox`（Path / Pattern 白名单）。
- 禁用 `SecurityManager`（JDK 17 起废弃、JDK 21 已不可用）。

### VIII. 范围收敛：核心阶段只做运行时内核（NON-NEGOTIABLE）

- 五大核心能力（对接 LLM、ReAct 循环、Memory、Plugin Tool、Web Service）优先交付。
- 多租户、SSO、完整审计查询、Tool 治理属扩展阶段，核心阶段仅预留扩展点，不实现。
- 扩展阶段新增 Channel 或 Tool 只加新模块、不改 `sableos-core`。
- 契约依赖倒置：入站渠道契约放 `sableos-core/channel/`，知识库契约放 `sableos-core/knowledge/`。

### IX. 每个 user story 交付可演示 Demo

- 优先级是跑通而非完美；每个 user story 完成时必须有可演示 Demo，验收标准复用《需求文档》第 13 章的 5 个 Demo。
- 每个 user story 结束后必须跑 `/speckit.analyze`，核对 constitution + spec + plan + tasks + 代码是否一致，发现漂移立即修正。

## 技术栈与范围约束

| 维度 | 选型 | 约束 |
| --- | --- | --- |
| 语言 / 运行时 | JDK 21 | 构建锁定 Java 21，禁用非 21 特性 |
| 框架 | Spring Boot 3.x | 单体应用，不使用微服务拆分 |
| LLM 接入 | Spring AI Alibaba | 只用 Provider 抽象、协议转换、`@Tool` schema 生成 |
| 持久化 | SQLite + Spring Data JPA | 审计表 core 阶段落库；向量检索留扩展阶段 |
| CLI | Picocli | 子命令数量以《技术方案》第 10 章为准 |
| 模块 | Maven 多模块 | 划分以《技术方案》第 10 章为准，扩展只加模块不改 core |
| 部署 | 单二进制 fat JAR | 无外部中间件依赖 |

范围边界：核心阶段的范围就是「运行时内核」五个能力；任何企业级治理需求都不得挤进核心阶段的 plan 与 tasks，只能记为扩展点。

## 开发流程与质量门槛

- 主体开发走 Spec-Kit：constitution → specify → clarify → plan → checklist → tasks → analyze → implement → converge。
- 每个 user story 结束后必跑 `/speckit.analyze`，并通过一次 git commit 固化稳定状态。
- `/speckit.plan` 产出后**人工 review 是必要环节**，重点核查：Memory 是否被简化成与 Session 合并、Tool 是否被拆成多模块、Agent 目录是否被当成 Tool、是否启用了 Spring AI 自动 tool 执行。
- Spec-Kit 版本锁定（当前 specify-cli 1.0.13），主体开发期间不升级、不引入 community extension，只用官方核心命令。
- 文档同步：`docs/` 文档之间冲突以《技术方案》为准；修改 `docs/` 遵守 AGENTS.md 的 Markdown 格式约定，提交前跑 markdownlint。
- 跨 task 上下文丢失时，回到 `spec.md` + `plan.md` + 最近代码，不凭印象继续写。

## Governance

- 效力：本宪章是项目的最高约束，优先于其他实践与文档；仓库文档之间冲突时以《技术方案》为准。
- 修订程序：修订需项目方明确同意并书面记录变更理由与影响面；**AI agent 无权自行修改本宪章**，发现生成内容偏离宪章时只能重读本宪章并纠正产出。
- 版本策略：语义化版本。MAJOR = 原则或治理条款的移除、重定义（向后不兼容）；MINOR = 新增原则或实质性扩展约束；PATCH = 措辞澄清、错别字与非语义调整。
- 合规审查：每次 `/speckit.analyze` 必须核对本宪章；plan 与 tasks 需能追溯到对应原则编号；无法解释的偏离必须在进入 implement 前修正。

**Version**: 1.0.0 | **Ratified**: 2026-10-05 | **Last Amended**: 2026-10-05
