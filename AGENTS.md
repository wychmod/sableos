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

- 仓库当前包含：`docs/` 六份文档、`design/` 设计留档、`website/` 线上站点、`README.md`、`AGENTS.md`（本文件）、`CLAUDE.md`（指向本文件的入口），以及 **Maven 多模块骨架**（2026-09-27 初始化）：parent POM + 9 个核心模块（core / provider / memory / tool / channel-cli / web / storage / cli / boot），`mvn clean package` 可通过，`sableos-boot` 产出可执行 fat JAR。
- **工程地基已于 2026-10-12 落地**（按 `sableos-init` skill 执行，提交链 `b37e5f4`→`1ac85d3` + `24dd3b8`）：依赖版本锁定、结构化日志、Actuator/Prometheus、虚拟线程、统一响应信封 + `GlobalExceptionHandler` + OpenAPI、四层质量门槛（见 5.3）、`ci.yml` 与本地钩子。
- **链路标识（traceId）已于 2026-10-14 落地**（提交链 `a206f1a`→`91505fc`→`2b3a691`）：`com.sableos.core.trace` 内核（W3C `traceparent` 解析 + 快照式跨线程传播）、web 侧 `TraceFilter`、boot 侧唯一 `TaskDecorator`、信封第 5 字段 `traceId`。审计表的 `trace_id` 列已随按节迁移落地（`db/migration/{sqlite,postgresql}/V1__llm_calls.sql`、`V2__tool_invocations.sql`；第三表 `sessions` 归 18 节）。
- 骨架按《技术方案》第 13 章第一周的 9 模块口径落地；第 10 章的 14 模块清单（多 persona / knowledge / 三渠道模块）在实施到对应能力时再补模块。各模块依赖按实施节奏补齐：storage 的 JPA / SQLite / Flyway 已就位，provider 的 Spring AI Alibaba 等随 US1 加入。
- `git` 仓库已初始化（main 分支）；**Spec-Kit 工作区已于 2026-10-05 就地初始化**（集成键 `qodercli`，技能装在 `.qoder/skills/`，配套 CLI `specify-cli` 1.0.13 锁版本），宪章 **v1.0.1**。Spec-Kit 产物按**逐节课件一个 feature** 组织（`specs/001-lesson16-provider/`、`specs/002-lesson17-react/`，每节含 spec / plan / research / data-model / contracts / quickstart / tasks.md）；早期「三合一」试跑的 `specs/001-runtime-core/`（工程地基与链路标识的任务口径曾登记于此）已随 `17e2cfc` 移除，其事实状态见第 10 章。
- 文档在 2026-09-21 做过一次纯格式整理（统一标题层级、表格分隔符、引用块、列表空行），**内容与整理前逐字一致**，以当前版本为准。

## 3. 目录分工

> 三块对外资源是**源 → 文档副本 → 线上产物**的单向关系：同一个文件只在一处维护，不要两处各存一份。拿不准某个文件该放哪，按本表判断。

| 目录 | 放什么 | 不放什么 | 是否上线 |
| --- | --- | --- | --- |
| `website/` | **线上站点本体**：`index.html` + `assets/{css,js,img,fonts}`，Pages 的唯一入口 | 设计草稿、生成脚本、评审台 | ✅ 发布 |
| `design/` | **设计过程留档**：`assets/`（标识源文件、`make-*.py` 生成器、`logo-concepts/` 备选概念）、`preview/`（评审台 `index.html`、`audit.json` 校验原始数据）、`README.md` | 站点代码、`docs/` 文档正文 | ❌ |
| `docs/` | **文档语料**：六份 `.md` + `images/`（文档内嵌图，见第 4 章） | 站点代码、设计源文件 | ❌ |
| `sableos-*`（9 个） | Maven 模块，各自只有 `pom.xml` + `src`；模块职责见第 7 章 | 文档、设计资产、生成脚本 | ❌ |
| `.github/workflows/` | CI：`pages.yml`（发布 `website/` 到 Pages + 站点健全性校验）、`ci.yml`（`mvn verify` 质量门禁 + 独立 depcheck 聚合） | 业务代码 | — |
| `.specify/` | **Spec-Kit 工作区**：`memory/constitution.md`（宪章）、`templates/`、`scripts/bash/`、内置 SDD 工作流与集成配置 | 应用代码、设计资产 | ❌ |
| `.qoder/skills/` | Spec-Kit 生成的 `speckit-*` 技能（Qoder IDE 1.24+ 扫描此目录，以 `/speckit-<command>` 调用） | IDE 本地状态（`.qoder/` 下除 `skills/` 之外的一切已在 `.gitignore` 排除） | ❌ |

