# Feature Specification: Tool 体系——Agent 真正能动手干事的那双手

**Feature Branch**: `005-lesson20-tool`

**Created**: 2026-10-16

**Status**: Draft

## Clarifications

### Session 2026-10-16

- Q: 20 节兑现"NotifyTools 在此完成注册"，但渠道来源没有着落（19 节只交付通知抽象三件；注册表本体 SQLite+CRUD 各节课程都没排上）。渠道来源用什么形态？ → A: **Map 最小组合**——`NotifyTools` 构造接收有序 `Map<渠道名, 通知目标>`（全用现有类型，零新对外概念、不触软门禁 #1）；本节装配为空表（报错点名"无已注册渠道"，零请求）；注册表本体（SQLite + CRUD）留后续节落地、届时不改 `NotifyTools`。测试与 19 节登记契约逐条对齐；`NotifyToolsTest` 仍按 19 节口径 24 节后补跑。
- Q: `sableos tool list` 怎么升级（课件 §五 要求"能看到当前注册了哪些工具"）？ → A: **保持轻命令、输出静态内置清单**（用户裁定按参照仓库 oryxos 判断；oryxos 终态同为轻命令占位，其 20 节验收报告明载"轻命令不起 Spring 拿不到 registry"、为可接受占位，运行期实时清单归后续节）。本仓库口径：`tool list` 保持零 Spring 秒回、刷新静态内置清单并如实标注"运行期实时清单归 26 节 Web 管理台"；课件人工项"@Tool 示例工具在 tool list 里可见"随此口径调整（方式三真跑经 chat 真实调用验证——内置工具走同一条注解管道）。

**Input**: User description: "第20节需求：Tool 体系——Agent 真正能动手干事的那双手。背景与价值：大模型只会生成文字，读不了磁盘文件也发不出 HTTP 请求；要让 Agent 真的干活，必须给它一批能操作外部世界的工具——LLM 靠 Function Calling 决定『调哪个工具、传什么参数』，底座负责真正执行并把结果递回，这正是 ReAct 循环里 Act 那一步。工具来源五花八门（内置、业务方 Java 写的、外部 MCP 服务），若 ReAct 循环分来源区别对待代码会立刻乱掉，因此先立统一工具抽象、再用一个注册表汇总；Plugin Tool 给三档接入方式（零代码 SKILL.md+MCP / 轻代码自写 MCP server / 重代码 @Tool Java Bean），选择标准一句话：能用方式一就不用方式二、能用方式二就不用方式三；所有工具执行前一律过安全校验（应用层白名单：文件查路径、Shell 查命令、HTTP 查域名，校验不过直接拦下），这是核心阶段唯一的 Tool 治理手段、设计时就串进执行链路。用户场景：①业务方零代码：写一份 SKILL.md 并复用现成 MCP server，Agent 理解任务并调用外部工具完成『每天早上把 PR 进度推到群里』这类事；②业务方重代码：一个带 @Tool 注解的 Spring Bean 放进工程，启动自动注册、参数 schema 自动生成，Agent 可调用；③运行时容错：某个 MCP server 失联只 WARN 跳过它的工具，绝不能拖垮 SableOS 启动、其余工具照常可用；④安全拦截：任一涉外工具执行第一步过白名单校验，不过即抛异常拦下，动作根本不发生；⑤日常工具箱：改一处配置（edit_file 唯一匹配替换）、搜代码（grep/glob）、跑命令、抓网页、上网搜索（web_search）、中途问人（ask_user）都由内置工具覆盖。功能需求：FR1 统一抽象（名称/描述/输入 schema/执行）承载所有来源、唯一注册表、ReAct 循环与执行器只跟抽象打交道、重名注册拒绝；FR2 九个内置工具面：六个本节点名交付（read_file/write_file/list_dir/shell/http_get/http_post）+ notify 完成注册（19 节）+ Memory 两个 22 节注册；FR3 安全校验前向接口（本节交付接口五件、白名单实现 23/24 节；工具执行第一行过校验、不过零动作）；FR4 注解式接入管道（@Tool → schema 自动生成 → 统一抽象，内置与方式三共用）；FR5 MCP 接入（读 .sableos/mcp_servers.yaml：name/transport/command/env；启动连接、tools/list 包装注册、调用 JSON-RPC 转发、结果包统一结果（失败可重试）；单 server 失联只 WARN 不拖垮启动）；FR6 按 Profile tools 字段过滤，子集精确匹配不多不少；FR7 运行时装配用注册表替换既有空工具表（接通 17 节执行器与 Prompt 组装、18 节 chat 链路），sableos tool list 可见当前注册工具；FR8 扩展交付（对齐业界主流，复用既有抽象与管道）：edit_file（唯一匹配，找不到/多处报错且文件不动）、grep/glob（200 条上限截断注明、grep 跳过二进制/非 UTF-8）、ask_user（UserInteraction 接口先行，拿不到回答抛异常；无人值守渠道明确报不支持）、web_search（SearchProvider 接口先行，第一件事过 HTTP_REQUEST 白名单；核心挂免 key 的 DuckDuckGo 实现）；FR9 NotifyTools 完成注册（渠道解析契约见 19 节登记：缺省/空白/default 取注册表第一个、未注册不回退、上下文缺失报错、enforce 先于 send）。明确不做（边界）：Tool Policy（哪个 Agent 能用哪些工具的 allow/deny 规则）；工具太多时的按需加载；把 SableOS 自己变成 MCP server 对外暴露；完整的容器级沙箱；一次响应里多个工具并行调——核心阶段先用『白名单 + Profile 的 tools 字段限定』顶住。验收标准：可自动化部分由课件『验收 harness』测试套件承载（mvn test 全绿即通过），关键回归点：契约三件套参数化遍历（name/description/inputSchema 全非空，新工具自动纳入）；三种来源统一注册 + 按 Profile 过滤不多不少；FileTools/ShellTools/HttpTools 各自『正常能跑通 + 越界会被拦』；MCP mock（listTools 包装注册、execute 转发参数原样、结果包装、失联只 WARN 其余照常注册启动不炸）；扩展工具（edit_file 唯一匹配语义、grep/glob 上限与二进制跳过、ask_user 无人值守报错、web_search 白名单先行）。人工项见课件『五、做完怎么验』：方式一真跑（SKILL.md + 真 MCP server，依赖真模型）、方式三真跑（@Tool 示例工具在 tool list 可见、Agent 调通）。依赖与假设：前序节已交付 SableTool/ToolResult/ToolCatalog/ToolExecutor/ProfileContext（17 节）、CLI 轻/重命令骨架（18 节）、Notify 抽象三件（19 节）、Provider 配置（16 节）；MCP 配置由使用者提供、MCP server 为外部进程；Sandbox 实现本体归 23/24 节。"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 统一抽象与注册表：循环对来源无感知 (Priority: P1)

