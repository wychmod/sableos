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
7. **顺延项（`【顺延·XX 节后】`）在后续章节被回收时，必须回到原 section 的 `tasks.md` 补勾并写明回收落点**，例如：

   ```markdown
   - [x] T009 [US3] 【顺延·24 节后】实现 `NotifyTools`（…）
     - **回收记录（2026-11-01 复核）**：已由 [005 T027](../005-lesson20-tool/tasks.md) 落地；`enforce` 在 `NotifyTools.java:100`、`send` 在 `:101`。
   ```

   并在原 spec 的 `acceptance-report.md` 里注明“第二批 harness 已回收”及其实跑结果。

   > **为什么必须做**：`tasks.md` 的勾选状态是复查时判断“这节到底做完没有”的唯一依据。顺延项不补勾，后续复查会把已完成的工作误判为未完成（`004` 的 T009/T010、`011` 全节、`014` 全节都实际踩过这个坑）；补勾而不写回收落点，则下次复核又要重新追一遍来源。

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

> 三层：**A. 未完成项**；**B. 已落地索引**（细节在 `specs/`）；**C. 硬约定**——踩坑才写下的护栏，**改动前必读，别因为"像历史记录"就删**。

### A. 未完成项

| 事项 | 状态与口径 |
| --- | --- |
| 架构/流程图 | `docs/` 12 处图片引用（`TechnicalSolution.md` 11 + `sableos.md` 1）统一指向 `images/*.svg`；`logo.svg` 已于 2026-09-29 换新。**剩余 11 张未绘制，引用悬空**（清单见 `design/README.md`）。 |
| 文档修订不同步 | 以《技术方案》为准：① 模块数 `AiProgrammingGuide.md` 五处（35/176/204/405/481 行）与 §13 第一周仍写 9，实际 14；② CLI 子命令 §8.7 写 12、§10 写 13（025 起加 `agent import`）；③ 验收 Demo `DemandAnalysis.md` §13 写"两个"、`TechnicalSolution.md` §11.5/12/15 写三个（多"每日 GitHub 日报"）——**以技术方案为准**；④ 长期记忆 §5.1 写"三档一次交付"，实际只有 Markdown（默认）+ SQLite，Mem0 后置（原 D-04 随 `17e2cfc` 移除，22 节 Memory 落地时补研究记录）。 |
| Boot 4.x 迁移 | 3.5.16 是 Boot 3.5 OSS 线终版，Spring Framework 6.2.19 的 6 条 CVSS≥8 CVE 在 OSS 线**无修复版**，过渡期以抑制放行（5.3 节，**抑制移除条件 = 迁移落地**）。须联动 Spring AI 1.1.x→2.x、springdoc 2.8→3.x、JUnit 6，单独排 Spec-Kit 任务，**勿在业务 task 里顺手升级**。 |

### B. 已落地索引

| 节 | feature | 一句话 |
| --- | --- | --- |
| 工程地基 | `sableos-init` skill | 依赖锁定、结构化日志、Actuator/Prometheus、虚拟线程、信封 + `GlobalExceptionHandler` + OpenAPI、四层门槛（5.3）、`ci.yml`；`b37e5f4`→`1ac85d3` + `24dd3b8` |
| 链路标识 | — | `com.sableos.core.trace` 内核、`TraceFilter`、boot 唯一 `TaskDecorator`、信封第 5 字段；`a206f1a`→`91505fc`→`2b3a691` |
| 16–20 | `001-lesson16-provider` ~ `005-lesson20-tool` | Provider 抽象、自实现 ReAct、CLI、通知抽象、Plugin Tool 子系统 |
| 22 / 24 | `006-memory-service-backends` / `007-lesson24-sandbox` | 长期记忆多后端；Sandbox 三类校验 |
| 25 / 26 | `009-lesson25-scheduler` / `010-lesson26-web-service` | 定时调度（内存 cron）；Web Service 门面 |
| 27 / 28 | `011-lesson27-human-trigger-flow` / `012-lesson28-scheduler-subsystem` | 人推链路串联与对账 harness；定时子系统 + 通知渠道注册表（V6/V7） |
| 29 / 30 | `013-lesson29-pluggable-agent` / `014-lesson30-dynamic-agent` | 插件化 Agent；动态管理 Agent（三条录入路径殊途同归） |
| 白名单管理 | `008-sandbox-whitelist-admin` | 白名单从"启动绑定、只读"改为"库为事实源 + 运行期 CRUD" |
| Spec-Kit | — | 2026-10-05 就地初始化，键 `qodercli`（技能在 `.qoder/skills/`，以 `/speckit-<command>` 调用），CLI 锁 1.0.13，宪章 **v1.0.1**；早期 `specs/001-runtime-core/` 随 `17e2cfc` 移除 |

