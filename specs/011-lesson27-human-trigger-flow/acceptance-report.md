# 验收报告：第 27 节 全流程串联（一）——打通 Agent 人推主流程

**Feature**: `specs/011-lesson27-human-trigger-flow` | **Branch**: `027-lesson27-human-trigger-flow` | **Date**: 2026-10-31

> 范围说明：本节是集成课，不新增模块、不引入新概念。交付物是"一句真实的问话走通想 → 查 → 答 → 记账"这件事本身，以及把对账固化下来的三层 harness。定时链路与 Demo 准备归第 28 节。

## 1. 全量门禁

命令：`mvn -B clean verify` → `BUILD SUCCESS`

| 模块 | 单测结果 |
| --- | --- |
| sableos-core | 104 run, 0 fail, 0 err, 0 skip |
| sableos-provider | 30 run, 0 fail, 0 err, 0 skip |
| sableos-tool | 152 run, 0 fail, 0 err, 0 skip |
| sableos-storage | 41 run, 0 fail, 0 err, 0 skip |
| sableos-memory | 35 run, 0 fail, 0 err, 0 skip |
| sableos-channel-cli | 2 run, 0 fail, 0 err, 0 skip |
| sableos-web | 76 run, 0 fail, 0 err, 0 skip |
| sableos-cli | 1 run, 0 fail, 0 err, 0 skip |
| sableos-boot | 40 run, 0 fail, 0 err, 0 skip |

合计 **481** 个测试全绿；Spotless / Checkstyle / P3C(PMD) / SpotBugs(FindSecBugs) 全部通过，OWASP 按本地默认跳过（CI 独立 job 为权威）。

> 计数口径：按 `target/surefire-reports/TEST-*.xml` 的 `tests` 属性汇总（`.txt` 会把 `@Nested` 父类记成 0）。

本次新增/扩展的测试类：

| 类 | 模块 | 覆盖 |
| --- | --- | --- |
| `MockChatModelTest`（4 例） | provider | mock 脚本行为：第一轮发工具调用、第二轮收尾、空白兜底、两种"记住"前缀 |
| `MockProviderFlowTest`（1 例） | boot | 手工装配全链路 + `/sessions` `/memory` `/tools` 三端点对账 |
| `MockAgentE2ETest`（1 例） | boot | 真端口 + 临时 SQLite 整机 |
| `HumanTriggerFlowIT`（6 例，`@Tag("integration")`） | boot | 三支柱 + 三类失败记账 + 三面同源 |
| `SessionManagerTest`（+1 例） | storage | 状态过滤不被未命中行挤掉 |
| `SessionApiControllerTest`（+2 例） | web | status 下推、空白 status 视为不过滤 |

## 2. jar 级实测（不是看代码推断）

用一个临时工作区（`-Dsableos.root`）起真实 fat JAR，provider 用环境变量登记为 `mock`：

| 断言 | 实测结果 |
| --- | --- |
| 建会话 | `data.id = web:anonymous:mock-agent` |
| 发"记住…"消息 | 答复 `（mock）已记住你提供的信息。`，信封 `code=0` |
| 会话历史 | `[(USER), (ASSISTANT), (TOOL, save_memory, success=true), (ASSISTANT)]` — 恰 4 条 |
| `GET /sessions` | 该会话在列表中，`messageCount=4`，**无** `messages` 字段 |
| `GET /sessions?status=archived` | `data: []` |
| `GET /memory?profile=mock-agent` | `- [2026-10-31] 我喜欢喝美式咖啡` |
| `sessions` 表 | 1 条（`web:anonymous:mock-agent`, active） |
| `llm_calls` 表 | 2 条，`sum(success)=2` |
| `tool_invocations` 表 | 1 条 `save_memory`，`success=1` |
| `MEMORY.md` | 真写入到 `<workspace>/memory/mock-agent/MEMORY.md` |
| 启动日志 | `Found 6 JPA repository interfaces`（N > 0） |
| `/admin` | 302 → `/admin/` 200；`/admin/sessions` 200 |
| 管理台会话页（浏览器 DOM） | 1 条：`web:anonymous:mock-agent` / `mock-agent` · 4 条 · 2026/10/31 15:23:46 |
| `/info`（另起一轮验证） | `mock` provider `reachable: true, latencyMs: 0`，未回落 DashScope 探测 |

## 3. 无 key 与真 key 两条路径