内置的、业务方 Java 写的、外部 MCP 接进来的工具，全部以统一抽象（名称 / 描述 / 输入 schema / 执行）注册进唯一注册表；ReAct 循环与执行器只跟抽象打交道，完全不感知工具来源。每个 Agent 启动时按自己配置的 tools 字段拿到精确的可用子集，运行时装配用注册表替换既有的空工具表，chat 链路立即可用。

**Why this priority**: 这是整个 Tool 体系的地基（课件"第一先定统一抽象"）；没有它，后面每种来源都要在循环里开特例。

**Independent Test**: 分别向注册表注册三种来源的工具，断言都以统一抽象可查、契约三件套齐全；按 tools 字段过滤断言子集恰好等于声明列表（不多不少）；空工具表替换后 chat 的 Prompt 组装与执行器取到的就是注册表内容。

**Acceptance Scenarios**:

1. **Given** 三种来源各一个工具注册进注册表，**When** 以统一抽象遍历，**Then** 三个都在且名称/描述/输入 schema 可用。
2. **Given** 一个注册表，**When** 参数化遍历其中每个工具，**Then** 契约三件套全部非空——任何一个工具漏实现输入 schema 立刻红（"动手前先检查"那条的自动化版，新工具自动纳入）。
3. **Given** 注册表含多个工具、某 Agent 声明 tools=[read_file, http_get]，**When** 过滤，**Then** 结果恰好这两个——多一个是没过滤干净、少一个是过滤过头。
4. **Given** 重名工具二次注册，**When** 注册，**Then** 明确报错拒绝注册（静默覆盖会让两个来源打架且难查）。
5. **Given** 运行时装配完成，**When** 走 chat 链路，**Then** Prompt 里声明的工具清单与执行器查得到的工具都来自注册表（不再有"空工具表"）。
6. **Given** 运行时已装配，**When** 查看工具清单命令，**Then** 输出静态内置工具清单（轻命令零 Spring 秒回；如实标注"运行期实时清单归 26 节 Web 管理台"——clarify 拍板对齐 oryxos 口径，chat 启动可目检 MCP 连接与注册）。

