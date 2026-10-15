# Feature Specification: CLI——SableOS 的命令行入口与会话持久化

**Feature Branch**: `003-lesson18-cli`

**Created**: 2026-10-14

**Status**: Draft

**Input**: User description: "第18节需求：CLI（命令行入口）——SableOS 打包成可执行 JAR 后，人在终端里操作底座的那道门。背景与价值：前两节让底座『会调模型』（Provider）和『会思考』（ReAct 循环），大脑已能转，但缺一个能亲手操作它的入口。CLI 是整个程序的 main 入口，让人在终端里跟 Agent 对话、把服务跑起来、查配置和状态；它是『消息进出的门，不是干活的人』——所有入口的消息最后都汇进同一个 ReAct 引擎，CLI 自己不想、不调模型、不执行工具。用户场景：①开发者在终端与 Agent 交互式多轮对话、主动退出；②运维把底座作为常驻服务/守护进程跑起来；③用户用『看一眼就退』的命令秒查工程状态与有哪些 Agent/Provider/工具/会话。功能需求：FR1 CLI 是薄入口层，只做『读输入→交给引擎→打印结果』，不含 Agent 智能。FR2 命令按轻/重分流：轻命令不启动应用上下文、直接文件操作、秒回；重命令才启动上下文。FR3 参数解析/子命令/帮助/报错交给成熟命令行框架。FR4 交互对话命令维护一个当前会话，逐行读标准输入、连同会话交给引擎、打印回复，直到退出指令。FR5 会话持久化随本节交付：同一（渠道,用户,Agent配置名）三元组多次获取返回同一会话（幂等）；三元组任一不同即不同会话；会话标识拼接只此一处，所有入口只提供三元组、绝不各自拼串。FR6 对话历史整体序列化成 JSON 存单列，核心阶段不按条拆表；模拟重启（新建上下文重查）历史仍在。FR7 重命令启动上下文时跨模块的仓储/实体扫描范围必须显式声明，否则『发现 0 个仓储』、审计写不进、启动即退出。FR8 核心阶段提供 12 个子命令。明确不做：serve 完整 Web Service（26 节）、gateway 挂多个 IM 通道（后续渠道节）、命令分流与 --help 的自动化测试（进程级行为，留人工）、会话按条拆表与归档状态机完整流转、第 13 个子命令 agent import（025 节）。验收标准：可自动化部分由课件『验收 harness』两个测试类承载（SessionManagerTest 幂等/隔离/单点 id、SessionRepositoryTest 建表可存取+JSON 回读完整+重启历史仍在），mvn test 全绿即通过；人工项见课件『五、做完怎么验』。依赖与假设：依赖第16节 Provider/Profile、第17节 ReAct/AgentService 与会话契约、审计落库；复用第17节为 sessions 表预留的字段口径；外部依赖命令行框架与已在持久化模块就位的 SQLite+JPA+Flyway。交付物（拆解锚点，类名/落位归 plan）：代码 CLI 主入口、12 个子命令类、CLI 交互对话通道、会话 JPA 实体 + 会话仓储、会话管理器；测试 SessionManagerTest、SessionRepositoryTest；表 sessions（会话标识由会话管理器按渠道+用户+Agent配置名唯一拼接，手工建表脚本）；约定 轻命令不启动上下文、重命令启动类显式声明仓储/实体扫描范围。"

## Clarifications

### Session 2026-10-14

- Q: 重命令要启动 Spring，但 `SableOsCli` 在 sableos-cli、而完整装配与可执行 fat JAR 现在在 sableos-boot（boot→cli）——程序入口与 Spring 启动怎么落？ → A: **boot 主类委托 Picocli，重命令懒启动 Spring**。sableos-boot 仍是可执行 fat JAR 的产出方与 JVM 主类，但主类先跑 `SableOsCli` 命令树、用一个惰性 Spring 工厂：轻命令永不启动应用上下文，重命令按需拉起 boot 的上下文取 bean；`@EntityScan`/`@EnableJpaRepositories`（basePackages=`com.sableos.storage`）仍声明在 boot 的应用类上。模块依赖方向不变（boot→cli），cli 不反向依赖 boot。
- Q: `SessionManager`（getOrCreate/get/save）落在哪、与第17节既有 `SessionStore`/`InMemorySessionStore` 什么关系？ → A: **core 契约 + storage JPA 实现**（沿用 §28 `ScheduledTaskStore` 的依赖倒置先例）。`SessionManager` 契约（含 session_id 单点拼接公式）放 sableos-core，JPA 实现 `JpaSessionManager` 放 sableos-storage；它取代 `InMemorySessionStore` 成为 `AgentService` 的 `SessionStore` bean（process 处理完成后保存会话的路径不变）。session_id 单点落在 core，满足不变量 H4④。
- Q: JPA 持久化实体如何命名（core 已有领域类 `com.sableos.core.session.Session`）？ → A: **`SessionEntity`**（映射 `sessions` 表），`SessionRepository` 管理 `SessionEntity`；`SessionManager` 负责 core 领域 `Session` ↔ `SessionEntity` 的映射，避免同仓两个 `Session` 混淆。
- Q: session_id 的唯一拼接公式定成什么（只在 SessionManager 一处生成，web/scheduler 复用）？ → A: **`channel:user:profile`**（冒号分隔，按 渠道:用户:Agent配置名 顺序，如 `cli:wang:default`）；与 `TraceContext` 的 channel 取值口径（cli/web/scheduler）对齐。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 在终端里跟 Agent 说上话：交互式多轮对话 (Priority: P1)

