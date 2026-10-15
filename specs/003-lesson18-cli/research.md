# Phase 0 Research: CLI 与会话持久化（第 18 节）

> 决策记录。每条含 Decision / Rationale / Alternatives。附录 A 是第三方 API 的本地核实证据（软门禁 #5）。
> 本节**不新增任何第三方依赖**（软门禁 #6）——所有用到的库都已在锁定 BOM 与既有模块 POM 内。

## D-01 会话持久化的分层：core 契约 + storage JPA 实现（依赖倒置）

**Decision**: `SessionManager` 契约放 `sableos-core`（`com.sableos.core.session`），`extends` 既有 `SessionStore`（加 `getOrCreate`/`get`，`save` 继承）；JPA 实现 `JpaSessionManager` 放 `sableos-storage`。`JpaSessionManager` 取代 `InMemorySessionStore` 成为 `AgentService` 的 `SessionStore` bean。

**Rationale**: 与 §28 `ScheduledTaskStore`（契约在 core、`JpaScheduledTaskStore` 在 storage）既定先例一致；`AgentService`（core）依赖 `SessionStore`（core）而非具体实现，core 保持框架无关；`session_id` 单点公式随契约落 core，满足不变量 H4④"只在 core 侧单点拼接"。

**Alternatives considered**:
- 整个 `SessionManager` 具体类放 storage（无 core 契约）：`session_id` 公式会落 storage，与 H4④"core 侧单点"口径冲突；且 core 侧若将来要按标识取会话得反向依赖 storage。**否决**。
- 让 `SessionManager` 不 `extends SessionStore`、二者并列：`AgentService` 只认 `SessionStore.save`，若并列则要么 `AgentService` 改依赖 `SessionManager`（改 L17 公共构造签名，软门禁 #4 更重），要么装配两个 bean。`extends` 是**追加式**、零破坏。**采纳 extends**。

## D-02 `session_id` 单点拼接公式

**Decision**: `channel + ":" + userId + ":" + profileName`（如 `cli:wang:default`），实现为 core `SessionManager` 接口上的 `static String sessionId(String channel, String userId, String profileName)`。所有入口只提供三元组、调此方法，绝不各自拼串。channel 取值与 `TraceContext.CHANNEL_CLI/WEB/SCHEDULER`（`cli`/`web`/`scheduler`）对齐。

**Rationale**: clarify 已拍板。冒号分隔可读、稳定、跨入口一致；静态方法在 core 契约上是全代码库唯一实现处，收尾用 grep 核验无第二处 `":"` 拼接即满足 SC-002 / H4④。

**Alternatives considered**: 竖线/下划线分隔、或 `profile` 在前——无实质差异，按 clarify 选定冒号 + `channel:user:profile` 顺序。哈希/UUID 派生 id：不可读、且"同一三元组收敛同一 id"的幂等性反而要额外保证，**否决**（直接拼接天然幂等）。

## D-03 JPA 实体命名 `SessionEntity`（不与 core `Session` 撞名）

**Decision**: 持久化实体命名 `SessionEntity`（`com.sableos.storage`，`@Table(name="sessions")`，`@Id` 为 `sessionId` 字符串、**非自增**）；`SessionRepository extends JpaRepository<SessionEntity,String>`。`JpaSessionManager` 负责领域 `Session` ↔ `SessionEntity` 映射。

**Rationale**: clarify 已拍板。core 已有领域类 `com.sableos.core.session.Session`；storage 再叫 `Session` 会同仓两名、import 易混。既有 storage 实体（`LlmCall`/`ToolInvocation`）按表名直命，但 `sessions` 的领域名已被占用，故加 `Entity` 后缀区分"持久化行"与"领域聚合"。

**Alternatives considered**: storage 包内仍叫 `Session`（课件字面）：Java 合法但混淆成本高，**否决**。`SessionRecord`/`SessionRow`：与既有 `record` 语义/命名习惯冲突，`SessionEntity` 最直白。**采纳 SessionEntity**。

## D-04 领域 `Session` 的持久化重建：追加 `rehydrate(...)` 静态工厂

**Decision**: 给 core `Session` 追加一个 `public static Session rehydrate(String id, String profileName, String channel, String userId, String status, Instant createdAt, Instant lastActiveAt, List<SessionMessage> messages)`，内部走一个新增的私有全字段构造；既有 4 参公共构造与所有 getter/`append*` 方法签名**不变**（既有构造改为委托私有构造，`createdAt=now`/`status=active`/空历史）。

