# Implementation Plan: Notify——结果主动送出去的统一出口

**Branch**: `004-lesson19-notify` | **Date**: 2026-10-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-lesson19-notify/spec.md`

## Summary

交付"出站通知"的第一块地：**通知抽象三件**（`NotifyChannelAdapter` 接口 / `NotifyTarget` 通知目标 / `WebhookNotifyAdapter` 通用 webhook 实现）+ **第一批 harness**，全部落 `sableos-tool` 的 `com.sableos.tool.notify` 包。入站有 Channel 负责"消息怎么进来"，本节补对称的另一半——"结果怎么主动送出去"：定时触发（25 节）与日报 Demo（31 节）跑完 ReAct 循环后，靠这一出口把结果送到人能看到的地方。

技术路径（决策详见 research D-01~D-07）：

- **`sableos-tool`**（唯一改动模块）：
  - `NotifyChannelAdapter`：`void send(NotifyTarget target, String content)`——签名与课件逐字一致；接口语汇零渠道特有词（接口先行，SC-005）。
  - `NotifyTarget`：`record(String channelType, Map<String,String> config)`——课件签名 + 追加 compact 构造防御拷贝（`null → Map.of()`、否则 `copyOf`，对齐 core `ProviderResponse` 先例），`config` 恒非 null 且不可变。
  - `WebhookNotifyAdapter`：核心阶段唯一实现，纯 POJO（零 Spring 注解，装配归 20 节）；构造注入 `RestClient`；缺 url 快速失败（IAE 含 `url`、零请求），正常路径 `post().uri(url).contentType(APPLICATION_JSON).body(Map.of("content", content)).retrieve().toBodilessEntity()`；4xx/5xx 与连接失败依赖 RestClient 默认判定异常上抛（不注册吞错处理器）；3xx 不特判（出网管控归 23/24 节）。
  - `WebhookNotifyAdapterTest`：假 webhook 用 JDK 内置 `HttpServer`（零新依赖），四条回归断言（POST 恰好一次 + body 带 content / 地址来自配置非硬编码 / 5xx 上抛 / 缺 url 报错点名零请求）。
- **登记不落码**（课件「实现顺序说明」明载、clarify 拍板）：`NotifyTools` 完整接线（`@Tool` 注册归 20 节、`Sandbox.enforce` 归 23/24 节）与其第二批 harness 的完成时点在 24 节之后；本契约先定死于 [contracts/notify-tool.md](./contracts/notify-tool.md)（渠道解析：缺省/空白/`default` → 注册表第一个、未注册不回退、上下文缺失报错；enforce 先于 send；审计走 `ToolExecutor` 既有路径）。
- **渠道模型**：全局注册表按名引用（技术方案 §6.8 / AGENTS.md §6，clarify #1 拍板）——课件 `Profile.notify_channels` 字段为过时漂移，本节不建任何 Profile 字段、不建表。

## Technical Context

**Language/Version**: Java 21（enforcer 钉死 `[21,)`；P3C/PMD 以 17 目标语法解析，避开 Java 18+ 语法形态：增强 switch 的 `default ->`、record pattern、pattern switch 一律不用；records/sealed 可用）

**Primary Dependencies**:

- **本节零新增第三方依赖**（软门禁 #6）：
  - `org.springframework:spring-web`（**BOM 管理，无版本** → Spring Framework 6.2.19）——`RestClient` 全链路已在本地 jar 逐类核实（research 附录 A）；这是 `sableos-tool` 相对 Boot BOM 的增量引用，非新第三方构件。
  - `com.fasterxml.jackson.core:jackson-databind`（core 编译依赖，传递可达）——`RestClient.create()` 默认转换器按类路径探测 Jackson（附录 A-3），`.body(Map)` 序列化为 `application/json`。
  - 测试：父 POM 自动注入 `spring-boot-starter-test`（JUnit5/Mockito/AssertJ，test scope，全模块）；假 webhook 用 JDK 标准模块 `jdk.httpserver`——均零新增。
- 消费前序节交付：`SableTool`/`ToolResult`/`ToolExecutor`（17 节 core，登记契约引用）；`ProfileContext`（17 节，登记契约引用）。本节实现层不触碰这些类型。

**Storage**: **N/A——本节零数据库迁移、零新表**。渠道注册表（`notify_channels` 表 + CRUD）随后续节落地，本节只依赖其"有序 + 按名查找"语义（data-model §5）。禁 `ddl-auto` 口径不变（本节不涉）。

**Testing**: JUnit 5 + AssertJ + Mockito（父 POM 注入）。harness `WebhookNotifyAdapterTest` 在 **sableos-tool**，**零 Spring 上下文**：`new WebhookNotifyAdapter(RestClient.create())` 直连 JDK `HttpServer` 假 webhook（随机端口，不出网、零 key）。测试方法名英文驼峰，课件中文原文进 `@DisplayName`。第二批 `NotifyToolsTest` 登记（24 节后）。集成冒烟打 `@Tag("integration")` CI 跳过（本节不新增）。

**Target Platform**: JVM 服务端单体（fat JAR 形态不变；本节不涉启动/装配）。

**Project Type**: Maven 多模块库（改动集中在 1 个既有模块 `sableos-tool`：3 个主类 + 1 个测试 + pom 加 1 个 BOM 依赖；不新增模块）。

**Performance Goals**: N/A——同步阻塞一次 POST，无量化目标（不引入异步/反应式模型）。

**Constraints**:

- 已定字面量逐字保真（软门禁 #2）：接口方法签名、record 组件、RestClient 调用链形态与课件一致；防御拷贝为 record 内部不变量（签名零改）。
- 失败口径固定：4xx/5xx 与连接失败必上抛；不注册吞错处理器；3xx 不做特判、不声称已管控（clarify #2）。
- 纯 POJO 零装配：不加 `@Component`、不建 `@Configuration`（装配归 20 节 ToolRegistry）。
- 不出现 Sandbox 类型、不发明校验逻辑；enforce 调用位登记于 `NotifyTools` 契约（24 节接线）。
- url/config 不进日志（FR-005：地址可能内嵌凭证 token）；本类无用户可控字符串入日志。
- 避开 Java 18+ 语法；首次提交前 `mvn spotless:apply`（格式违规挂构建）。
- 全程不自动 commit/push/运行 package.sh（同步时机由用户决定）。

**Scale/Scope**: 1 模块（`sableos-tool`）新增 4 文件 + pom 1 依赖；0 表 / 0 迁移 / 0 新第三方；登记（不落码）：`NotifyTools` + 第二批 harness（24 节后）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据/说明 |
| --- | --- | --- |
| I. JDK 21 + Spring Boot 3.x 单体 | ✅ | 目标字节码 21；`spring-web` 为 BOM 构件；不引响应式；不新增模块；单二进制不变 |
| II. 自实现 ReAct 循环 | ✅ | 本节不触碰循环；`send` 是同步阻塞的一次调用，最终由 20/24 节接入 `ToolExecutor` 编排 |
| III. Spring AI 只用一半 | ✅ | 本节不引 Spring AI 类型（`@Tool` schema 归 20 节）；无任何自动工具执行路径 |
| IV. Provider 显式映射 | ✅ | 本节不触碰 Provider 路由 |
| V. Tool 三档且单模块；Agent 目录不当 Tool | ✅ | notify 三件落**单模块** `sableos-tool`（三合一，不拆新模块）；纯 POJO 待 20 节 ToolRegistry 统一收编 |
| VI. 审计 day one 落库 | ✅(不触碰) | 本节零迁移（无新表）；通知成败的审计路径**登记**为 `ToolExecutor` 既有 `tool_invocations` 路径（24 节接线后生效），不新增审计逻辑、不改表 |
| VII. Sandbox 接口先行 | ✅(兑现) | 本节不新建 Sandbox 类型、不发明校验逻辑；`enforce` 先于 `send` 的调用位与顺序断言登记于 `contracts/notify-tool.md`（24 节接线） |
| VIII. 范围收敛 | ✅ | 企微/飞书/钉钉专用 API、Sandbox 校验本身、注册表本体（表/CRUD）、`NotifyTools` 实现全部在范围外（课件边界 + clarify 拍板）；零扩展性提前设计 |
| IX. 每个 US 可演示 Demo | ✅ | US1/US2 由 harness 即 Demo：本地假 webhook 收 POST、5xx 上抛、缺 url 零请求；US3/US4 顺延登记（24 节后） |

**Post-Phase 1 re-check**: 设计未引入新违规。三处已拍板取舍记入 Complexity Tracking：①`NotifyTools` 与第二批 harness 顺延 24 节后（课件明载 + clarify）；②`sableos-tool` 新增 `spring-web`（BOM 构件，非新第三方）；③`default` 哨兵与 3xx 顺延（clarify #1/#2）。一处静态门禁预案（非违规）：`WebhookNotifyAdapter` 构造注入 `RestClient` 若触发 SpotBugs `EI_EXPOSE_REP2`，按 `PromptBuilder` 先例做方法级定点抑制（research D-07）。

## Project Structure

### Documentation (this feature)

```text
specs/004-lesson19-notify/
├── plan.md              # 本文件
├── spec.md              # /speckit-specify + /speckit-clarify 产出（5 条澄清）
├── research.md          # Phase 0：D-01~D-07 决策 + 附录 A（RestClient 逐类核实证据）
├── data-model.md        # Phase 1：NotifyTarget / 适配器 / 线格式 / 注册表预告 / 零迁移
├── quickstart.md        # Phase 1：可执行的验收向导（harness + 人工项 + H4 自查）
├── contracts/
│   ├── notify-adapter.md    # 本节实现：接口 / 行为契约 / 接口中立性 / harness 映射
│   └── notify-tool.md       # 登记：NotifyTools 契约（渠道解析 + InOrder + 审计，24 节后落）
├── checklists/
│   └── requirements.md      # 规格质量清单（16/16）
└── tasks.md             # /speckit-tasks 产出
```

### Source Code (repository root)

```text
sableos-tool/pom.xml                          # 本节修改：+ org.springframework:spring-web（无版本，Boot BOM 管理）