---

### User Story 2 - 六个内置工具：能干活且越不了界 (Priority: P1)

read_file / write_file / list_dir / shell / http_get / http_post 六个内置工具交付：给合法输入能跑通拿到结果；每个工具执行的第一步过安全校验，校验不过直接拦下、动作根本不发生。

**Why this priority**: "真的去查天气"靠 http_get；工具能读文件跑命令发请求，一旦被乱调就是事故——功能对、且踩不出边界是工具最基本的两条。

**Independent Test**: 每个工具两条用例——正常输入 execute 成功且内容非空；注入拒绝一切的安全校验替身后 execute 被拦、外部动作零发生（真实白名单行为归 24 节测试）。

**Acceptance Scenarios**:

1. **Given** 临时目录里有文件，**When** read_file / list_dir / write_file 以合法路径执行，**Then** 内容读到 / 列到 / 写入成功；路径不存在时失败结果点名路径、不抛裸异常。
2. **Given** shell 执行 echo 类无害命令，**When** execute，**Then** 拿到标准输出；命令长时间不结束按超时终止并报失败；非零退出码失败结果携带标准错误。
3. **Given** 本地假服务，**When** http_get / http_post 执行，**Then** 取回响应体 / 完成提交；非 2xx 返回失败结果带状态码（查询类工具失败模型可自救，包结果不抛——区别于 notify 的失败上抛）。
4. **Given** 安全校验器拒绝该动作，**When** 任一涉外工具 execute，**Then** 违规异常抛出、文件未动 / 命令未跑 / 请求未发（动作零发生）。

---

### User Story 3 - MCP 接入：外部工具进来，失联不拖垮 (Priority: P2)

按 `.sableos/mcp_servers.yaml` 连接外部 MCP server（方式二轻代码档的运输层）：启动时取工具列表、逐个包装注册；调用时按协议转发、结果包成统一结果。某个 server 失联只报 WARN 跳过它的工具——外部依赖的可用性不是自己的可用性；配置缺失按零 server 处理，启动照常。

**Why this priority**: 零代码 / 轻代码两档 Plugin Tool 都靠它；31 节日报 Agent 硬依赖（课件明言"不能只停在概念"）。

**Independent Test**: mock 客户端——好 server 的工具照常注册、坏 server 抛连接异常时启动不炸且它的工具不在注册表；execute 转发参数原样、结果正确包装；配置文件逐项解析、文件缺失视为零 server。

**Acceptance Scenarios**:

1. **Given** 两个配置的 server 一好一坏，**When** 启动连接，**Then** 不抛异常；好 server 的工具在注册表、坏的不在、告警日志留痕。
2. **Given** MCP 工具被调用，**When** execute，**Then** 参数原样转发给对应 server、成功结果包装返回；调用失败包成"失败且可重试"。
3. **Given** 配置文件（name/transport/command/env），**When** 加载，**Then** 逐项解析；文件缺失按零 server 处理不报错；server 返回空工具列表 = 零注册，正常。

---

### User Story 4 - 注解式接入：方式三管道 (Priority: P2)

业务方（和内置工具自己）把 Java 方法标上 `@Tool` 注解：参数 schema 自动生成、自动包装成统一抽象注册。写法与内置工具完全一样，进程内直调、不走 MCP 协议，性能最好。

**Why this priority**: 重代码档是集成深度最好的路（可复用现有 Spring Bean）；内置工具走同一条管道，管道本身被日常使用持续验证。

**Independent Test**: 一个注解方法经管道注册后，从注册表取出断言名称/描述/schema 齐全且 schema 含参数描述；execute 传 JSON 实参断言方法真的被调、返回值包成统一结果。

**Acceptance Scenarios**:

1. **Given** 标注 `@Tool` 注解的方法（带参数说明），**When** 经管道注册，**Then** 注册表可查、schema 自动生成且含该参数描述。
2. **Given** 该工具被 execute，**When** 传 JSON 实参，**Then** 方法以正确实参执行、返回内容进统一结果；方法抛异常时由执行层统一收敛为失败结果（管道自身不吞异常）。