- **无 key**：`MockProviderFlowTest` + `MockAgentE2ETest` 随 `mvn verify` 自动执行并通过。
- **真 key**：`HumanTriggerFlowIT` 打 `@Tag("integration")`，无 key 时以 `assumeTrue` 跳过（实测 6 例中 2 例 skipped），命令 `DEEPSEEK_API_KEY=xxx mvn -pl sableos-boot -am test -DexcludedGroups= -Dgroups=integration -Dtest=HumanTriggerFlowIT` 可用（本次实测 `-DexcludedGroups=` 覆盖生效，`BUILD SUCCESS`）。

## 4. 课件「本节交付物」存在性核对

| 交付物 | 证据 |
| --- | --- |
| `SessionManager.listRecent(int)` / `(int, status)` | `sableos-core/src/main/java/com/sableos/core/session/SessionManager.java` |
| `JpaSessionManager` 实现（SQL 侧过滤） | `sableos-storage/.../JpaSessionManager.java` + `SessionRepository#findByStatus` |
| `GET /api/v1/sessions` + 摘要 DTO | `sableos-web/.../SessionApiController.java` + `dto/SessionSummaryView.java` |
| `MockChatModel` + 工厂接线 + 校验放行 | `sableos-provider/.../MockChatModel.java`、`ProviderChatModelFactory`、`ProvidersProperties`、`HttpProviderHealthProbe` |
| `sableos.root` 工作区覆盖 | `application.yaml` + 三个 boot 配置类 + `Workspace.root()` |
| `HumanTriggerFlowIT` | `sableos-boot/src/test/java/com/sableos/channel/cli/HumanTriggerFlowIT.java` |
| `MockProviderFlowTest` | `sableos-boot/src/test/java/com/sableos/web/api/MockProviderFlowTest.java` |
| `MockAgentE2ETest` | `sableos-boot/src/test/java/com/sableos/boot/MockAgentE2ETest.java` |
| `MockChatModelTest` | `sableos-provider/src/test/java/com/sableos/provider/MockChatModelTest.java` |
| 管理台"会话"页接真实列表 | `SessionsPage.vue` 走 `listSessions()`，产物已重建 |
| README 端点表 + 管理台构建链 | README「REST API」「构建管理台与启动」两节 |

## 5. 复核中发现并修掉的问题

复核这一节时发现以下问题，均已修复且未放宽任何断言：

1. **会话列表的 status 过滤在内存里做**：初版是 `listRecent(100)` 之后再 `filter`，归档一多 `?status=archived` 会返回不足 100 条。改为 `findByStatus` + `PageRequest` 在 SQL 侧完成，并补了"不被未命中行挤掉"的回归断言（`SessionManagerTest#listRecent_filtersByStatusBeforeLimiting`）。
2. **README 缺管理台构建链**：课件要求"这条构建链要写进 README"，但 README 只写了 mock 自测。已新增「构建管理台与启动」一节，并显式提醒"`mvn package` 不重建前端、`static/admin/**` 是入库产物"。
3. **端点口径只改了一半**：AGENTS.md 声称"已同步"，实际 README 能力表、TechnicalSolution 第 13 章与五大能力清单、frontend README 仍是 10。已全部改为 11，并把 AGENTS.md 的措辞改成陈述实际同步范围 + 补上构建链要点。
4. **frontend README 有过时表述**：仍写"不包含会话列表"、端点表缺 `GET /sessions`。已修正。
5. **课件与本仓库布局不符**：`serve --port 8080`（实测报 `Unknown options`）、`bin/start.sh`、`config/application.yml`、`.sableos/profiles/`、`config/application.yml.example` 在仓库里都不存在；Bootstrap 缺失口径与 17 节契约（WARN）冲突。已就地改课件为本仓库事实，并把 Bootstrap 处理写成两档（正文/Bootstrap → WARN，Skill 软连接断裂 → 报错点名）。
6. **`HumanTriggerFlowIT` 断言偏弱**：支柱一只断言了 USER/TOOL，没断言"两次模型回复"；三面同源只用 mock 未覆盖"REST 侧入列"。已补 `messages[1]`/`messages[3]` 角色与非空正文断言，并新增"对外工具清单覆盖每个 Profile 工具集"用例。

## 6. 遗留（不在本节范围）

- **管理台渲染属人工验收项**：门禁只覆盖后端与接口契约；本次已用真实浏览器 DOM 抽查，但 CI 无此覆盖。
- **specs/010 与 docs/study 的历史口径**：`specs/010-lesson26-web-service/**` 与 `docs/study/第26节-WebService代码走读.md` 仍写"10 个端点"——那是 26 节当时的交付事实，未回改（docs/study 本身也在 .gitignore 中）。
- **本节未提交**：实现与文档改动仍在工作区，按项目节奏需单节提交。