sableos-tool/src/main/java/com/sableos/tool/notify/
├── NotifyChannelAdapter.java                 # 本节新增：接口，void send(NotifyTarget, String)（课件逐字）
├── NotifyTarget.java                         # 本节新增：record + 防御拷贝 compact 构造（ProviderResponse 先例）
└── WebhookNotifyAdapter.java                 # 本节新增：核心阶段唯一实现（RestClient 直发 + 缺 url 快速失败）

sableos-tool/src/test/java/com/sableos/tool/notify/
└── WebhookNotifyAdapterTest.java             # 本节新增：第一批 harness（JDK HttpServer 假 webhook，4 断言）

# 登记（本轮不落码，完成时点 24 节之后）——契约见 contracts/notify-tool.md：
#   com.sableos.tool.builtin.NotifyTools（SableTool 形态，@Tool 注册归 20 节）
#   com.sableos.tool.builtin.NotifyToolsTest（第二批 harness：渠道解析三断言 + InOrder）
```

**Structure Decision**: 全部落既有模块 `sableos-tool`（技术方案 §6.8：内置 Tool + Sandbox + Notify 三合一模块；AGENTS.md §5.2"不把 Tool 拆多模块"）；包名 `com.sableos.tool.notify` 对齐课件；测试与实现同模块。零新模块、零迁移、零新第三方依赖。依赖方向不变（`core ← sableos-tool`；本节只用到 core 传递的 `jackson-databind` 与 BOM 管理的 `spring-web`）。

## Complexity Tracking

| 取舍 | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| `NotifyTools` 与第二批 harness 顺延至 24 节之后（本轮只登记契约） | 课件「实现顺序说明」明载（授课顺序 ≠ 构建顺序）：`@Tool` 注册归 20 节、`Sandbox.enforce` 归 23/24 节——本轮做了也接不了线、第二批 harness 照样跑不了 | 本轮硬做（mock 直调）→ 双份返工；课件明确其完成时点在 24 节后、27/28 节串联全量验证。**登记为契约 + tasks 任务，Analyze 据此判非漂移** |
| `sableos-tool` 新增 `spring-web` 依赖 | `RestClient` 是课件骨架的 HTTP 客户端（签名保真的前提）；`spring-web` 属 Boot BOM 管理构件（6.2.19），非新第三方（软门禁 #6 不触发） | 引 `spring-boot-starter-web` 整包 → 只为一个 RestClient 拖进 tomcat 一大串；自写 HttpClient 封装 → 违课件骨架与"不过度设计"。**软门禁 #6 边界：BOM 内既有构件** |
| `channel` 字面量 `default` 按哨兵取第一个渠道（优先于同名匹配） | 课件 harness 示例 `notify("hello", "default")` 的意图即"用默认渠道"；clarify #1 拍板（对标 oryxos 同节澄清先例） | 按同名渠道匹配 → `default` 未注册即报错，与课件示例意图相悖；引入一个保留渠道名参与同名匹配 → 语义坑。**哨兵语义最直白** |
| 3xx 重定向本节不做特判（依赖默认不跟随、不声称管控） | RestClient 默认判定 = 4xx/5xx 才抛（字节码核实）；出网安全策略（禁跟随/逐跳复检/缺 Location 报错）与白名单同批归 23/24 节 | 本节自造 redirect 管控 → 越界 + 未经验证逻辑；oryxos 先例同为出网组件落地后才补 redirect 断言。**clarify #2 拍板** |
| `WebhookNotifyAdapter` 构造注入 `RestClient`（若触发 `EI_EXPOSE_REP2` 则方法级定点抑制） | 课件构造签名保真（软门禁 #2）；`PromptBuilder` 存 `ContextLoader` 已有同款先例（全仓仅 3 处定点抑制、均带 justification） | 为避免告警改构造签名（如注入 request factory）→ 改已定字面量、且真实告警应按先例抑制而非改设计 |