开发者在终端敲下交互对话命令，进入"一问一答"：系统读一行标准输入，把这行连同"当前这次对话的会话"交给引擎处理，把引擎返回的最终回复打到标准输出，然后回到提示符读下一行；直到开发者输入退出指令，命令结束。整个交互对话命令通篇没有任何"Agent 智能"——它就是个"读—转交—打印"的薄壳，想、调模型、执行工具全在引擎里。

**Why this priority**: 这是本节"看得见摸得着"的头部交付——到这一步，用户第一次能在终端里真正跟自己搭的 Agent 说上话。它和 Provider、ReAct 一起撑起第一个端到端 Demo 的对话体验，是 CLI 存在的理由本身。

**Independent Test**: 以脚本化的标准输入喂若干行（含最后的退出指令），断言每行都被交给引擎一次、每条回复被打印、读到退出指令即结束循环不再继续读；断言交互对话命令自身不触发任何模型调用或工具执行（薄壳）。

**Acceptance Scenarios**:

1. **Given** 进入交互对话命令，**When** 用户输入一行普通文本，**Then** 该行连同当前会话被交给引擎，引擎返回的回复被打印到标准输出，随后回到提示符等待下一行。
2. **Given** 处于交互对话中，**When** 用户输入退出指令，**Then** 命令立即结束循环并退出，不再读后续输入。
3. **Given** 同一次交互对话里的连续多行输入，**When** 逐行处理，**Then** 它们复用同一个当前会话（多轮上下文得以串接），而非每行新建会话。

---

### User Story 2 - 会话有身份、跨重启连续：所有入口共用的地基 (Priority: P2)

CLI 是第一个真正"用起来"会话的入口，所以会话的持久化归本节负责。系统对外提供一个会话管理器：给定（渠道, 用户, Agent 配置名）三元组就能拿到（或新建）这次对话的会话，也能按会话标识取回、把会话存下。同一三元组历次获取返回的都是**同一个**会话（幂等）；三元组里任一维度不同就是不同会话（渠道不同就是不同会话）。会话标识的拼接**只发生在会话管理器内部这一处**——所有入口（本地交互传 cli、Web 传 web、定时传 scheduler）只提供三元组、绝不各自拼串，因为两处各拼一遍、格式差一个分隔符，同一个人就会出现两条互不相认的历史。对话历史整体序列化成 JSON 存单列；进程重启（新建上下文重新查询）后历史仍在。

**Why this priority**: 这是后面所有入口共用的地基，且"出口径"问题最难查（同一个人裂成两条历史、重启丢历史）——一旦漂移，排查成本极高，所以在本节就钉死。它是本节唯一被自动化 harness 覆盖的部分，是会话层可独立验证的 MVP。

**Independent Test**: 对同一三元组两次获取，断言返回同一会话标识；对渠道/用户/Agent 配置名任一不同的三元组，断言得到不同会话标识；把带历史的会话存下、以新建上下文重新查询，断言历史完整回读；断言会话标识生成逻辑在全代码库只有一处实现。

**Acceptance Scenarios**:

1. **Given** 同一（渠道, 用户, Agent 配置名）三元组，**When** 连续两次获取会话，**Then** 两次返回同一个会话（标识相同），多轮对话靠它串起来。
2. **Given** 用户与 Agent 配置名相同、仅渠道不同（如 cli 与 web），**When** 分别获取会话，**Then** 得到两个不同的会话（标识不同）。
3. **Given** 一个已存有对话历史的会话，**When** 模拟重启（新建上下文按会话标识重新查询），**Then** 历史被完整回读、消息不丢。
4. **Given** 全代码库，**When** 检索会话标识的拼接实现，**Then** 只有会话管理器内部这一处，各入口不各自拼串。