---

### User Story 5 - 五个扩展基础工具：对齐业界水准 (Priority: P2)

在规划的九个内置工具之外，补齐日常任务里高频、缺了就明显笨手笨脚的五样——它们全都复用已立好的统一抽象、注解管道与安全校验位，正是"加工具成本恒定在写一个类"的实战检验：`edit_file`（唯一匹配的局部编辑）、`grep` / `glob`（找内容 / 找路径）、`ask_user`（human-in-the-loop 的落点）、`web_search`（上网查的第一入口）。

**Why this priority**: 这几样是"基础工具"而非"业务能力"——任何稍复杂的任务都会用到，值得进内置清单而不是丢给每家 Plugin 各写一遍。

**Independent Test**: 逐个工具两条：正常输入能跑通；边界语义钉死（edit_file 找不到 / 多处匹配报错且文件不动；grep/glob 超 200 条截断注明；grep 遇二进制 / 非 UTF-8 文件跳过不中断；ask_user 在无人值守渠道明确报不支持；web_search 第一件事过 HTTP_REQUEST 白名单）。

**Acceptance Scenarios**:

1. **Given** 文件里有一段唯一出现的旧文本，**When** edit_file 替换，**Then** 只改该处；旧文本找不到 / 出现多次，**Then** 明确报错且文件一个字都不动。
2. **Given** 一个含多个文件的目录，**When** grep 按正则搜内容，**Then** 返回"文件:行号:内容"；glob 按通配（如 `**/*.yaml`）找路径；匹配超过 200 条，**Then** 截断并在结果里注明。
3. **Given** 搜索命中二进制 / 非 UTF-8 文件，**When** grep 执行，**Then** 跳过该文件、不中断整次搜索。
4. **Given** 交互渠道（终端）**When** ask_user 提问，**Then** 拿到用户回答返回；无人值守渠道（定时 / Web 无状态），**Then** 明确报"不支持交互"，绝不静默卡住。
5. **Given** 联网检索，**When** web_search 执行，**Then** 第一件事过 HTTP_REQUEST 白名单（引擎入口不绕过治理）；换搜索引擎只加实现类，工具与调用方不改。

---

### User Story 6 - notify 完成注册：19 节预留的接线兑现 (Priority: P3)

19 节交付的通知抽象（接口 / 通知目标 / 通用 webhook 实现）在本节完成工具侧注册：notify 以统一抽象形态进注册表，渠道解析按 19 节登记契约执行（缺省 / 空白 / `default` 取注册表第一个、未注册不回退、上下文缺失报错、校验先于发送）。

**Why this priority**: 课件交付物明言"`NotifyTools`（19 节）在此完成注册"；它是 25 节定时与 31 节 Demo 的出口，但完成时点依赖本节与 23/24 节两段接线。

**Independent Test**: 构造含渠道的注册来源与空来源，断言：缺省取第一个、显式按名选中、无渠道 / 上下文缺失报错且零请求；`InOrder` 钉死"校验先于发送"。

**Acceptance Scenarios**:

1. **Given** 渠道来源已配置，**When** 只传 content（或 channel 传空白 / `default`）调用 notify，**Then** 内容送往第一个渠道；显式传渠道名，**Then** 送往该渠道。
2. **Given** 一个渠道都没有（或渠道名不存在 / 当前 Agent 上下文缺失），**When** 调用 notify，**Then** 返回明确错误点名原因、零请求发出。
3. **Given** 工具执行链路（24 节后补跑），**When** 调用 notify，**Then** 安全校验先于发送被调用——顺序反了就是漏洞。
4. **Given** 运行时装配完成，**When** 查看工具清单，**Then** notify 在已注册列内。

---

### Edge Cases