顶层文件：`pom.xml` 是聚合 POM，`README.md` 对外，`AGENTS.md`（本文件）是对内的唯一权威指南，`CLAUDE.md` 只做指向；工程门槛配套：`.editorconfig`、`.githooks/`（git 原生钩子）、`.pre-commit-config.yaml`（框架版，二选一）、`config/dependency-check-suppressions.xml`（漏洞抑制，无 notes 不许抑制）。

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
| 6 | SQLite + Spring Data JPA + `MEMORY.md` 文件 | 审计表 `tool_invocations` 和 `llm_calls` **核心阶段就落库**，不是只放日志；表结构唯一权威是 Flyway 双轨目录 `db/migration/{vendor}/`（SQLite 默认档），禁止 `ddl-auto` 改结构 |
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
| 在 `synchronized` 里做阻塞调用（JDBC / LLM / 文件 / MCP） | JDK 21 会把虚拟线程**钉住**在载体线程上，高并发直接失效；这类锁改用 `ReentrantLock`（JDK 24 才修复该问题，本项目锁定 JDK 21） |
| 指望子线程继承 MDC / 用 `InheritableThreadLocal` 兜底 traceId | logback 1.5.x 的 MDC 是普通 `ThreadLocal`，虚拟线程子任务必为空；跨线程一律 `TraceContext.snapshot()` 显式传播（提交时捕获、运行时安装、finally **恢复**目标线程原状态） |
| 再注册第二个 `TaskDecorator` bean | `SimpleAsyncTaskExecutorBuilder` 用 `getIfUnique()`，多于一个即**静默失效**（`@Async` 全链路丢 traceId 且无任何报错）；全容器只允许 `ObservabilityConfig` 那一个 |
| 用 `UUID.randomUUID()` 生成 traceId | 它走共享静态 `SecureRandom`，高并发虚拟线程下是竞争点；用 `TraceIds`（`ThreadLocalRandom` 生成 32/16 位 hex） |

### 5.3 工程质量门槛（已接入 `mvn verify`，2026-10-12 起生效）

| 关卡 | 工具 | 拦截方式 |
| --- | --- | --- |
| 格式 | Spotless + google-java-format | 绑 `validate`，格式违规即挂；本地先跑 `mvn spotless:apply` 自动修 |
| 编码规约 | 阿里 P3C（8 个 `ali-*` ruleset，挂 PMD 6.55） | `mvn verify` 拦截 |
| 兜底风格 | Checkstyle（`google_checks`，只拦 error，含测试代码） | 绑 `validate` |
| 安全 | SpotBugs（effort Max / threshold Low）+ Find Security Bugs | 有 BugInstance 即失败（含测试代码） |
| 依赖 CVE | OWASP Dependency-Check（`failBuildOnCVSS=8`） | 本地默认 `-Dowasp.skip=true` 跳过；CI 独立 job 用 `-Dowasp.skip=false` 强制跑（本地实测受限，见下方结论） |