---

### User Story 3 - 命令分轻重：看一眼就退的秒回，要跑引擎的才起上下文 (Priority: P3)

底座在 JDK 21 下启动应用上下文要 2~4 秒。对常驻服务无所谓，但对"看一眼就退"的命令（如初始化工程、列出有哪些 Agent）等 4 秒太难受。所以命令分两类：轻命令（不需要调模型/不跑引擎）不启动应用上下文、直接用标准 API 读写文件、秒回；重命令（交互对话、启动服务、守护进程）才启动应用上下文。判断标准就一条——这个命令要不要调模型/跑引擎。重命令启动上下文时，跨模块的仓储与实体扫描范围必须显式声明：组件扫描范围与"仓储/实体扫描"是两套独立逻辑，后者默认只按主类所在包扫描；不显式声明会得到"发现 0 个仓储接口"、审计写不进去、启动即报错退出。

**Why this priority**: 分流是一开始就要定的边界，不然要么全都慢、要么后面改起来伤筋动骨；扫描范围则是照着"重命令才起上下文"走几乎绕不开的真实坑。它决定 CLI 的"手感"与重命令能否真正跑起来，但不改变会话与引擎的核心正确性，故排在地基之后。

**Independent Test**: 断言轻命令的执行路径不触发应用上下文启动（不加载引擎/模型 bean）；断言重命令启动的上下文中"发现的仓储接口数量 > 0"、审计与会话可写入（该行为属进程级，主要留人工清单，自动化仅覆盖会话/仓储可写这条与地基共用）。

**Acceptance Scenarios**:

1. **Given** 一个轻命令（如列出 Agent），**When** 执行，**Then** 不启动应用上下文、直接完成文件读取并秒回结果。
2. **Given** 一个重命令（如交互对话），**When** 执行，**Then** 启动应用上下文，且启动日志中"发现的仓储接口数量 > 0"、会话与审计能正常写入。
3. **Given** 重命令的启动类，**When** 检查其扫描声明，**Then** 跨模块的仓储与实体扫描范围被显式声明（不依赖默认只扫主类所在包）。

---

### User Story 4 - 一整套操作面：12 个子命令各司其职 (Priority: P4)

底座打包成一个可执行 JAR，CLI 主入口就是整个程序的 main，底下挂 12 个子命令：三种运行模式（交互对话 chat、启动服务 serve、守护进程 gateway，区别只在"消息从哪进来"，共享同一份 Agent 配置和同一套会话存储）、查看类（status、profile list/create/show/delete、provider list、tool list、session list）、起项目类（init）。子命令、参数、帮助、报错统一交给成熟命令行框架处理，不自己撸参数解析。其中 serve 的完整 Web Service 实现归后续节、gateway 挂多个企业 IM 通道归后续渠道节，本节只交付这些命令的外壳与其当前能跑通的部分。

**Why this priority**: 这是把前三块能力"包"成一个可操作产品的最外层操作面；单个命令都很浅，但齐备的命令面 + 统一帮助是"可用 CLI"的观感底座。它建立在会话地基与命令分流之上，故排最后。

**Independent Test**: 断言主入口注册了 12 个子命令且各有其名；断言每个子命令都能响应框架自带的帮助（--help 属进程级行为，留人工清单）；断言三种运行模式共享同一份 Agent 配置与会话存储。

**Acceptance Scenarios**:

1. **Given** CLI 主入口，**When** 查看其注册的子命令，**Then** 恰好包含核心阶段约定的 12 个（chat/serve/gateway、status、profile list/create/show/delete、provider list、tool list、session list、init）。
2. **Given** 任意一个子命令，**When** 请求其帮助，**Then** 由命令行框架给出该命令的用法与参数说明。
3. **Given** 三种运行模式（chat/serve/gateway），**When** 分别运行，**Then** 它们复用同一份 Agent 配置和同一套会话存储，切换模式数据不丢。

---

### Edge Cases