- 注册重名工具 → 明确报错拒绝（静默覆盖会让两个来源打架且难查）。
- Profile tools 声明了注册表里不存在的名字 → 过滤结果不含它、不报错——该名字可能来自尚未连接的 MCP server；"不多不少"断言以声明名全部存在为前提。
- Profile 未声明任何工具 → 过滤结果为空（最小权限：不声明就什么都拿不到）。
- read_file 读不存在的文件 / list_dir 列不存在的目录 → 失败结果点名路径，不抛裸异常。
- write_file 目标目录不存在 → 失败结果点名（不擅自递归建目录——那是模型该显式做的决定）。
- shell 命令非零退出码 → 失败结果携带标准错误；超时 → 失败结果注明超时并按超时终止进程。
- http 非 2xx → 失败结果带状态码（查询类工具失败模型可自救，包结果不抛；notify 的发送失败仍是异常上抛——两条口径并存、互不混用）。
- 安全校验器未装配（如单元环境）→ 工具构造强制要求校验器参数，不允许"没有校验器就裸奔"。
- MCP server 返回空工具列表 → 正常，零注册不报错。
- `mcp_servers.yaml` 不存在 → 视为零 server，启动照常；文件存在但结构非法（如顶层不是列表）→ 明确报错点名，不静默吞掉。
- MCP 调用失败 → 包成"失败且可重试"（外部故障多为瞬态）；失联 server 的 WARN 不阻断启动。
- edit_file 旧文本找不到 / 出现多次 → 报错且文件不动（逼调用方给足上下文定位唯一编辑点）。
- grep / glob 匹配超上限 → 截断并注明；grep 遇二进制 / 非 UTF-8 文件 → 跳过不中断。
- ask_user 在无人值守渠道 → 明确报"不支持交互"，绝不静默卡住（拿不到回答就抛异常）。
- web_search 命中白名单外域名 → 与 http_get 同口径被拦、请求零发生。
- notify 渠道来源为空 / 渠道名不存在 / 上下文缺失 → 明确报错点名、零请求（见 US6）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 以统一工具抽象（名称 / 描述 / 输入 schema / 执行四要素）承载所有来源的工具，注册进唯一注册表；ReAct 循环与执行器 MUST 只依赖抽象、不感知来源；重名工具注册 MUST 明确报错拒绝；同名工具二次注册不得静默覆盖。
- **FR-002**: MUST 交付六个内置工具：read_file、write_file、list_dir、shell（带超时；不经 Shell 解释、直接执行白名单内的可执行文件与参数数组）、http_get、http_post；与既有 notify（本节完成注册）、save_memory / recall_memory（22 节注册）构成九个内置工具面。
- **FR-003**: 本节 MUST 交付安全校验前向接口五件（校验接口 / 动作描述 / 动作类型四值（文件读、文件写、Shell 命令、HTTP 请求）/ 违规异常 / 临时放行的装配实现——放行时 MUST 逐次告警留痕、绝不静默裸奔，实现本体归 23/24 节）；每个涉外内置工具 execute 的第一步 MUST 调用校验（文件→路径、Shell→命令、HTTP→域名）；校验抛违规异常时外部动作 MUST 零发生。
- **FR-004**: MUST 提供注解式接入管道：`@Tool` 标注的方法 → 参数 schema 自动生成 → 包装为统一抽象注册；内置工具与业务方方式三 MUST 共用同一条管道。
- **FR-005**: MUST 提供 MCP 接入：读 `.sableos/mcp_servers.yaml`（name / transport / command / env）连接外部 server、调 tools/list 把每个工具包装成统一抽象注册、调用时按协议（JSON-RPC）转发参数、结果包装为统一结果（失败含可重试标记）；单个 server 失联 MUST 告警跳过、不拖垮启动，其余工具照常注册；配置文件缺失 MUST 视为零 server。
- **FR-006**: 注册表 MUST 支持按 Profile 的 tools 字段过滤，结果为"声明列表中存在于注册表的项"且与声明的存在项精确相等（不多不少）；未知名跳过不报错；空声明得空结果。
- **FR-007**: 运行时装配 MUST 用注册表替换既有空工具表，接通 17 节执行器与 Prompt 组装、18 节 chat 链路；工具清单命令 MUST 保持轻命令（零 Spring）并刷新为静态内置清单、如实标注运行期实时清单归 26 节 Web 管理台（对齐 oryxos 验收口径：轻命令拿不到注册表，实时清单经管理台补）。
- **FR-008**: MUST 交付三个文件扩展工具（同归文件工具族、同一安全校验、方法第一行过校验）：edit_file（旧文本唯一匹配才替换：找不到 / 出现多次均报错且文件保持不动）、grep（正则搜内容、返回"文件:行号:内容"、跳过二进制 / 非 UTF-8 文件不中断）、glob（通配找路径）；grep 与 glob MUST 有返回条数上限（200 条）并在截断时注明。
- **FR-009**: MUST 交付 ask_user：依赖交互抽象（接口先行），接口语汇零渠道特有词；拿不到回答 MUST 抛异常、绝不静默卡住；核心阶段挂两档实现（终端读一行 / 无人值守渠道明确报不支持）；工具自身只把问题递给交互抽象、把回答带回来。
- **FR-010**: MUST 交付 web_search：依赖搜索抽象（接口先行）；工具执行第一件事 MUST 过 HTTP_REQUEST 白名单；核心阶段挂一档免密钥搜索引擎实现；换搜索引擎只加实现类，工具与调用方不改。
- **FR-011**: MUST 完成 NotifyTools 注册（19 节交付物在本节接线）：以统一抽象形态进注册表；渠道解析按 19 节登记契约执行——channel 缺省 / 空白 / 字面量 `default`（哨兵优先于同名匹配）取注册表第一个；显式渠道名不存在 MUST 明确报错、不回退默认（回退会把消息发错地方）；当前 Agent 上下文缺失 MUST 明确报错；校验 MUST 先于发送（顺序反了就是漏洞）；成败 MUST 走 17 节既有工具审计路径、不建旁路。渠道来源形态（clarify 拍板）：用现有类型的最小组合——有序 `Map<渠道名, 通知目标>`（零新对外类型）；本节装配为空表（报错点名"无已注册渠道"、零请求），注册表本体（SQLite + CRUD）留后续节落地、届时不改 `NotifyTools`。

