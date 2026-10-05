# SableOS 项目指南

> **本文件（`AGENTS.md`）是仓库内 AI coding agent 的唯一权威指南**，Claude Code、Codex、Cursor 等一律读这一份。`CLAUDE.md` 只是指向本文件的入口，不含任何项目内容 —— 两边冲突时以本文件为准，且只需维护本文件。
>
> SableOS 目前处于**文档先行阶段**：主体开发尚未开始，仓库里现有 `docs/`（六份文档）、`design/`（设计留档）、`website/`（线上站点）与 Maven 多模块骨架。动手写代码前，先读本文档，再读对应源文档；不确定某个文件该放哪，先看第 3 章。

## 1. 项目是什么

SableOS 是一个**企业能完全掌控的、Java 原生的、私有可审计的 Agent OS（Agent Harness OS）**。核心阶段交付的是 Agent OS 的**运行时内核**，由五大核心能力构成：

1. **对接 LLM**：Provider 抽象，多 Provider 并存
2. **ReAct 循环**：自实现循环引擎，完全可控
3. **Memory 记忆**：会话记忆 + 长期记忆的分层体系
4. **Plugin Tool**：三档扩展方式接入工具
5. **Web Service**：REST API 对外集成

企业级治理能力（多租户、SSO、完整审计查询、Tool 治理）**不在核心阶段**，只在架构上预留扩展点，放到扩展与社区阶段补齐。

> 行业定位与竞品空白见 `docs/IndustryResearch.md`；为什么在 Java 生态做这件事、社区背景见 `docs/sable-labs.md`。

## 2. 仓库现状

- 仓库当前包含：`docs/` 六份文档、`design/` 设计留档、`website/` 线上站点、`README.md`、`AGENTS.md`（本文件）、`CLAUDE.md`（指向本文件的入口），以及 **Maven 多模块骨架**（2026-09-27 初始化）：parent POM + 9 个核心模块（core / provider / memory / tool / channel-cli / web / storage / cli / boot），`mvn clean package` 可通过，`sableos-boot` 产出可执行 fat JAR。骨架只含模块结构、依赖拓扑与入口类（`SableOsApplication`、`SableOsCli`），**尚无业务代码**。
- 骨架按《技术方案》第 13 章第一周的 9 模块口径落地；第 10 章的 14 模块清单（多 persona / knowledge / 三渠道模块）在实施到对应能力时再补模块。各模块依赖也按实施节奏补齐（provider 的 Spring AI Alibaba、storage 的 JPA/SQLite 尚未加入）。
- `git` 仓库已初始化（main 分支）；**Spec-Kit 工作区已于 2026-10-05 就地初始化**（集成键 `qodercli`，技能装在 `.qoder/skills/`，配套 CLI `specify-cli` 1.0.13 锁版本），宪章已落地为 v1.0.0；`spec.md` / `plan.md` 尚未产出。
- 文档在 2026-09-21 做过一次纯格式整理（统一标题层级、表格分隔符、引用块、列表空行），**内容与整理前逐字一致**，以当前版本为准。

## 3. 目录分工

> 三块对外资源是**源 → 文档副本 → 线上产物**的单向关系：同一个文件只在一处维护，不要两处各存一份。拿不准某个文件该放哪，按本表判断。

| 目录 | 放什么 | 不放什么 | 是否上线 |
| --- | --- | --- | --- |
| `website/` | **线上站点本体**：`index.html` + `assets/{css,js,img,fonts}`，Pages 的唯一入口 | 设计草稿、生成脚本、评审台 | ✅ 发布 |
| `design/` | **设计过程留档**：`assets/`（标识源文件、`make-*.py` 生成器、`logo-concepts/` 备选概念）、`preview/`（评审台 `index.html`、`audit.json` 校验原始数据）、`README.md` | 站点代码、`docs/` 文档正文 | ❌ |
| `docs/` | **文档语料**：六份 `.md` + `images/`（文档内嵌图，见第 4 章） | 站点代码、设计源文件 | ❌ |
| `sableos-*`（9 个） | Maven 模块，各自只有 `pom.xml` + `src`；模块职责见第 7 章 | 文档、设计资产、生成脚本 | ❌ |
| `.github/workflows/` | CI：`pages.yml`（发布 `website/` 到 Pages + 站点健全性校验） | 业务代码 | — |
| `.specify/` | **Spec-Kit 工作区**：`memory/constitution.md`（宪章）、`templates/`、`scripts/bash/`、内置 SDD 工作流与集成配置 | 应用代码、设计资产 | ❌ |
| `.qoder/skills/` | Spec-Kit 生成的 `speckit-*` 技能（Qoder IDE 1.24+ 扫描此目录，以 `/speckit-<command>` 调用） | IDE 本地状态（`.qoder/` 下除 `skills/` 之外的一切已在 `.gitignore` 排除） | ❌ |