- 用户在交互对话中输入空行：按引擎可处理的一条输入交下去（是否忽略空行属实现细节，核心是不崩溃、不越权自行判断语义）。
- 用户输入的退出指令带前后空白：以去除首尾空白后的内容判定退出。
- 同一三元组在并发下同时首次获取：会话标识拼接的唯一实现须保证同三元组收敛到同一会话，不产生两条并行新建的历史。
- 会话历史为空（刚新建、还没对话）：序列化/回读须能处理空历史，不报错。
- 重启后会话表已有历史行：重新查询按会话标识命中既有行、在其上继续累积，而非新建覆盖。
- 轻命令目标目录不存在（如还没 init 就 list）：给出清晰提示，不静默返回空、不崩溃。
- 重命令启动上下文时若漏声明跨模块扫描范围：启动即因"发现 0 个仓储接口"报错退出（这是必须靠显式声明规避的坑，而非容忍的降级）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: CLI 必须是整个程序的 main 入口；每个子命令只做"读输入 → 交给引擎处理 → 打印结果"，不得自行调用模型、不得自行执行工具、不得内嵌任何 Agent 智能。
- **FR-002**: 命令必须按"轻/重"分流——轻命令（不需要调模型/跑引擎，如初始化工程、列出 Agent）不启动应用上下文、直接用标准 API 读写文件、秒回；重命令（交互对话、启动服务、守护进程）才启动应用上下文。判据唯一：该命令要不要调模型/跑引擎。
- **FR-003**: 子命令、参数、帮助信息与报错提示必须统一交给成熟的命令行框架处理，不得自行解析原始参数数组。
- **FR-004**: 交互式对话命令必须维护"一个当前会话"，逐行读标准输入、把每行连同该会话交给引擎、把引擎返回的最终回复打到标准输出，直到用户输入退出指令为止；退出判定是 CLI 唯一"自己判断"的逻辑。
- **FR-005**: 同一次交互对话内的连续多行输入必须复用同一个当前会话（多轮上下文得以串接），不得每行新建会话。
- **FR-006**: 系统必须提供会话管理器，对外至少支持三种操作：按（渠道, 用户, Agent 配置名）三元组获取或新建会话、按会话标识取回会话、保存会话。
- **FR-007**: 同一（渠道, 用户, Agent 配置名）三元组历次获取必须返回同一个会话（幂等）；三元组任一维度不同必须得到不同会话（渠道不同即不同会话）。
- **FR-008**: 会话标识的拼接必须只发生在会话管理器内部唯一一处，公式为 `channel:user:profile`（冒号分隔，按 渠道:用户:Agent配置名 顺序，如 `cli:wang:default`；channel 取值与 `TraceContext` 的 cli/web/scheduler 口径对齐）；所有入口（本地交互传 cli、Web 传 web、定时传 scheduler）只提供三元组，绝不各自拼接标识字符串。
- **FR-009**: 对话历史必须整体序列化成 JSON 存入单列（`messages_json`），核心阶段不做按条拆表；序列化回读后消息必须完整（角色、内容、工具调用请求、工具名与成败等要素不丢）。
- **FR-010**: 会话必须跨重启持久化——进程重启（新建上下文按会话标识重新查询）后，既有会话的对话历史必须完整可回读，且在其上继续累积而非新建覆盖。
- **FR-011**: 会话表结构必须由手工维护的建表脚本落地（表结构唯一权威是迁移脚本，禁用自动改表结构）；会话实体字段口径必须复用第17节为 `sessions` 表预留的定义（会话标识主键、Agent 配置名、渠道、用户标识、对话历史 JSON、状态、创建/最后活跃/归档三个时间戳）。
- **FR-012**: 重命令启动应用上下文时，跨模块的仓储与实体扫描范围必须显式声明（`@EntityScan`/`@EnableJpaRepositories` 的 basePackages 指向 `com.sableos.storage`），声明落在 sableos-boot 的应用类上；不得依赖"组件扫描范围"顺带扫到——二者是独立逻辑，后者默认只按主类所在包扫描，漏声明会导致"发现 0 个仓储接口"、审计与会话写不进、启动即报错退出。程序入口与 Spring 启动方式：sableos-boot 仍是可执行 fat JAR 的产出方与 JVM 主类，主类委托 Picocli 的 `SableOsCli` 命令树，用惰性 Spring 工厂——轻命令永不启动上下文，重命令按需拉起 boot 的上下文取 bean；模块依赖方向保持 boot→cli，cli 不反向依赖 boot。
- **FR-013**: 核心阶段必须提供 12 个子命令：三种运行模式（交互对话 chat、启动服务 serve、守护进程 gateway）、查看类（status、profile list/create/show/delete、provider list、tool list、session list）、起项目类（init）。
- **FR-014**: 三种运行模式必须共享同一份 Agent 配置（Profile）和同一套会话存储；切换模式数据不丢。
- **FR-015**: 会话状态核心阶段固定为 `active`（字段口径按前序节预留，归档状态机的完整流转不在本节）；会话持久化以 `JpaSessionManager`（sableos-storage）取代第17节的进程内 `InMemorySessionStore`，成为 `AgentService` 处理完成后保存会话的落地实现——`SessionManager` 契约在 sableos-core、JPA 实现在 sableos-storage（依赖倒置，沿用 §28 `ScheduledTaskStore` 先例），`AgentService.process` 的保存路径不变。