- 提交前钩子在 `.githooks/`：启用一次 `git config core.hooksPath .githooks`（或改用 pre-commit 框架读 `.pre-commit-config.yaml`，二选一）；紧急跳过 `SKIP_SABLEOS_HOOKS=1 git commit ...`。**仓库默认不启用钩子（`core.hooksPath` 未设置）**，新环境先启用再开发。
- 写 Java 的节奏：改完 → `mvn spotless:apply` → `mvn -q -B verify` 全绿再提交；CI 任一关卡不过即红、禁止合并。
- 两个已踩过的坑（勿回退）：`GlobalExceptionHandler` 的 CRLF 净化必须保持 `String.replaceAll("[\r\n]", "_")` 形态（findsecbugs 只认这种写法，换 `Pattern/Matcher` 会挂 SpotBugs）；升级 `org.webjars:swagger-ui` 时必须同步 `application.yaml` 的 `springdoc.swagger-ui.version`（值是 `@swagger-ui.version@`，构建期从父 POM 过滤注入；不同步会因 springdoc 探测失败回落内置版本，导致 `/swagger-ui/**` 404）。
- depcheck 本地可行性实测（2026-10-12）：NVD 镜像经 IPv6 可连通、数据可下载，但全量首更新出现文件传输中途截断（如 `nvdcve-2012.json.gz` 仅收 2.97/4 MB）与连接停滞（进程 10+ 分钟零进展），且单个辅助文件（`.meta` / RetireJS）下载失败即整体 `UpdateException`；强制 IPv4 反而完全超时。结论：**本地依赖 CVE 首跑不可靠，以 CI 独立 job 为权威门禁**；确需本地跑时，先在有稳定网络的环境预热 Maven 本地仓库的 `dependency-check-data` 目录后再执行。
- **Boot 3.5 线已 EOL（OSS 支持止于 2026-06-30，3.5.16 为终版），BOM 不再出安全补丁，依赖 CVE 修复只能手动覆盖**：可修构件走父 POM 属性覆盖（先例：`tomcat.version=10.1.61`、`jackson3.version=3.1.7`；覆盖前到 Maven Central 核验目标版本存在且跨过全部已知 CVE 修复线，Boot 3.5 的 Tomcat 10.1.x / Jackson 3.0.x 均有后续线内补丁可跟）。spring-framework 6.2.x 的 6 条 CVSS≥8 CVE（2026-08~09 披露）在 OSS 线**无修复版**（修复仅存在于 6.2.20 商业版与 Boot 4 的 7.0.9），已按规范写入抑制（`config/dependency-check-suppressions.xml`）。2026-10-14 决策：短期留在 3.5.16，Boot 4.x 迁移另排期（见第 10 章）。
- 响应信封是 **5 字段契约**（`code` / `message` / `data` / `timestamp` / `traceId`）：增删字段必须同步 `sableos-web/src/main/java/com/sableos/web/common/ApiResponse.java`、`ApiResponseTest` 与本条口径（本契约的活锚点即本条与代码/测试；原 `specs/001-runtime-core/contracts/http-api.md` 已随 `17e2cfc` 移除），否则 `/speckit-analyze` 报漂移；`traceId` 为 null 时也要输出（信封形状恒定，**不加** `@JsonInclude(NON_NULL)`）。任何**用户可控字符串**（含入站 `traceparent` / `X-Trace-Id` 头）入日志前一律先 `replaceAll("[\r\n]", "_")` 净化（同 CRLF 那条，勿改 `Pattern/Matcher` 形态）。
- MDC 输出的 5 个键（`traceId` / `spanId` / `sessionId` / `profileName` / `channel`）在 prod 档走 `LogstashEncoder` 的 `includeMdcKeyName` **白名单**——漏列即该字段在 JSON 里静默消失而 dev 档正常，是最难查的一类故障；新增 MDC 键必须同步补白名单（勿改用 `includeMdc=true`，白名单能防敏感值混入，见 A-03）。
- **勿「顺手修掉」`TraceIds` 上的 SpotBugs 抑制**：它用 `ThreadLocalRandom` 生成 trace-id/span-id，并对 findsecbugs 的 `PREDICTABLE_RANDOM` 做了**方法级定点抑制**（带 `justification`）。改成 `SecureRandom` 会引入更严重的问题——其 `nextBytes` 在 `synchronized` 内做阻塞 IO，在 JDK 21 会钉住载体线程，而这是每请求热路径；且 trace-id 非安全凭证（入站本就接受客户端提供的头）。撤销条件与完整理由见该抑制标注处的 `justification`（`sableos-core/src/main/java/com/sableos/core/trace/TraceIds.java`，原 `specs/001-runtime-core/research.md` D-14 已随 `17e2cfc` 移除）。`spotbugs-annotations` 以 `provided` 进父 POM 的 `dependencyManagement`，**只允许**用于确证误报的方法级定点抑制且必须写理由。
- 同类勿改：`TracedExecutorService` **继承 `AbstractExecutorService`** 而非 `implements ExecutorService`——后者会触发阿里 P3C `ServiceOrDaoClassShouldEndWithImplRule` 误报（它把 JDK 接口当成项目 Service 接口），且继承后 `submit`/`invokeAll`/`invokeAny` 都经由被包装的 `execute`，一处即覆盖全部提交入口。