顶层文件：`pom.xml` 是聚合 POM，`README.md` 对外，`AGENTS.md`（本文件）是对内的唯一权威指南，`CLAUDE.md` 只做指向。

**三处都可能出现"图"，用途不同，不要混：**

| 位置 | 是什么 | 谁引用 |
| --- | --- | --- |
| `design/assets/` | 标识**源文件**（SVG 母版）与生成器 | 维护者改标识时重跑脚本 |
| `docs/images/` | 文档内嵌用**副本** | `docs/*.md` 里的图片引用 |
| `website/assets/img` | 线上页面用**产物**（`favicon.svg`、`og-cover.png`、`logo-512.png` 等） | `website/index.html` |

生成方向单向，不要反向改：

```text
design/assets/make-fonts.py   ->  website/assets/fonts/*.woff2
design/assets/*.svg           ->  docs/images/*.svg     （文档内嵌副本）
design/assets/*.svg           ->  website/assets/img/*  （线上产物）
```

两条硬规则：

- **改 `website/**` 会触发线上部署**（`pages.yml` 的 `paths` 含 `website/**`），所以设计草稿、过程文件、`make-*.py` 一律不要放进去。
- **`website/` 必须保持零外部请求**（字体图标自托管、不引 CDN），工作流里的健全性校验会拦截外部 CDN 引用与缺失资源。

设计主张、资产清单、评审台用法、部署与 `github-pages` 环境坑见 `design/README.md`。

## 4. 文档地图与阅读顺序

写代码前按下列顺序读；只改文档时，先确认要改的是哪一份。

| 文档 | 回答的问题 |
| --- | --- |
| `docs/IndustryResearch.md` | Agent OS 是什么、业界做到了什么、SableOS 的定位 |
| `docs/DemandAnalysis.md` | 做什么：需求、数据模型、里程碑、验收标准 |
| `docs/TechnicalSolution.md` | 怎么做：架构、模块、接口，**编码时的权威依据** |
| `docs/AiProgrammingGuide.md` | 怎么开发：Spec-Kit 流程、AI coding 协作方式 |
| `docs/sableos.md` | 项目对外简介（叙事向） |
| `docs/sable-labs.md` | 社区背景（叙事向） |

> 文档之间若出现修订不同步，**以《技术方案》为准**——《AI 编程指南》自己也声明"以最新技术方案为准"。

## 5. 硬约束（必须遵守）

### 5.1 关键技术决策（非协商）

| # | 约束 | 说明 |
| --- | --- | --- |
| 1 | JDK 21 + Spring Boot 3.x 单体应用 | Maven 多模块，单二进制部署；虚拟线程处理高并发 |
| 2 | 五大核心能力优先 | 核心阶段只交付运行时内核，治理层放扩展阶段 |
| 3 | **自实现 ReAct loop** | 不依赖 Spring AI 的 Agent 抽象 |
| 4 | **Spring AI 只用一半** | 只用 Provider 抽象、协议转换、`@Tool` schema 生成；**禁用自动 tool 执行**，tool 调度完全由 `ReActLoop` + `ToolExecutor` 控制 |
| 5 | Plugin Tool 三档接入 | 主推 SKILL.md + MCP 零代码方式 |
| 6 | SQLite + Spring Data JPA + `MEMORY.md` 文件 | 审计表 `tool_invocations` 和 `llm_calls` **核心阶段就落库**，不是只放日志 |
| 7 | Sandbox 接口先行 | `Sandbox` 抽象 + `WhitelistSandbox`（应用层 Path/Pattern 白名单）；**不用 `SecurityManager`**（JDK 17 起废弃、JDK 21 已不可用） |
| 8 | Provider 显式映射 | 维护 provider name 到 `ChatModel` 的显式映射，**不用类型扫描** |