**Rationale**: `SessionManager.get/getOrCreate` 命中既有行时，必须还原带历史与**原始**创建/活跃时间戳的 `Session`；既有构造把 `createdAt` 钉成 `now`、历史钉空，无法承载恢复态。L17 的 `Session` javadoc 已明载"跨重启持久化与状态机归 18 节……以免 18 节落地时回改"——本节即该 anticipated 演进。改动是**追加式**：L17 的 `Session` 相关测试与调用方零改动即通过。

**Alternatives considered**:
- 用既有 `append*` 逐条重放历史：`appendAssistantMessage(ProviderResponse)` 只吃 `ProviderResponse`，无法从反序列化出的任意 `SessionMessage`（可能是 TOOL 条目、可能带 `toolCalls`）还原；且每次 `append*` 都 `touch()` 把 `lastActiveAt` 刷成 now，丢失原始时间戳。**否决**。
- 在 storage 侧另建一套可持久化的会话模型、不经 core `Session`：与 core 领域模型双轨，`AgentService.process(Session,...)` 仍要 core `Session`，映射反而更绕。**否决**。

## D-05 `messages_json` 的序列化：Jackson 直序列化 `List<SessionMessage>`

**Decision**: `JpaSessionManager` 用 core 已依赖的 `jackson-databind` `ObjectMapper` 把 `session.messages()`（`List<SessionMessage>`）整体序列化成 JSON 存 `messages_json` 单列；读回时反序列化为 `List<SessionMessage>` 交 `Session.rehydrate`。核心阶段不按条拆表。

**Rationale**: `SessionMessage`（record：`role`/`content`/`toolCalls`/`toolName`/`success`）与 `ToolCallRequest`（record：`id`/`name`/`argumentsJson`）、`MessageRole`（enum）均为 Jackson 2 原生可序列化类型（record 自 Jackson 2.12 起支持，枚举按 name 往返）。单列 JSON 契合课件"整体序列化、不按条拆表"。`ObjectMapper` 配为对未知字段宽容、空历史序列化为 `[]`。

**Alternatives considered**: 自建 DTO 再映射——多一层无谓映射，record 已可直接序列化，**否决**。按条拆 `session_messages` 表——越界（课件明确核心阶段不做），**否决**。

**核实点（写前 H3）**: 实现前用一个小 round-trip 单测确认 `SessionMessage`（含带 `toolCalls` 的 ASSISTANT 条目、带 `toolName/success` 的 TOOL 条目、`success=null` 的 USER 条目）序列化再反序列化后逐字段相等；若 record 反序列化因缺参数名报错，则给 `ObjectMapper` 注册 `ParameterNamesModule` 或在 `SessionMessage` 上补 `@JsonProperty`（不改语义）。

## D-06 程序入口与重命令启动：cli 为聚合入口 + `SableOsRuntime`（对齐 oryxos）