### C. 硬约定

- **Sandbox（24 / 008）**：① 只做 **file / shell / http 三类**校验，**不新增 SMTP 动作类型**、不写 `checkSmtpEndpoint`（`smtp.allowed_endpoints` 仅预留不读取）；② `HTTP_DOMAIN` **只接受 ASCII**，非 ASCII → 400 提示 punycode——JDK 21 `URI.getHost()` 对非 ASCII 返回 `null`（全角点 `．` 同），会被判"缺少主机名"，且 IDN 有同形字（西里尔 `а` vs 拉丁 `a`）假放行风险；要支持须 `getRawAuthority()` 回退 + `IDN.toASCII` 归一，**需回归 24 节冻结的 `checkHttpUrl` 现有 29 例**；③ **装配根在 `sableos-cli`**（`SableOsRuntime` + `SandboxWhitelistBootstrap`）不在 boot——18 节起 cli 是唯一声明 `@EnableJpaRepositories`/`@EntityScan` 处；依赖边 `cli→tool`，`storage` 以 **test scope** 引 `tool`（仅用 `WhitelistRules`）不成环；④ **播种判据 = 独立标记表 `sandbox_whitelist_seed`**（行存在即已导入），**不是条目表判空**，否则删光条目后重启会把 `application.yaml` 再插一遍；⑤ **`WhitelistSandbox` 不标 `@Component`**：初始策略必须"先导入/从库恢复再构造"，组件扫描表达不了该顺序且会尝试无参实例化失败，由 `SableOsRuntime` 的 `@Bean` 显式创建并以 `Sandbox` 接口暴露**同一实例**；⑥ **FILE_PATH 入库** = `normalize()` + 分隔符统一 `/`（盘符大小写不归一）；**根在运行时取真实路径**——`WhitelistPolicy` 构造器统一走 `resolveRealPath`，与 `checkFilePath` 对**待查路径**的解析共用同一函数，两侧任一不解析就误判越界（Windows 8.3 短名 `RUNNER~1`、macOS `/var→/private/var` 各挂过一次），解析只在快照构造时不在校验热路径；⑦ 只写操作日志（`logChange`），**不建白名单审计表**；Controller 测试用 `MockMvcBuilders.standaloneSetup`（web 无 `@SpringBootApplication`，切片定位不到配置类），"开关关闭返回 404"因为 `@ConditionalOnProperty` 是装配层行为，放 boot 集成测试。
- **定时任务（25 / 28）**：① **`sableos-core` compile 依赖 `spring-context`**（仅 `TaskScheduler` / `CronTrigger`），core 不再是"零 Spring"模块；② **执行权键 = `(profileName, scheduleId)`**，**不是裸 `scheduleId`**——两个 Agent 各声明 `id: daily` 是两条独立任务，不得互相跳过（FR-004）；③ 同一 Agent 的多条 schedules **共享 `scheduler:scheduler:<profile>` 会话**，`runOnce` 在任务锁内再按会话加**阻塞锁**串行化，否则各取历史快照、`save` 整列覆盖 `messages_json`（lost update），锁是进程内的、多实例竞争归扩展阶段；④ **注册用 `ServeCommand.startScheduler(context)` 显式调用，不用 `@PostConstruct`**——三种模式共用装配根，自注册会让 `chat` 也跑定时；⑤ 表（Flyway 双轨 **V6**）`scheduled_tasks`（`schedule_id` 自增 PK、`profile_name`+`schedule_key` 唯一、`cron`、`zone`、`enabled`、`retired`、统计列）与 `task_executions`，**定义仍只来自 `AGENT.md`**，管理端只管状态/历史/启停/立即执行；⑥ **v2 主路径 + v1 兼容**——`/api/v2/schedules` 四端点 + `/api/v2/agents/{profileName}/schedules/{key}/run`，v1 按任务键兼容、多 Agent 撞 key → **409**，管理台只调 v2；`reconcile(profileName, key, ...)` 复用 `schedule_id`，删配置或改 key → 旧行 `retired=true`（不参与调度/列表但保留历史，再现则复活）；⑦ 通知渠道（**V7**）`notify_channels` 核心阶段只接受 `webhook`，`NotifyTools` 每次从 `NotifyChannelStore` 解析（缺省/空白/`default` 取首个）、**改渠道无需重启**、发前仍 `Sandbox.enforce`，契约在 core、JPA 在 storage、Bean 在 `SableOsRuntime`；⑧ **稳定性支柱（勿降级为"依赖对端自觉超时"）**——`AgentScheduler` 单次执行与 Web 入口同受 **60 秒**上限（`CALL_TIMEOUT`，`Future#get` 截断，超时取消并写 `success=false` + 可读原因），共享 `RestClient` 显式 connect 10s / read 30s，`ReActLoop` 每轮与 `ToolExecutor` 每次调用各打一条主线日志。
- **Web 门面（26）**：① **`sableos-web` compile 依赖 `sableos-tool`**（`GET /tools` 列 `ToolRegistry.all()`），记忆查询走 core 的 `MemoryProvider`，**不新增 web→memory/provider 边**；② core 的 `ProviderHealthProbe` / `ProviderStatus` / `ProviderFailureException`，provider 的 `HttpProviderHealthProbe`（`HEAD`，2s/3s，**不调模型不耗 token**）由 `ProviderConfiguration` 装配，失败 → `ProviderFailureException` → **503 通用话术不回显原始 message**；③ **`SessionManager.archive(String): boolean`** 置 `status=archived` + 首次写 `archived_at`、行保留，归档后仍可查、重复幂等，**`session_id` 拼接仍只在 `SessionManager.sessionId` 一处**；④ **60 秒执行器**——容器已有 `agentTaskScheduler`（也是 `Executor`）→ Boot **不会创建 `applicationTaskExecutor`**，故 `ApiConfiguration` 显式声明仅服务 Agent 调用的虚拟线程执行器并复用容器唯一 `TraceTaskDecorator`，超时取消 → 504、底层允许后台收尾，虚拟线程与 8080 端口既有 `application.yaml` 已配置未改动；⑤ **课件"排除 `OpenAiAutoConfiguration`"在本仓库不适用**——classpath 只有 `spring-ai-openai` raw jar 无 starter/autoconfig，排除不存在的自动配置类会让 `serve` 启动失败。
- **人推链路与管理台（27）**：① **`GET /api/v1/sessions` 是第 11 个核心端点**——`listRecent(int)` / `listRecent(int, status)`（`findByStatus` + `PageRequest` **在 SQL 侧**过滤与截断，不是先取 100 再内存筛），`SessionSummaryView` 只带元数据 + `messageCount` **不带正文**，四处口径已统一为 11（TechnicalSolution §7.2、README 能力表与端点表、`frontend/README.md`）；② **管理台构建链不在 Maven 里**——`static/admin/**` 是**入库的构建产物**，`mvn package` **不会重建前端**，**改完前端源码必须手工 `npm ci && npm run build`** 否则 jar 里仍是旧页面（步骤见 README「构建管理台与启动」）；③ **`mock` 是显式映射表里的第三类 provider**（`ProvidersProperties.TYPE_MOCK`）——无 api-key/base-url 直接构造 `MockChatModel`，健康探针直接判可达不回落 DashScope 默认地址；`MockChatModel` 无实例状态，prompt 出现 `Tool[save_memory]:` 即回最终答复否则发一次 `save_memory`，**只有模型是假的、其余链路全真**；④ **工作区根支持 `sableos.root` 覆盖**——`workspace.root: ${sableos.root:.sableos}`，三个 Configuration 的 `.sableos` 硬编码已改为读 `workspace.root`，轻命令按 `sableos.root` → `WORKSPACE_ROOT` → `.sableos` 解析，**默认值不变**；⑤ **`excludedGroups` 是项目属性（默认 `integration`）**——原硬编码让 `-DexcludedGroups=` 无法覆盖，现在 `mvn test -DexcludedGroups= -Dgroups=integration -Dtest=<IT>` 可用。
- **Agent 加载与插件化（29）**：① **未注册工具只告警不丢弃**——派生后逐个比对记 WARN 仍完成登记（MCP 工具可能晚于 Agent 注册，丢弃会造成"工具到齐了但 Agent 没了"），装配层从 `ToolRegistry` 取名单**只传 `Set<String>`**、core 不 import tool 类型（`ArchitectureTest` 红线）；② **`deriveProfile(Path)` 是运行时唯一派生入口**——启动扫描与 30 节的 API/文件监听都走它，异常类型与消息才可能一致，字符串重载只给纯文本；③ **`ProfileRegistry.remove(String): boolean`** 命中并移除返回 true，**`get` 保持返回 `null`**（改 `Optional` 会破全部调用方）；④ **`scheduledTasks` 句柄表键为 `long scheduleId`**（本仓库 `schedule_id` 是自增 `long`，**不用课件写法的 `String`**），重复登记 `reconcile` 复用同一 id、旧句柄 `cancel(false)`（**不中断正在执行那次，只阻止后续触发**），退役时同步清理句柄表否则 `hasScheduledTask` 误报；⑤ **`skills/` 只接受软连接**——放普通 `.md` 会让 `ContextLoader` 抛「Skill 绑定越界（目标不在 Skill 根目录内）」导致**整个 Agent 跑不起来**（课件 §1.3 示例是笔误，按技术方案 §11.1），示例夹具**不含** `skills/`（git 存不了空目录，`core.symlinks=false` 的机器会把软连接还原成普通文件），由测试在复制后的临时根建链、真实工作区按 quickstart §4 用 `mklink /D` 或 `ln -s`。
- **动态管理 Agent（30）**：① **`register(Path)` 是唯一登记入口，`delete` 顺序不能反**——`unregisterProfile` → `registry.remove` → `agentStore.archive`，先停定时避免窗口期 cron 触发已注销 Agent，归档放最后保证前两步失败时磁盘仍完整（harness 用 `InOrder` 钉死）；② **`unregisterProfile` 按 Agent 名筛句柄表**，不能按 `profile.schedules()` 反查（旧配置有、新配置已删的那条在 `schedules()` 里查不到），`cancel(false)` 不中断正在执行那次，**不动** `taskLocks`/`sessionLocks`（重注册复用同一把锁）；③ **`WatchService` 不递归，必须两层 watch**——根盯子目录增删、每个 Agent 子目录各盯 `AGENT.md`（大小写不敏感），监听循环跑在**单守护线程**，`ContextClosedEvent` 里 `stop()` 关服务，否则 `take()` 不醒、停机要等满超时；④ **per-agent 记忆靠 `ToolExecutionContext`**——`ThreadLocal<String> agentName` **非 `InheritableThreadLocal`**（§5.2），`ToolExecutor` 执行前后置入/清除，`MemoryServiceImpl` 读路径置入并**复原原值**（不是清空，否则抹掉外层上下文），有合法上下文落 `agents/<name>/MEMORY.md` 否则回退 `memory/<profile>/MEMORY.md`，`LongTermMemoryStore` 签名不变，**全局 `GET /api/v1/memory` 已移除**改 `GET /api/v1/agents/{name}/memory`；⑤ **一句话生成走 `ProviderService.chat` + 临时 Profile**（`agent-author` / `agent-author:<name>`）天然落 `llm_calls`，`sableos.author.model` **留空则 503**（不向端点发空模型名），生成**不落盘不注册**、走与写盘同一套 `AgentLoader.parse`（**草稿能过 = 写盘能过**），产出路径拒绝对路径 / `..` / `skills/` 前缀；⑥ **工作区文件接口只放行 `agents/` 与 `archive/`**——边界单点在 `AgentStore.resolveWithin` 不另写一套，`message` 固定话术不含用户输入，写 `AGENT.md` **不裸写盘**走 `saveFiles`（校验 + 重注册），因为 macOS 的 `WatchService` 不监听子目录内变更、裸写会变成"保存了但没生效"；⑦ **Windows 路径坑**——`Path.of("/etc/passwd").isAbsolute()` 返回 `false`（缺盘符），只靠它会让前导斜杠路径绕过校验，`AgentStore` 另做 `looksAbsolute`（前导 `/` 与盘符）；⑧ **`AGENT.md` 文件名口径单点在 `AgentDefinitionFile`**（`matches` / `matchesPath` / `locate` / `existsIn`）——监听侧、读取侧（`AgentLoader`/`AgentStore`/`ContextLoader`）、web 侧、CLI **全部走它**，**不得再各自写 `dir.resolve("AGENT.md")` 或 `equalsIgnoreCase`**；吃过亏：只改监听侧、读取侧仍精确匹配 → Linux 上"监听命中 → 派生抛缺少 AGENT.md"而 Windows/macOS 因文件系统不敏感复现不了；写入侧目录里已有变体时 `writeAll` **按变体写**，否则一次保存会留下两份定义；**`AGENTS.md` 与 `CLAUDE.md` 不是定义文件**，永远不该被命中。

### D. harness 索引

`AgentStoreTest` / `AgentDefinitionFileTest` / `AgentLifecycleServiceTest` / `WorkspaceWatcherTest` / `GenerateTest` / `AgentSchedulerRegisterTest` / `MarkdownMemoryStoreTest` / `ToolExecutorTest` / `WorkspaceApiControllerTest` / `AgentApiControllerTest` / `MockProviderFlowTest` / `MockAgentE2ETest` / `ScheduledTaskE2ETest` / `MultiAgentIsolationTest` 随 `mvn clean verify` 跑，**无 key**；`HumanTriggerFlowIT` / `SchedulerFlowIT` / `RestartRecoveryIT` / `WebSmokeIT` / `ProviderSmokeIT` 标 `@Tag("integration")`，需 key 或手动跑。
