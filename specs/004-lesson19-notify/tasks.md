---
description: "Task list for 004-lesson19-notify"
---

# Tasks: Notify——结果主动送出去的统一出口

**Input**: Design documents from `/specs/004-lesson19-notify/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: 课件「验收 harness」明确要求 `WebhookNotifyAdapterTest`（第一批），故**测试为必需**（非可选），且 **harness 先行**（测试任务先于/伴随对应实现）。`NotifyToolsTest`（第二批）按课件「实现顺序说明」在 24 节后补跑——本轮**登记**、不实现（T008/T009）。测试方法名英文驼峰，课件中文原文进 `@DisplayName`。

**Organization**: 按 user story 分组。**US1/US2/US4 共享同一实现面**（一个适配器类 + 一个测试类），故按 US1（正常发送）→ US2（失败口径收口）→ US4（换渠道闭环）的顺序在**同一文件面上**推进，各 story 的测试点仍可独立判卷；US3（NotifyTools 渠道解析）**全量顺延**至 24 节后，置末登记。

**范围与漂移标记**：
- 【漂移剔除】课件「配置」交付物"Profile 新增 `notify_channels` 字段"——按技术方案 §6.8 / AGENTS.md §6 **全局注册表按名引用**（SG#3 已拍板），本轮不建 Profile 字段、不建表、零迁移；注册表本体（表 + Web CRUD）随后续节。
- 【顺延】`NotifyTools` 与 `NotifyToolsTest`（第二批 harness）——课件「实现顺序说明」明载完成时点在 24 节之后；本契约已定死于 [contracts/notify-tool.md](./contracts/notify-tool.md)。
- 【拍板】`sableos-tool` 新增 `spring-web`（Boot BOM 管理、非新第三方构件）；假 webhook 用 JDK 内置 `HttpServer`（零新依赖）；实现纯 POJO（装配归 20 节）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属 user story（US1–US4）
- 每条任务含确切文件路径

## Path Conventions

Maven 多模块，模块内标准布局：`sableos-<module>/src/main/java/com/sableos/...` 与 `sableos-<module>/src/test/java/com/sableos/...`；本节只动 `sableos-tool` 一个模块。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 依赖口径落地（本节零新增第三方依赖，软门禁 #6）

- [x] T001 改 `sableos-tool/pom.xml`：新增 `org.springframework:spring-web`（**不带版本**，Boot BOM 管理 → 6.2.19）；跑 `mvn -q -pl sableos-tool dependency:tree` 确认 `spring-web:6.2.19` 与传递的 `jackson-databind` 均解析到位、无新第三方构件（research D-05、附录 A-3/A-4）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 通知抽象的前两件（US1/US4 实现与测试的编译前提）；纯 POJO、零框架

**⚠️ CRITICAL**: US1/US2/US4 均依赖本阶段完成

- [x] T002 [P] 新增 `NotifyChannelAdapter` 接口（`void send(NotifyTarget target, String content)`——**签名与课件逐字一致**；接口语汇零渠道特有词）于 `sableos-tool/src/main/java/com/sableos/tool/notify/NotifyChannelAdapter.java`（[contracts/notify-adapter.md](./contracts/notify-adapter.md)、research D-01）
- [x] T003 [P] 新增 `NotifyTarget` record（`record NotifyTarget(String channelType, Map<String, String> config)` + **追加** compact 构造防御拷贝：`config == null → Map.of()`、否则 `Map.copyOf`，对齐 core `ProviderResponse` 先例）于 `sableos-tool/src/main/java/com/sableos/tool/notify/NotifyTarget.java`（[data-model.md](./data-model.md) §1、research D-01）

**Checkpoint**: 抽象两件就位——harness 可编译

---

## Phase 3: User Story 1 - 定时结果主动送达 (Priority: P1) 🎯 MVP

**Goal**: 一条内容经 `WebhookNotifyAdapter` 发向 `NotifyTarget.config()["url"]`——POST 恰好一次、body 带 content

**Independent Test**: `WebhookNotifyAdapterTest` 四条回归全绿（①POST 一次 + body 带 content 由本 story 判；②③④分别由 US4/US2 在各自阶段核对）

### Tests for User Story 1（harness 先行）⚠️

- [x] T004 [US1] 写 `WebhookNotifyAdapterTest`（**课件 harness 第一批原样落地、全部四条方法一次写齐**——单文件不可跨阶段拆分，②③④的 story 归属在 Description 注明）于 `sableos-tool/src/test/java/com/sableos/tool/notify/WebhookNotifyAdapterTest.java`：
  - 假 webhook = JDK 内置 `HttpServer`（`new InetSocketAddress(0)` 随机端口；`handler` 记录 `record ReceivedRequest(String method, String path, String body)` 到 `CopyOnWriteArrayList`；`volatile int responseStatus = 200` 可切 500；`urlOf(path)` 拼 `http://127.0.0.1:<port>`）（research D-04/D-07）
  - `@BeforeEach`：`adapter = new WebhookNotifyAdapter(RestClient.create())`（零 Spring 上下文）；`webhookTarget(url)` = `new NotifyTarget("webhook", Map.of("url", url))`
  - ①`sendPostsJsonBodyContainingContent`（`@DisplayName("发送后收到一次 POST，body 里带 content")`）：断言 `received.size()==1`、method=`POST`、body 含内容原文、body 含 `"content"`（按约定包成 `{"content": ...}`）【US1·SC-001】
  - ②`urlComesFromTargetConfigNotHardcoded`（`@DisplayName("换通知目标只改配置零代码")`）：同一实现先后发 `/group-a`、`/group-b`，断言两条请求各达其址【US4·SC-003】
  - ③`serverErrorPropagatesAsException`（`@DisplayName("webhook 返回 5xx 异常向上抛、不静默吞掉")`）：`responseStatus=500`，`assertThrows(RestClientResponseException.class, ...)`【US2·SC-002】
  - ④`missingUrlFailsFastWithoutRequest`（`@DisplayName("缺 url 配置：报错点名且零请求发出")`）：`new NotifyTarget("webhook", Map.of())` → `assertThrows(IllegalArgumentException.class, ...)` 且 `ex.getMessage().contains("url")`、`received` 零请求【US2·边界】
  - **先写、先跑确认红**（实现类尚不存在）：`mvn -q -pl sableos-tool -am test -Dtest=WebhookNotifyAdapterTest`