## 6. 核心领域概念

- **一个目录 = 一个 Agent**：`.sableos/agents/<name>/AGENT.md`，frontmatter 是运行配置（派生为底座认识的 `Profile`），正文是任务指令；可选 `skills/` 软连接、`scripts/`、`REFERENCE.md`。
- **Skill 绑定用相对软连接，不写 frontmatter**（项目约定，见本节与 `.sableos/`；宪法 v1.0.1 尚未收录该条，若拟升格为宪章条款须由项目方另行修订，AI agent 无权自改宪章）：公共 Skill 存 `.sableos/skills/<name>/`，Agent 在自身 `skills/<name>` 下建受控相对软连接选择可见集合，**软连接集合是唯一绑定真相源**；删除被引用的 Skill 默认拒绝并返回引用 Agent。
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

1. 准备阶段：`specify init` 初始化工作区，产出 `constitution.md`（宪章）与首个 feature 的 `spec.md` / `plan.md`。工作区已于 2026-10-05 就地初始化（集成键 `qodercli`，`specify-cli` 锁 1.0.13，技能以 `/speckit-<command>` 调用），宪章 **v1.0.1**；此后演进为**逐节课件一个 feature**（`specs/001-lesson16-provider/` → `specs/002-lesson17-react/` → …），每节走 specify → clarify → plan → checklist → tasks（固定软停点）→ analyze → implement；工程地基由 `sableos-init` skill 先行落地。
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

> **豁免**：`docs/study/`（课件走读 / 学习笔记）与 `docs/reports/`（本地改动分析等生成物）不受本节约束、不跑上面的 lint，且已在 `.gitignore` 中（只留本地、不入库）。`docs/` 根目录的六份正式文档仍按本节执行。

## 10. 已知待办与文档漂移

- **图片引用悬空**：`docs/` 里 12 处图片引用（`TechnicalSolution.md` 11 处 + `sableos.md` 1 处）现统一指向同目录下的 `images/*.svg`（与 `docs/images/architecture.svg` 同一约定，不再跨到 `website/`）。`images/logo.svg` 已于 2026-09-29 换为新标识；剩余 **11 张架构/流程图尚未绘制**，引用仍悬空。清单见 `design/README.md`「已知待办」。
- **文档间修订不同步**（以《技术方案》为准）：
  - 模块数：`AiProgrammingGuide.md` 五处（第 35/176/204/405/481 行）与《技术方案》第 13 章第一周都还写 9 个模块，第 10 章已是 14 个（含 persona/knowledge/三渠道模块）。
  - CLI 子命令数：第 8.7 节写 12 个，第 10 章写 13 个（025 起加 `agent import`）。
  - 验收 Demo 数：`DemandAnalysis.md` 第 13 章写"两个 Demo"，`TechnicalSolution.md` 第 11.5 / 12 / 15 章写三个（多"每日 GitHub 日报"）——**以技术方案为准（三个）**，需求文档待同步。
  - 长期记忆后端：`TechnicalSolution.md` 5.1 写"三档后端核心阶段一次交付"，实际本次只交付 Markdown（默认）+ SQLite 两档，Mem0 档（依赖外部自托管服务）后置（原 `specs/001-runtime-core/research.md` D-04 记录已随该 spec 目录移除 `17e2cfc`，结论保留于此，待 22 节 Memory 落地时重新留研究记录）。