### 5.2 AI agent 最容易被写错的点

| 问题 | 正确做法 |
| --- | --- |
| 用了非 JDK 21 特性 | 强制要求 JDK 21 |
| 改了 ReAct 实现方式（依赖 Spring AI 自动执行） | 自实现，tool 被调两次时立刻查这里 |
| 启用了 Spring AI 自动 tool 执行 | 必须禁用，见 5.1 表第 4 条 |
| 把 Tool 又拆成多模块 | 应该合并为一个 `sableos-tool` 模块 |
| Provider 用类型扫描 | 必须用显式 provider name 映射 |
| `AgentLoader`/`AGENT.md` 当成 Tool | Agent 目录归 `ContextLoader`，在 core 模块里 |
| 审计表没落库 | `tool_invocations` 和 `llm_calls` day one 写入 |

## 6. 核心领域概念

- **一个目录 = 一个 Agent**：`.sableos/agents/<name>/AGENT.md`，frontmatter 是运行配置（派生为底座认识的 `Profile`），正文是任务指令；可选 `skills/` 软连接、`scripts/`、`REFERENCE.md`。
- **Skill 绑定用相对软连接，不写 frontmatter**（宪章 v2.0.0 修订）：公共 Skill 存 `.sableos/skills/<name>/`，Agent 在自身 `skills/<name>` 下建受控相对软连接选择可见集合，**软连接集合是唯一绑定真相源**；删除被引用的 Skill 默认拒绝并返回引用 Agent。
- **通知渠道按全局注册名引用**：渠道存 SQLite 全局注册表，`AGENT.md` 正文按名引用，frontmatter **不含** `notify_channels` 字段。
- **核心阶段 `schedules` 只写在 `AGENT.md` frontmatter**：跟着进程启动注册，改 cron 需重启；API 增删改 cron 定义属扩展阶段。

## 7. 模块与范围

SableOS 是 Maven 多模块项目，**以《技术方案》第 10 章为准，共 14 个模块**：

| 模块名 | 职责 |
| --- | --- |
| `sableos-core` | 核心抽象与接口：`SableTool`、`Session`、`Profile`/`Persona`、`ContextLoader`、`AgentLoader`、`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`AgentService`、`AgentScheduler`、`AgentLifecycleService` |
| `sableos-persona` | 人格库（025）：12 个内置预设 + `.sableos/personas/` 自定义人格 CRUD，只做 copy-in 模板复制 |
| `sableos-provider` | 核心能力一：`ProviderService`、Function Calling 适配、Provider 配置 |
| `sableos-memory` | 核心能力三：`MemoryService`、`LongTermMemory`、`MemoryTools` |
| `sableos-knowledge` | 知识库（014）：解析/切分/向量化流水线、双路召回 + RRF、`ChunkStore`、`KnowledgeTools` |
| `sableos-tool` | 核心能力四：内置 Tool、`McpClientService`、`ToolRegistry`、`Sandbox` + `WhitelistSandbox`、`NotifyChannelAdapter`（三合一模块） |
| `sableos-channel-cli` | CLI Channel：`CliChannel`、`sableos chat` |
| `sableos-channel-feishu` | 飞书入站渠道（017）：长连接收事件、出站过沙箱、自动重连 |
| `sableos-channel-wecom` | 企微入站渠道（对称飞书） |
| `sableos-channel-dingtalk` | 钉钉入站渠道（对称飞书/企微） |
| `sableos-web` | 核心能力五：`WebServer`、6 个 `ApiController`、OpenAPI 文档 |
| `sableos-storage` | 持久化层：SQLite、`SessionRepository`、`ToolInvocationRepository`、`LlmCallRepository` |
| `sableos-cli` | 命令行入口：Picocli、13 个子命令、`ConfigLoader` |
| `sableos-boot` | Spring Boot 启动模块：主类、自动配置、依赖聚合 |

范围与边界约定：扩展阶段加新 Channel 或新 Tool 实现**只加新模块不改 core**；入站渠道契约与共享编排（`InboundChannelAdapter`、`InboundMessageService`、`ChannelConfigLoader` 等）在 `sableos-core/channel/`，知识库契约在 `sableos-core/knowledge/`（依赖倒置）。

## 8. 开发流程（Spec-Kit 驱动）

主体开发**不直接写代码**，按 Spec-Kit 流程走：

1. 准备阶段：`specify init` 初始化工作区，产出 `constitution.md`（宪章）、`spec.md`（5 个 user story）、`plan.md`。工作区已于 2026-10-05 就地初始化（集成键 `qodercli`，`specify-cli` 锁 1.0.13，技能以 `/speckit-<command>` 调用），宪章 **v1.0.0 已落地**；`spec.md` / `plan.md` 待产出。
2. 实施阶段：按依赖顺序实施 5 个 user story（US-5 Web Service 因依赖前四个能力排最后，但重要性很高）。
3. **每个 user story 结束后必跑 `/speckit.analyze`**：检查 constitution + spec + plan + tasks + 代码是否一致，发现漂移立刻修正，不能省。
4. **每个 user story 完成后打 git commit**，方便随时回退到稳定状态。
5. **不允许 AI agent 自己修改 constitution**；发现 AI 生成代码偏离宪章时，让它重读 constitution 纠正。
6. 跨 task 上下文丢失时，回去读 `spec.md` + `plan.md` + 最近的代码，别凭印象接着写。

## 9. 改 `docs/` 时的格式约定

六份文档已统一到同一套 Markdown 风格，改动时保持：

- 章节标题用阿拉伯数字（`## 1.`、`### 3.2`），不用 `## 一、`。
- 标题、列表、表格、围栏代码块前后留空行。
- 表格分隔行统一为带空格的 `| --- | --- |`，不用紧凑写法。
- 文档内不用水平分割线（`---`）做视觉切分；分卷分隔符（`# 第 X 部分`）除外。
- 整段导语用引用块（`>`），不要裸段落。
- 强调型小节用 `####` 标题，不要用加粗行当标题。