**Decision**（参照兄弟仓库 `D:\idea\oryxos` 的成熟落地，2026-10-15 核实）：
- fat JAR 仍由 `sableos-boot` 产出，但 `spring-boot-maven-plugin` 的 `<mainClass>` 指向 **`com.sableos.cli.SableOsCli`**（oryxos：boot pom `<mainClass>io.oryxos.cli.OryxOsCli</mainClass>`）。AGENTS.md「boot 产出 fat JAR」不变。
- `SableOsCli.main` = 纯 Picocli：`System.exit(new CommandLine(new SableOsCli()).execute(args))`，**不含 Spring**（oryxos `OryxOsCli` 同）。
- 新增 **`SableOsRuntime`（在 sableos-cli）** = `@SpringBootApplication(scanBasePackages="com.sableos") @EnableJpaRepositories("com.sableos.storage") @EntityScan("com.sableos.storage")` + 本节新 bean（`SessionManager`→`JpaSessionManager`、`CliChannel`）。它是重命令启动的上下文源（oryxos `OryxOsRuntime` 同，且 JPA 扫描注解只此一处，避免与 boot 应用类重复注册）。
- **重命令自启动**：`chat`/`serve`/`gateway` 在 `run()` 内 `try (var ctx = new SpringApplicationBuilder(SableOsRuntime.class).web(...).bannerMode(OFF).run()) { ctx.getBean(...)... }`（oryxos `ChatCommand` 逐字同构：chat 用 `WebApplicationType.NONE`、serve 用 SERVLET）。**轻命令零 Spring**（oryxos：`session list` 走 `LightDbConfig` 裸 JDBC）。
- `sableos-cli` 因此成为**聚合模块**：新增依赖 core / storage / channel-cli / picocli / spring-boot-starter / spring-boot-starter-data-jpa / sqlite-jdbc / snakeyaml（oryxos-cli 同款）。**cli 不依赖 boot**；`boot → cli`（既有）不变，无环。
- `sableos-boot` 侧：`SableOsApplication` **剥掉** `@EntityScan`/`@EnableJpaRepositories`（挪到 `SableOsRuntime`），降为最小 `@SpringBootApplication(scanBasePackages="com.sableos")`（vestigial，对齐 oryxos `OryxOsApplication`）；boot 既有 `@Configuration`（`ReactConfiguration`/`ProviderConfiguration`/`AgentConfiguration`/`ObservabilityConfig`）**原地不动**，由 `SableOsRuntime` 的 `scanBasePackages="com.sableos"` 在运行期/测试期扫到；boot 的 `@SpringBootTest` 改指 `classes=SableOsRuntime.class`（boot→cli 可引用）。

**Rationale**: 用户指定「根据 oryxos 推测」。oryxos 是同一架构的成熟 shipping 版，其入口结构已验证：cli 为聚合入口 + 独立 `Runtime` 应用类 + 重命令自启动 + 轻命令裸 JDBC。此法比"boot 侧惰性工厂"更简单——**无需 `SableOsLauncher`/`LazySpringFactory`/`SpringBackedCommand` 标记/自定义 `IFactory`**（原草案作废）；轻重分流天然由"命令 run() 内是否 `SpringApplicationBuilder.run`"表达。不新增第三方依赖（`SpringApplicationBuilder`/Picocli 均既有）。

**Alternatives considered**:
- boot 侧 `SableOsLauncher` + `LazySpringFactory`(IFactory) + `SpringBackedCommand` 标记（原 clarify 选项 A 的机制）：比 oryxos 多三个概念、且 IFactory 饿汉/懒汉边界易错；oryxos 证明"重命令自启动"更直白。**作废**。
- 用 `picocli-spring-boot-starter`：新增依赖 + 饿汉起上下文（轻命令变慢）。**否决**。
- 反转依赖让 boot 不产 fat JAR、cli 产：oryxos 仍由 boot 产 fat JAR（只改 mainClass），改动更小、AGENTS.md 口径不破。**否决**。

**核实点（写前 H3）**: 已核实 oryxos `OryxOsCli`/`OryxOsRuntime`/`ChatCommand`/`SessionListCommand`/`LightDbConfig`/`CliChannel` 的真实实现（`D:\idea\oryxos`，2026-10-15）；`SpringApplicationBuilder(...).web(...).bannerMode(...).run()` 为 Spring Boot 标准 API；Picocli API 见附录 A。

## D-07 命令轻/重分类与 12 个子命令的最小行为

**Decision**: 
- **重命令（在 `run()` 内自启动 `SableOsRuntime` 上下文）**：`chat`（全功能，`getBean(CliChannel).run(...)`）、`serve`（外壳：`web(SERVLET)` 起上下文后打印"Web Service 归 26 节"占位，不实现完整 REST）、`gateway`（外壳：占位，多通道归后续渠道节）。
- **轻命令（不起 Spring，纯文件/属性/直读 DB）**：`init`（建 `.sableos/` 骨架）、`status`（报工作区根、DB 路径、Agent 计数）、`profile list/create/show/delete`（读写 `.sableos/agents/` 或 profiles YAML）、`provider list`（读 providers 配置列出）、`tool list`（列已注册工具，当前 `ToolCatalog` 空 → 提示归 20 节）、`session list`（`DriverManager` 直读 SQLite `sessions` 表列会话 id/状态/时间）。