- **Spec-Kit 工作区已就位（2026-10-05）**：`.specify/` 就地初始化，集成键 `qodercli`（技能装在 `.qoder/skills/`，以 `/speckit-<command>` 调用），配套 CLI 锁 `specify-cli` 1.0.13，宪章 **v1.0.1**；产物按逐节 feature 组织（见第 2 章），早期 `specs/001-runtime-core/` 已随 `17e2cfc` 移除。
- **工程地基已落地（2026-10-12）**：`sableos-init` skill 十步执行完毕（提交链 `b37e5f4`→`1ac85d3` + `24dd3b8`；其任务口径原登记在 `specs/001-runtime-core/`，该目录已随 `17e2cfc` 移除）；业务开发自第 16 节起逐节推进。质量门槛、钩子启用方式与两个防回退坑见 5.3 节。Maven 工程骨架与 git 仓库已于 2026-09-27 就位（9 模块，`mvn clean package` 可过）。
- **链路标识已落地（2026-10-14）**：core 内核 / web 入口 / boot 装配 / 信封字段 / 传播测试（提交链 `a206f1a`→`91505fc`→`2b3a691`）。审计表的 `trace_id` 列已随按节迁移落地（`V1__llm_calls.sql`、`V2__tool_invocations.sql`；口径见 `specs/001-lesson16-provider/data-model.md`、`specs/002-lesson17-react/data-model.md`）；CLI 与定时入口的 `openRoot` 归 18 / 25 节。抑制与取舍理由见 `TraceIds` javadoc。
- **Sandbox SMTP 白名单口径（2026-10-27 定稿）**：`TechnicalSolution.md` §6.7 曾把 `WhitelistSandbox` 的配置写成四键（含 `smtp.allowed_endpoints`）并列出 `checkSmtpEndpoint`，但课件 23/24 节与 `ActionType` 四值均只覆盖 file / shell / http 三类，邮件通知 `EmailNotifyAdapter` 本身也属扩展阶段。核心阶段按课件三项落地：只实现路径 / 命令 / 域名三类校验，不新增 SMTP 动作类型、不写不可达的 SMTP 校验方法；`smtp.allowed_endpoints` 保留在 `application.yaml` 作为扩展阶段预留，核心阶段不读取。已于 2026-10-27 按本条口径复核并同步 `TechnicalSolution.md` §6.7（三键 + SMTP 扩展阶段预留）。
- **Sandbox 白名单管理接口的域名口径（2026-10-29 定稿）**：白名单 CRUD 的 `HTTP_DOMAIN` 只接受 ASCII 主机名，非 ASCII 一律 400 并提示改用 punycode（`xn--...`）提交。依据是 JDK 21 实测：`URI.getHost()` 对非 ASCII 主机名返回 `null`（全角点 `．` 同样为 `null`），而 `WhitelistSandbox.checkHttpUrl` 在 host 为 null 时判"URL 缺少主机名"拒绝——若允许存 punycode，Agent 用 `https://中文.example.com/` 请求依然被拒且理由误导。另外 IDN 还带来同形异义字（西里尔 `а` vs 拉丁 `a`）的"假放行"错觉与全角字符被静默 NFKC 化的风险。将来要支持，须连同 `getRawAuthority()` 回退 + `IDN.toASCII` 归一一起改（那时会动 24 节冻结的 `checkHttpUrl`，需回归现有 29 例）。设计口径见 `docs/requirements/Sandbox白名单管理接口设计.md` §6.3 / §7.3 / §12.1-#8。
- **Sandbox 白名单管理接口（008-sandbox-whitelist-admin，2026-10-29 落地）**：白名单从"启动绑定、运行期只读"改为"库为运行时事实源 + 运行期增删改查"。要点与三处**与设计稿不一致**的地方：
  - 装配与首次导入落 **`sableos-cli`**（`SableOsRuntime` + `cli/runtime/SandboxWhitelistBootstrap`），不是设计稿写的 `sableos-boot`。依据：第 18 节起真正的装配根在 cli（唯一声明 `@EnableJpaRepositories`/`@EntityScan` 处），boot 只剩打包职责；oryxos 同样把播种放 `OryxOsRuntime`。
  - 为承载上述装配，`sableos-cli` 新增 `sableos-tool` 依赖（cli→tool→core，不成环）；`sableos-storage` 新增 **test scope** 的 `sableos-tool` 依赖（仅测试用 `WhitelistRules`）。
  - Controller 测试用 `MockMvcBuilders.standaloneSetup` 而非 `@WebMvcTest`（sableos-web 内无 `@SpringBootApplication`，切片测试无法定位配置类）；「开关关闭返回 404」移到 boot 模块的集成测试，因为 `@ConditionalOnProperty` 是装配层行为。
  - **播种判据是独立标记表 `sandbox_whitelist_seed`**（行存在即已导入），不是条目表判空——否则删光条目后重启会把 `application.yaml` 再插一遍。
  - **`WhitelistSandbox` 不再标 `@Component`**：初始策略必须"先导入/从库恢复再构造"，组件扫描无法表达该顺序且会尝试无参实例化而失败；改由 `SableOsRuntime` 的 `@Bean` 显式创建，并额外以 `Sandbox` 接口类型暴露同一实例供工具注入。
  - FILE_PATH 入库形态 = `Path.normalize()` + 分隔符统一 `/`（盘符大小写不归一）；HTTP_DOMAIN 只接受 ASCII（口径见上一条）。
  - 本 feature 只写操作日志（`DefaultSandboxWhitelistAdmin.logChange`），**不建白名单审计表**（设计稿 §11 口径）。