提交前跑一遍 lint（只允许行内长与多 H1 两类豁免）：

```bash
npx -y markdownlint-cli --disable MD013 MD025 -- *.md
```

## 10. 已知待办与文档漂移

- **图片引用悬空**：`docs/` 里 12 处图片引用（`TechnicalSolution.md` 11 处 + `sableos.md` 1 处）现统一指向同目录下的 `images/*.svg`（与 `docs/images/architecture.svg` 同一约定，不再跨到 `website/`）。`images/logo.svg` 已于 2026-09-29 换为新标识；剩余 **11 张架构/流程图尚未绘制**，引用仍悬空。清单见 `design/README.md`「已知待办」。
- **文档间修订不同步**（以《技术方案》为准）：
  - 模块数：`AiProgrammingGuide.md` 五处（第 35/176/204/405/481 行）与《技术方案》第 13 章第一周都还写 9 个模块，第 10 章已是 14 个（含 persona/knowledge/三渠道模块）。
  - CLI 子命令数：第 8.7 节写 12 个，第 10 章写 13 个（025 起加 `agent import`）。
  - 验收 Demo 数：`DemandAnalysis.md` 第 13 章写"两个 Demo"，`TechnicalSolution.md` 第 11.5 / 12 / 15 章写三个（多"每日 GitHub 日报"）——**以技术方案为准（三个）**，需求文档待同步。
  - 长期记忆后端：`TechnicalSolution.md` 5.1 写"三档后端核心阶段一次交付"，实际本次只交付 Markdown（默认）+ SQLite 两档，Mem0 档（依赖外部自托管服务）后置，见 `specs/001-runtime-core/research.md` 的 D-04。
- **Spec-Kit 工作区已就位（2026-10-05）**：`.specify/` 就地初始化，集成键 `qodercli`（技能装在 `.qoder/skills/`，以 `/speckit-<command>` 调用），配套 CLI 锁 `specify-cli` 1.0.13，宪章已升到 **v1.0.1**；`specs/001-runtime-core/` 下已产出 spec / plan / research / data-model / contracts / quickstart，主体开发仍未开始编码。Maven 工程骨架与 git 仓库已于 2026-09-27 就位（9 模块，`mvn clean package` 可过），骨架只是工程地基。