### Key Entities *(include if feature involves data)*

- **统一工具抽象（SableTool，17 节已交付）**: 四要素契约（名称 / 描述 / 输入 schema / 执行）；本特性不改它。
- **工具结果（ToolResult，17 节已交付）**: 成功标识、结果内容、错误信息、是否可重试；本特性不改它。
- **工具注册表（ToolRegistry）**: 名称→工具的唯一权威；两条注册路径——直接注册（通知工具 / MCP 适配）与注解管道注册（内置工具与方式三共用）；按 Profile tools 过滤；实现 17 节交付的目录契约，运行时替换空工具表。
- **注解管道适配（AnnotatedToolAdapter）**: 把 `@Tool` 方法产生的回调（schema 已自动生成）包装成统一抽象；只借 schema 生成与反射调用，不引入框架自动执行路径。
- **内置工具组（FileTools / ShellTools / HttpTools + 扩展三工具）**: read_file、write_file、list_dir、edit_file、grep、glob、shell、http_get、http_post；执行第一步过安全校验。
- **安全校验前向接口（Sandbox 五件）**: 校验接口（enforce(动作)）、动作描述（类型 + 目标）、动作类型四值（文件读 / 文件写 / Shell 命令 / HTTP 请求——本节定死，24 节沿用）、违规异常、临时放行装配（放行必告警留痕）；白名单实现归 23/24 节。
- **MCP 接入（McpClientService + McpToolAdapter）**: 连接维护 / 启动时注册 与 协议转发 / 结果包装；失联隔离。
- **MCP 配置（`mcp_servers.yaml`）**: name / transport / command / env 列表；由使用者提供。
- **通知工具（NotifyTools）**: notify 的统一抽象实现；渠道解析 + 校验先于发送 + 委托适配器；渠道来源形态见 FR-011 澄清。
- **交互抽象（UserInteraction）**: "向用户提问并拿到回答"的意图表达；核心阶段两档实现（终端 / 无人值守报不支持）。
- **搜索抽象（SearchProvider）**: "按查询词搜网页"的意图表达，结果含标题 / 链接 / 摘要；核心阶段一档免密钥实现。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 注册表内每个工具 100% 通过契约三件套检查（参数化遍历，新工具自动纳入；任何工具漏实现输入 schema 立刻失败）。
- **SC-002**: 六个内置工具 12 条基本用例（正常 + 拦截）全绿；被拦时外部动作零发生。
- **SC-003**: 单个 MCP server 失联时启动成功率 100%，其余工具注册率 100%（mock 客户端断言）。
- **SC-004**: 按 tools 过滤的子集与声明的存在项 100% 精确相等（多一少一都失败）。
- **SC-005**: 五个扩展工具边界语义全部自动化钉死（edit_file 唯一匹配、grep/glob 截断与跳过、ask_user 无人值守报错、web_search 白名单先行）。
- **SC-006**: 运行时装配后 chat 链路可真实调用 http_get 完成查询（Demo 一"真的去查天气"的动作落地——真模型人工项）；工具清单命令可见当前注册工具（按 US1 场景 6 澄清结论）。
- **SC-007**: notify 渠道解析四态（缺省取第一 / 显式按名 / 未注册报错 / 上下文缺失报错）自动化断言通过，校验先于发送顺序以 `InOrder` 钉死。