- **定时任务调度（009-lesson25-scheduler，2026-10-29 落地）**：第三触发源按 §8.5 落 `sableos-core` 的 `AgentScheduler`（`registerAll` / `runOnce`），由 `serve` / `gateway` 启动后显式注册，`chat` 不调度（§8.6）。四条需记住：
  - **`sableos-core` 自本节起 compile 依赖 `spring-context`**（仅用 `TaskScheduler` / `CronTrigger`），core 不再是「零 Spring」模块；依据是 §8.5 明定该类归 core 并使用 Spring 调度能力。
  - **任务执行权键 = `(profileName, scheduleId)` 的私有 record**，不是裸 `scheduleId`：两个 Agent 各自声明 `id: daily` 是两条独立任务，不得互相跳过（FR-004），也与 §8.5 第 28 节的 `(profile_name, schedule_key)` 口径一致。
  - **同一 Agent 的多条 schedules 共享 `scheduler:scheduler:<profile>` 会话**，`runOnce` 在任务锁内再按会话加**阻塞锁**串行化；否则两边各取历史快照、`save` 整列覆盖 `messages_json`，后写者抹掉前者（lost update）。该锁是进程内的，多实例竞争仍归扩展阶段分布式协调。
  - 注册时机用 `ServeCommand.startScheduler(context)` 显式调用而非 `@PostConstruct`：三种运行模式共用同一装配根，自注册会让 `chat` 也跑起定时任务。
- **Web Service 门面（010-lesson26-web-service，2026-10-29 落地）**：`sableos-web` 新增六个 Controller、10 个 `/api/v1` 端点（会话 4 / Agent 调用 1 / 信息查询 3 / 系统状态 2）、CORS（仅 `/api/**`）与 60 秒调用上限；复用既有 `ApiResponse` 信封与 `GlobalExceptionHandler`。五条口径需记住：
  - **`sableos-web` 新增 `sableos-tool` 编译依赖**（`GET /tools` 列 `ToolRegistry.all()`，`web→tool→core` 不成环）；记忆查询走 core 的 `MemoryProvider`，不新增 web→memory/provider 模块边。
  - **core 新增 `ProviderHealthProbe` / `ProviderStatus` / `ProviderFailureException`**，provider 新增 `HttpProviderHealthProbe`（对 base-url 发 `HEAD`，2s connect / 3s read，任何 HTTP 响应即可达，不调模型、不耗 token）并由 `ProviderConfiguration` 装配；提供方调用失败包装为 `ProviderFailureException` → 503 通用话术（不回显原始 message）。
  - **`SessionManager` 新增 `archive(String): boolean`**：软归档置 `status=archived` + 首次写 `archived_at`，行保留；归档后 `GET /sessions/{id}` 仍可查，重复归档幂等。`session_id` 拼接仍只在 `SessionManager.sessionId` 一处。
  - **60 秒超时的执行器**：Boot 的 `TaskExecutionAutoConfiguration` 因容器已有 `agentTaskScheduler`（也是 `Executor`）而**不会创建 `applicationTaskExecutor`**；故 `sableos-web` 的 `ApiConfiguration` 显式声明仅服务 Agent 调用的虚拟线程执行器，并复用容器唯一 `TraceTaskDecorator`。超时 `cancel` 后返回 504，底层调用允许在后台收尾。
  - **课件“排除 `OpenAiAutoConfiguration`”在本仓库不适用**：classpath 上只有 `spring-ai-openai` raw jar，没有 starter/autoconfig；Boot 3.x 排除不存在的自动配置类会让 `serve` 启动失败。虚拟线程与 8080 端口既有 `application.yaml` 已配置，未改动。管理台前端/静态资源/SPA 回落/前端风格 skill 按用户决定不交付，由独立 agent 基于本节接口实现。