**Rationale**: 课件把重命令界定为 chat/serve/gateway（调模型/跑引擎），其余归"查一下/列个表"的轻命令；serve/gateway 的完整实现明确归后节，本节只交付外壳。`session list` 需读 DB 但"不调模型/跑引擎"，按课件轻/重判据归轻命令，用 `java.sql.DriverManager` 直读（驱动运行期由 fat JAR 经 storage 传递提供，cli 无新增编译依赖），避免为只读列表启动全上下文。**oryxos 实证**：`SessionListCommand` 正是裸 JDBC（`SELECT session_id, profile_name, status, last_active_at FROM sessions ORDER BY last_active_at DESC`），datasource 由 `LightDbConfig.load()` 解析（读同一份配置、`${ENV}` 占位、缺省落 SQLite 文件、库未建则提示"暂无会话"）——本节照此落一个 cli 侧 `LightDbConfig`（对齐 sableos 的 `workspace.root`/`.sableos/sableos.db`）。

**Alternatives considered**: 把 `session list` 做成重命令——违课件轻/重分类、为只读列表起全上下文，**否决**。serve/gateway 本节就实现完整 Web/多通道——越 26 节/后续渠道节范围（软门禁 #1），**否决**。

**边界**: 12 个子命令的 `--help` 与轻/重分流的进程级行为**不进 harness**（课件明确留人工清单）；harness 只钉会话层。

## D-08 `InMemorySessionStore` 的去留：删除

**Decision**: 删除 `sableos-storage/.../InMemorySessionStore.java`；同步改 boot 两处 L17 产物：`ReactConfiguration.sessionStore()` 返回 `JpaSessionManager`（或注入 `SessionManager` bean 后委托），`ReactAssemblyTest` 把 `assertThat(sessionStore).isInstanceOf(InMemorySessionStore.class)` 改为断言其为 `JpaSessionManager`（且是 `SessionManager`）。

**Rationale**: L17 的 `InMemorySessionStore` javadoc 与走读明载其为"内存前身/18 节换真身"；本节落地持久化后它即成死代码。保留死代码违 AGENTS.md"不留 backwards-compat 死代码"。`ReactAssemblyTest` 的断言更新是装配合法演进的反映（非删断言作弊）——保存路径与其余八个 bean 断言不变。**oryxos 实证**：全仓 `grep InMemorySessionStore` 零命中——成熟版从未保留内存实现，会话一律走 `JpaSessionManager`。

**Alternatives considered**: 保留 `InMemorySessionStore` 供无库降级/测试——本节 harness 用真 SQLite（@SpringBootTest），无需内存实现；保留即死代码。**否决**（倾向删除）。**注**：删除 L17 类 + 改 L17 测试属软门禁 #4，**停点确认**。

## D-09 `CliChannel` 落位与 `ChatCommand` 的委托关系

**Decision**: `CliChannel`（`com.sableos.channel.cli`）承载 chat 交互循环，框架无关（构造入参 `AgentService` + `SessionManager`，方法 `run(String profileName, String userId)` 内部 `getOrCreate("cli",userId,profileName)`→`while` 读 stdin→`/quit`/EOF 退出→`agentService.process`→打印）。`ChatCommand`（cli，`@Command(name="chat")`，重命令）在 `run()` 内自启动 `SableOsRuntime` 上下文后 `getBean(CliChannel.class).run(profileName, currentUser())`；`CliChannel` @Bean 由 cli 侧 `SableOsRuntime` 声明。故 `sableos-cli → sableos-channel-cli` 新依赖。

**Rationale**: §10 把"CliChannel、sableos chat 命令实现"归 `sableos-channel-cli`，"Picocli 主入口、子命令"归 `sableos-cli`；`CliChannel` 保持框架无关可写纯单测（喂脚本化 stdin）。oryxos 实证（`io.oryxos.channel.cli.CliChannel`）：构造 `(AgentService, SessionManager)`、`run(String profileName, String userId)` 内 `getOrCreate("cli",userId,profileName)`→`while` 读 stdin→`/quit`（及 EOF）退出→`agentService.process`→打印；`ChatCommand.run()` 自启动上下文后 `context.getBean(CliChannel.class).run(profileName, currentUser())`，`currentUser()=System.getProperty("user.name","unknown")`（**无 `--user` 选项**，核心阶段无认证体系）。本节据此收敛：`CliChannel.run(profileName,userId)`，`ChatCommand` 只留 `--profile`（默认 default），user 取系统用户名。