## Assumptions

- 前序交付（均已核实存在）：17 节 `SableTool` / `ToolResult` / `ToolCatalog`（目录契约）/ `ToolExecutor` / `ProfileContext` 于 core，`ToolExecutor` 留有的校验接线位注明"Sandbox 接口五件归 20 节"；18 节 CLI 轻 / 重命令骨架与 `tool list` 轻命令占位（其注释明言"工具注册归 20 节"）；19 节 Notify 抽象三件（接口 / 通知目标 / 通用 webhook 实现）于 `sableos-tool`，其 NotifyTools 契约已登记（渠道解析四态 + `InOrder` + 审计走既有路径）；16 节 Provider 配置与审计落库路径。
- 各实现全落既有模块 `sableos-tool`（技术方案 §6/§10：内置 Tool、MCP Client、注册表、Sandbox 三合一模块，AGENTS.md §5.2"不把 Tool 拆多模块"）；统一抽象与结果已在 core，本特性不改；零新模块、零数据库迁移。
- 安全校验前向接口为 23/24 节交付物的前向最小定义（课件明文"先以接口调用形式接入，Sandbox 本体 23/24 节交付"）；动作类型四值本节定死、24 节沿用（24 节课件明载）；临时放行的装配实现仅为本节可运行性（24 节替换为白名单实现），放行逐次告警留痕、绝不静默裸奔。
- 注解管道与 MCP 客户端使用项目锁定 BOM 内组件（Spring AI `@Tool` 生态、`io.modelcontextprotocol.sdk:mcp`，父 POM 已锁定版本、H0 阶段实核在位）；新增依赖在 plan 列明，均为 BOM 已管理构件、非新第三方。
- shell 超时默认 30 秒（课件与技术方案未给数值，先常量落地 + 注明；配置化归 24 节白名单配置一并处理）。
- MCP 配置解析为零新第三方（YAML 解析用 BOM 管理的既有构件）；核心阶段实际连接以 stdio 为准（测试以 mock 客户端覆盖协议转发，不真起子进程、不碰网）。
- NotifyTools 的完整验证按 19 节登记口径：第二批 harness（渠道解析 + `InOrder`，mock 校验器与适配器）在 24 节后补跑、27/28 节串联时全量验证；本节交付其实现与注册（完成"在此完成注册"）、登记其测试。渠道来源用有序 `Map<渠道名, 通知目标>`（clarify #1）、本节装配空表。
- 工具清单命令口径（clarify #2）：`tool list` 保持轻命令（零 Spring 秒回）+ 刷新静态内置清单、如实标注；运行期实时清单归 26 节 Web 管理台；课件人工项"@Tool 示例工具在 tool list 里可见"随此调整（方式三真跑经 chat 真实调用验证——内置工具走同一条注解管道），对齐 oryxos 20 节验收口径。
- 实现顺序（授课顺序 ≠ 构建顺序）：harness 先行——契约三件套与注册表过滤测试先于 / 伴随实现完成；MCP 相关测试全程 mock、不依赖真 server。
- 明确不做（边界）：Tool Policy（哪个 Agent 能用哪些工具的 allow / deny 规则，扩展阶段）；工具太多时的按需加载；把 SableOS 自己变成 MCP server 对外暴露；完整的容器级沙箱（白名单实现归 23/24 节）；一次响应里多个工具并行调——核心阶段先用"白名单 + Profile 的 tools 字段限定"顶住。
- 扩展五工具判定口径（课件第六部分）：它们是"基础工具"而非"业务能力"（任何稍复杂任务都会用到），故进内置清单；各自不新起抽象层——三个文件工具扩进文件工具族，ask_user / web_search 各配一个前向接口 + 一档实现。