### Implementation for User Story 1

- [x] T005 [US1] 实现 `WebhookNotifyAdapter implements NotifyChannelAdapter`（构造注入 `RestClient`；`send`：①`String url = target.config().get("url")`，**null/空白即抛** `IllegalArgumentException("webhook 渠道缺少 url 配置（通知渠道注册项需要 url 键）")`（点名 `url`、零请求）；②`restClient.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(Map.of("content", content)).retrieve().toBodilessEntity();`；**不注册任何响应错误处理器**（失败依赖默认判定上抛）；**纯 POJO 零 Spring 注解**；url/config 不进日志；若 SpotBugs 判 `EI_EXPOSE_REP2` 按 `PromptBuilder` 先例做方法级定点抑制并写 justification）于 `sableos-tool/src/main/java/com/sableos/tool/notify/WebhookNotifyAdapter.java`（依赖 T002/T003；[contracts/notify-adapter.md](./contracts/notify-adapter.md)、research D-02/D-03/D-07）
- [x] T006 [US1] 跑 `mvn -q -pl sableos-tool -am test -Dtest=WebhookNotifyAdapterTest` 四条全绿

**Checkpoint**: 适配器正常路径成立（①绿）——US2/US4 的核对可开始

---

## Phase 4: User Story 2 - 发送失败不许装成功 (Priority: P1)