### Key Entities *(include if feature involves data)*

- **会话（Session）**：一次对话的状态载体。关键属性：会话标识（主键，由 `channel:user:profile` 唯一拼接）、所属 Agent 配置名、渠道、用户标识、对话历史（整体 JSON）、状态（核心阶段固定 active）、创建时间、最后活跃时间、归档时间。领域载体复用第17节 `com.sableos.core.session.Session`；本节新增可跨重启持久化的 JPA 实体 `SessionEntity`（sableos-storage，映射 `sessions` 表）与之对应。
- **对话历史条目**：会话里按序的一条往来记录，区分角色（用户消息 / 模型输出 / 工具结果）；随会话整体序列化成 JSON 存单列（`messages_json`）。
- **会话管理器（SessionManager）**：会话身份与持久化的唯一收口。契约在 sableos-core、JPA 实现 `JpaSessionManager` 在 sableos-storage（依赖倒置）；对外提供"按三元组获取或新建、按标识取回、保存"三种操作；会话标识（`channel:user:profile`）拼接的唯一实现处；负责领域 `Session` ↔ `SessionEntity` 的映射。
- **命令（子命令）**：CLI 主入口下挂的 12 个操作单元，分轻/重两类；轻命令不启动应用上下文，重命令启动并显式声明跨模块扫描范围。
- **Agent 运行配置（Profile）**：复用第16节交付的载体；三种运行模式共享同一份。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 同一（渠道, 用户, Agent 配置名）三元组历次获取 100% 返回同一会话标识；三元组任一维度不同 100% 得到不同会话标识。
- **SC-002**: 会话标识的拼接实现在全代码库中恰好 1 处（各入口零处自行拼串）。
- **SC-003**: 一个已存历史的会话经"新建上下文重新查询"后，对话历史 100% 完整回读（消息条数与各条要素一致，零丢失）。
- **SC-004**: 交互对话命令能把每行输入交给引擎并打印回复，读到退出指令即结束；命令自身触发的模型调用数与工具执行数均为 0（薄壳）。
- **SC-005**: 重命令启动的上下文中"发现的仓储接口数量 > 0"，且会话与审计能成功写入；轻命令的执行路径不启动应用上下文。
- **SC-006**: CLI 主入口注册的子命令恰好 12 个，且每个都能给出框架自带的帮助。
- **SC-007**: 三种运行模式（chat/serve/gateway）复用同一份 Agent 配置与会话存储，切换模式后既有会话数据不丢。

## Assumptions

- 依赖第16节已交付的 Agent 运行配置载体与索引（Profile / ProfileRegistry）、模型调用统一入口；依赖第17节已交付的统一处理入口（AgentService.process）、会话契约（Session / SessionStore / SessionMessage）、审计落库（llm_calls / tool_invocations）。
- 会话持久化复用第17节为 `sessions` 表预留的字段口径（第17节进程内会话存储的 javadoc 已声明"跨重启持久化归 18 节 SessionManager"）；本节把进程内存储升级为持久化实现，AgentService 的"处理完成后保存会话"契约不变。
- 命令行框架用 Picocli（AGENTS.md §5.1 / 宪章技术栈表已锁定）；持久化用已就位的 SQLite + Spring Data JPA + Flyway 双轨迁移目录（SQLite 默认档，禁自动改表结构）。
- 三种运行模式的"启动应用上下文"复用同一套装配（同一份 Profile 与会话存储）；serve 的完整 Web Service 归第26节、gateway 挂多个企业 IM 通道归后续渠道节，本节只交付命令外壳与其当前能跑通的部分。
- 命令分流（轻命令不起上下文）与 12 个命令的 --help 属进程级行为，写自动化测试成本大于收益，留人工清单，不进 harness；harness 只钉会话层（SessionManagerTest / SessionRepositoryTest）。
- 会话状态核心阶段固定 active；归档状态机完整流转、会话按条拆表、第 13 个子命令 agent import（025 节）均不在本节范围。
- 凭证走环境变量占位，不落明文（AGENTS.md §5.1）；CLI 不在代码或配置里硬编码任何 API key。