**Alternatives considered**: 把交互循环直接写进 `ChatCommand`（cli）——违 §10 落位（chat 实现归 channel-cli），且 cli 会承载本应框架无关的循环逻辑。**否决**。（oryxos 成熟版另有 `/new` 清空、打字机流式、RBAC PrincipalContext——分属 26/19/后续节，本节不做。）

## D-10 `SessionManager extends SessionStore`（对 oryxos 的有意微 divergence）

**Decision**: sableos 的 core `SessionManager` **`extends SessionStore`**（追加 `getOrCreate`/`get` + 静态 `sessionId` 公式），`JpaSessionManager` 实现之；`AgentService`（L17，构造收 `SessionStore`）**签名不变**，运行期注入的 `SessionStore` bean 即 `SessionManager`（is-a）。

**Rationale**: oryxos 成熟版把 `AgentService` 改成直接依赖 `SessionManager`（且 `SessionManager` 不 extends 任何接口）——但那是 oryxos 后续节（turnCoordinator 等）演进的结果。sableos 停在 L17 的 `AgentService(SessionStore)`；让 `SessionManager extends SessionStore` 是**追加式**、零破坏 L17 公共构造与 `AgentServiceTest`（`@Mock SessionStore` 照用），比"改 AgentService 依赖类型"（SG#4 更重）更省。session_id 公式放 core 接口静态方法（而非 oryxos 的 storage 私有方法），以严丝合缝满足 sableos AGENTS.md H4④「**core 侧**单点拼接」。

**Alternatives considered**: 照搬 oryxos 改 `AgentService` 依赖 `SessionManager` → 改 L17 公共构造 + `AgentServiceTest`，SG#4 面更大且本节无收益。**否决**。公式放 storage 私有方法（oryxos 形态）→ 与 sableos H4④「core 侧」字面冲突。**否决**。

**连带 divergence（save 语义）**：oryxos 的 `JpaSessionManager.save` 在会话行不存在时**抛异常**（"save 前必须先 getOrCreate"）——因其瘦 `Session` 只有 (id, profileName, messages)，无法凭 Session 补全 channel/userId/createdAt 整行。sableos 的富 `Session` 自带这些列，故 `save` 做成 **upsert**（命中更新易变列、未命中从 Session 补全整行插入）。收益：`AgentService.process` 的保存路径无需强制先 `getOrCreate`，且 L17 里直接 `new Session(id,...)` 的调用方（如 `ReactAssemblyTest`）不破。channel/userId 为 null 时（core Session 允许）落库前 coalesce 成空串以满足 NOT NULL。

## 附录 A：第三方 API 本地核实（软门禁 #5，2026-10-14）

核实对象：`/d/maven/Repository/info/picocli/picocli/4.7.7/picocli-4.7.7.jar`（父 POM `picocli.version=4.7.7` 锁定）。

| API | 核实结果 |
| --- | --- |
| `picocli.CommandLine`（含 `execute(String...)`、`defaultFactory()`） | ✅ `picocli/CommandLine.class` 存在 |
| `picocli.CommandLine.IFactory` | ✅ `public interface`，唯一方法 `<K> K create(Class<K>) throws Exception` |
| `@picocli.CommandLine.Command`（`name`/`description`/`subcommands`/`mixinStandardHelpOptions`） | ✅ `CommandLine$Command.class` 存在 |
| `@picocli.CommandLine.Option`（`names`/`defaultValue`） | ✅ `CommandLine$Option.class` 存在 |
| `@picocli.CommandLine.Spec` + `Model.CommandSpec` | ✅ `CommandLine$Spec.class` 存在（既有 `SableOsCli` 已用） |
| `RunAll`/`RunFirst`/`IExecutionStrategy` | ✅ 存在（默认执行策略 `RunFirst`，本节沿用默认） |

Jackson：`jackson-databind` 在 `sableos-core/pom.xml`（`com.fasterxml.jackson.core`），storage 经 core 传递可见；record 序列化自 Jackson 2.12 支持（BOM 版本远高于此）。Spring Boot `SpringApplicationBuilder`/`SpringApplication.run`/`@EntityScan`/`@EnableJpaRepositories` 均为既有依赖内标准 API（`SableOsApplication` 已在用后两者）。

**结论**：本节所需第三方 API 全部在本地锁定依赖中核实存在，无需新增依赖，无软门禁 #5/#6 触发。