**Goal**: 4xx/5xx 与连接失败一律异常上抛、零静默；失败可被工具执行统一审计路径接管

**Independent Test**: ③（500 → `RestClientResponseException` 上抛）、④（缺 url → IAE 点名 + 零请求）核对通过

### Implementation for User Story 2

- [x] T007 [US2] 失败口径收口核对（代码走查 + 跑 ③④）：确认 `WebhookNotifyAdapter` 无自定义 `ResponseErrorHandler`、无 catch 吞错、无降级分支；确认 ③ 断言类型为 `RestClientResponseException`；**连接失败口径**（`ResourceAccessException` 同抛）为 RestClient 默认语义（research 附录 A-2 字节码证据），本批 harness 按课件判卷点不新增用例——此项由收尾报告以设计证据说明。跑 `mvn -q -pl sableos-tool -am test -Dtest=WebhookNotifyAdapterTest`（依赖 T005）

**Checkpoint**: 失败口径钉死——"发出去没送到"与"没发出去"同口径上抛

---

## Phase 5: User Story 4 - 换渠道零代码 (Priority: P3)

**Goal**: 同一实现对不同配置发往不同地址；接口签名不因新增渠道实现而改动

**Independent Test**: ②（同实现两配置 → 两目标）核对通过 + SC-005 接口中立性自查

### Implementation for User Story 4

- [x] T008 [US4] 换渠道闭环 + 中立性自查：跑 ② 核对；`grep` 核验实现内无硬编码地址/域名（地址只来自 `target.config()`）；SC-005 接口中立性自查（换企业微信官方 SDK 实现无需改 `send(NotifyTarget, String)` 签名）结论记录进收尾报告（依赖 T005）

**Checkpoint**: 全部本轮可验证 story（US1/US2/US4）闭环

---

## Phase 6: User Story 3 - notify 工具的渠道解析 (Priority: P2)【顺延·24 节后】

**Goal**: `NotifyTools`（渠道解析 + enforce 先于 send + 审计走既有路径）——本轮**登记不落码**（课件实现顺序说明；契约见 [contracts/notify-tool.md](./contracts/notify-tool.md)）

**Independent Test**（24 节后）: `NotifyToolsTest` 三断言（未配置报错 / 缺省取第一个 / `InOrder` enforce 先于 send）

- [ ] T009 [US3] 【顺延·24 节后】实现 `NotifyTools`（`SableTool` 形态、`com.sableos.tool.builtin` 包；渠道解析按契约：缺省/空白/`default` → 注册表第一个、未注册不回退、上下文缺失报错零请求；`sandbox.enforce(...)` 先于 `adapter.send(...)`；成败走 `ToolExecutor` 既有审计路径；`@Tool` 注册归 20 节 ToolRegistry 收编）于 `sableos-tool/src/main/java/com/sableos/tool/builtin/NotifyTools.java`
- [ ] T010 [US3] 【顺延·24 节后】写 `NotifyToolsTest`（第二批 harness：`missingChannelConfigFailsLoud`／`channelDefaultsToFirstRegistered`／`enforceIsCalledBeforeSend`（`InOrder`，课件示例 `notify("hello", "default")`）；mock `Sandbox` + `NotifyChannelAdapter`）于 `sableos-tool/src/test/java/com/sableos/tool/builtin/NotifyToolsTest.java`