- **人推链路串联（027-lesson27-human-trigger-flow，2026-10-31 落地）**：集成课不新增模块，交付"一次对话走通想→查→答→记账"及对账 harness。要点：
  - **`GET /api/v1/sessions` 为第 11 个核心端点**：`SessionManager.listRecent(int)`（core 接口 + `JpaSessionManager` 实现，`findAll` 后按 `lastActiveAt` 倒序取前 N），Controller 侧支持 `?status=` 过滤；响应 `SessionSummaryView` 只带元数据与 `messageCount`，**不带**对话正文。TechnicalSolution §7.2 与 README 端点表已同步（26 节的"10 个端点"口径自此为 11 个）。
  - **`mock` 是显式映射表里的第三类 provider**（`ProvidersProperties.TYPE_MOCK`）：无 api-key、无 base-url，`ProviderChatModelFactory` 直接构造 `MockChatModel`；`HttpProviderHealthProbe` 对 mock 直接判可达，不回落到 DashScope 默认地址探测。`MockChatModel` 无实例状态——prompt 里出现 `Tool[save_memory]:` 即返回最终答复，否则发一次 `save_memory` 工具调用；**只有模型是假的**，其余链路全真。
  - **工作区根支持 `sableos.root` 系统属性覆盖**：`application.yaml` 的 `workspace.root: ${sableos.root:.sableos}`，`AgentConfiguration` / `ReactConfiguration` / `ToolConfiguration` 的 `.sableos` 硬编码改为读 `workspace.root`（agents / skills / mcp_servers.yaml / SQLite 一起跟随）；轻命令 `Workspace.root()` 也按 `sableos.root` → `WORKSPACE_ROOT` → `.sableos` 依次解析。默认值不变。
  - **POM 的 `excludedGroups` 改为项目属性 `${excludedGroups}`（默认 `integration`）**：原硬编码让 `-DexcludedGroups=` 无法覆盖，`ProviderSmokeIT` 文档里的手动命令实际跑不起来；现在 `mvn test -DexcludedGroups= -Dgroups=integration -Dtest=<IT>` 可用。
  - **harness**：`MockProviderFlowTest`（手工装配、无 key）与 `MockAgentE2ETest`（`@SpringBootTest` 真端口 + 临时 SQLite + `mock`）随 `mvn verify` 跑；`HumanTriggerFlowIT`（`@Tag("integration")`，真 key）覆盖三支柱 + 三类失败记账 + CLI/REST 三面同源。管理台"会话"页已接 `GET /sessions` 真实列表。
- **Boot 4.x 迁移待排期（2026-10-14 决策：短期留在 3.5.16 过渡）**：3.5.16 是 Boot 3.5 OSS 线终版，Spring Framework 6.2.19 的 6 条 CVSS≥8 CVE 在 OSS 线无修复版，过渡期以抑制放行（见 5.3 节，抑制移除条件即迁移落地）。迁移须联动 Spring AI 1.1.x→2.x、springdoc 2.8→3.x、JUnit 6 等，单独走 Spec-Kit 任务排期，勿在业务 task 里顺手升级。