**Checkpoint**: 契约已定死；实现与二批 harness 在 24 节后回收、27/28 节串联全量验证

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T011 全仓 `mvn spotless:apply`（Spotless 绑 validate，格式违规即挂）
- [x] T012 `mvn -q -B clean verify` 全绿（Spotless/Checkstyle/P3C-PMD/SpotBugs/FindSecBugs 全过）——**完成的定义**
- [x] T013 全局不变量自查（H4 + AGENTS.md §5.1/§5.2）：无 Sandbox 类型/无自造校验；`grep` 无明文 key（测试目标仅 `127.0.0.1` 假服务）；无 Reactor/`CompletableFuture`/自建线程池（RestClient 同步、HttpServer 不 `setExecutor`）；无 Spring AI 自动工具执行路径；无第二个 `TaskDecorator`；url/config 不进日志
- [x] T014 前序全节回归：`mvn -q -pl sableos-core,sableos-provider,sableos-storage,sableos-cli,sableos-channel-cli,sableos-boot test` 全绿（16/17/18 节测试不破）
- [x] T015 整理验收报告 + 变更总结导读（改动点/重点 review 清单/如何验证命令块/剩余人工项——接口中立性自查结论、第二批 harness 顺延说明）——对话内输出，不另开文件

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1)** → **Foundational (P2)**：T001（pom）先于一切编译；T002/T003 阻塞 US1 测试与实现。
- **US1 (P3 阶段)**: T004（测试）先写先红 → T005（实现）→ T006（跑绿）。T004/T005 依赖 T002/T003。
- **US2 (P4 阶段)**: T007 依赖 T005（走查 + 跑 ③④）。
- **US4 (P5 阶段)**: T008 依赖 T005。
- **US3 (P6 阶段)**: T009/T010 顺延——不参与本轮完成的定义；24 节后实现时依赖 20 节（`@Tool`/ToolRegistry）与 23/24 节（`Sandbox`）。
- **Polish (P7)**: T011–T015 依赖本轮全部（T001–T008）。

### 阶段顺序说明（偏离"严格优先级"的理由）

spec 优先级 US3=P2 > US4=P3；但 US3 **全量顺延**（课件明载完成时点在 24 节后），本轮零工作量，故置末登记；US1/US2/US4 共享同一实现面（一个适配器 + 一个测试类），按"正常路径 → 失败口径 → 换渠道闭环"顺序在 US1 阶段一次实现、随后两个阶段专项核对——各 story 的测试点（①②③④）仍可独立判卷。

### Parallel Opportunities

- T002/T003（不同文件，抽象两件）可并行。
- T004 内部的四条测试方法同文件一次写齐（单文件不并行）。
- T009/T010（顺延项）与本轮无并行关系。

---

## Parallel Example: Foundational

```bash
# 抽象两件（不同文件、无相互依赖）：
Task: "NotifyChannelAdapter.java in sableos-tool/src/main/java/com/sableos/tool/notify/"
Task: "NotifyTarget.java in sableos-tool/src/main/java/com/sableos/tool/notify/"
```

---

## Implementation Strategy

### MVP First

Setup（T001）→ Foundational（T002/T003）→ US1（T004 测试先红、T005 实现转绿、T006 跑绿）即得**可独立验证的 MVP**：本地假 webhook 收到恰好一次带 content 的 POST。随后 US2（失败口径收口）→ US4（换渠道闭环）→ Polish 收口。

### Incremental Delivery

1. T001–T006 绿 → 发送链路成立（①绿，MVP）。
2. +T007 → 失败口径钉死（③④绿核对）。
3. +T008 → 换渠道零代码闭环（②绿核对 + 中立性自查结论）。
4. +T011–T015 → `mvn clean verify` 全绿 + 不变量自查 + 前序回归 + 验收报告。
5. 24 节后回收 T009/T010（第二批 harness），27/28 节串联全量验证。

---

## Notes

- [P] = 不同文件、无未完成依赖。
- 反作弊：不删断言、不 `@Disabled`、不放宽阈值；实现错修实现，认为测试错停下报告。
- 【顺延】任务（T009/T010）不参与本轮完成的定义——收尾报告需明示其完成时点；未全绿不得宣称完成。
- 本节不自动 commit/push/运行 package.sh（同步时机由用户定）。
- 避免 Java 18+ 语法形态（增强 switch `default ->`、record/pattern switch）；PMD 按 17 级解析。
