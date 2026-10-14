# Quickstart: ReAct 循环验证向导

**Feature**: 002-lesson17-react | 本文件只讲「怎么验」；结构口径见 [data-model.md](./data-model.md) 与 [contracts/](./contracts/)，实现细节见 tasks.md 与代码。

## 0. 前置

- JDK 21、Maven 3.9+（本机路径见开发环境约定）；仓库根 `D:\idea\sableos`。
- 无需任何 API key、无需任何工具实现即可完成场景 A–C（模型与工具均以桩/空实现驱动）。
- 首次改动后先 `mvn -q spotless:apply`（格式门禁绑 validate，不跑会挂构建）。

## 1. 场景 A：单测全绿（无需网络，默认必跑）

```bash
mvn -q -B test
```

期望：全绿。其中本节新增断言包含——

- `ReActLoopTest`：**关键回归**——模型每轮都要求调工具时恰好转满 `maxIterations` 次并返回固定终止文案（一轮不多不少）；无工具调用即返回该轮文本；每轮响应与工具结果都累积进会话；
- `PromptBuilderTest`：**关键回归**——历史超 N 条只留最近 N 条且 system prompt 仍在；system prompt 末尾含当前日期时间；无记忆来源时跳过记忆段；
- `ToolExecutorTest`：**关键回归**——工具成功写审计 `success=true`；工具抛异常写 `success=false` 带原因且不抛出；未注册工具名按失败处理；
- `AgentServiceTest`：**关键回归**——处理期间 `ProfileContext.current()` 可取到当前 Profile；处理抛异常后 `finally` 已清空；正常结束后 `SessionStore.save` 被调用一次；
- `ContextLoaderTest`：**关键回归**——改 AGENT.md/bootstrap 后下次组装立即读到新内容（无缓存）；Skill 软连接缺失报错点名；Bootstrap 缺失记 WARN。

## 2. 场景 B：审计表存取（临时 SQLite + 真 Flyway）

```bash
mvn -pl sableos-storage -am test -Dtest=ToolInvocationRepositoryTest
```

期望：`V2__tool_invocations.sql` 在空库上从零执行成功；成败两态能存能读；`PRAGMA table_info(tool_invocations)` 含 `success`/`error_message`/`trace_id` 等列；`flyway_schema_history` 有 version='1' 与 '2' 两行（表来自手工迁移而非 ddl-auto）。

## 3. 场景 C：零 key 装配可启动

```bash
mvn -pl sableos-boot -am test -Dtest=ReactAssemblyTest
```

期望：Spring 上下文升起；`ReActLoop`/`PromptBuilder`/`ToolExecutor`/`AgentService`/`ContextLoader`/`SessionStore`/`ToolInvocationAuditor` bean 齐备；空 `ToolCatalog` 生效；驱动器（模型桩 + 一次 `AgentService.process`）可跑通一轮并落一条 `tool_invocations`（失败态，因无注册工具）。

## 4. 场景 D：本地起服务（零 key）

```bash
mvn -q -DskipTests package
java -jar sableos-boot/target/sableos-boot-*.jar
```

期望：应用启动成功；`curl localhost:8080/actuator/health` 返回 `UP`；SQLite 文件 `.sableos/sableos.db` 生成且含 `llm_calls` + `tool_invocations` 两表（Flyway V1+V2 迁移成功）。

## 5. 场景 E：课件「做完怎么验」手工项对照

| 手工项（课件五） | 本节对应验证 |
| --- | --- |
| 五个测试类全绿 | 场景 A |
| 最大轮数兜底不无限循环 | 场景 A 的 `ReActLoopTest`（恰好 N 次 + 固定文案） |
| 每轮累积进会话、可续接 | 场景 A 的 `ReActLoopTest`（历史按序保留） |
| 工具成败两态都落审计 | 场景 A 的 `ToolExecutorTest` + 场景 B（真落库） |
| 线程级上下文可取值且异常也清 | 场景 A 的 `AgentServiceTest` |
| 上下文无缓存、缺失不静默 | 场景 A 的 `ContextLoaderTest` |
| key 不落明文 | `grep -rn "sk-" --include="*.java" --include="*.yaml" --include="*.sql" .` 零命中（本节不涉 key） |

> 本节仍属运行时内核，**无独立可演示入口**；「对话版 Demo」由 18 节 CLI 接入 `AgentService.process` 后成型（§IX）。

## 6. 场景 F：门禁全量（提交前）

```bash
mvn clean verify
```

期望：Spotless / Checkstyle / P3C(Pmd) / SpotBugs(+FindSecBugs) / 全部测试 全绿（OWASP depcheck 本地默认跳过，CI 强制跑）。这是「本节完成」的硬门禁定义。

## 7. 留证提示

- 场景 B 通过后可用任意 SQLite 工具查证审计行：`SELECT tool_name, success, error_message, trace_id, duration_ms FROM tool_invocations ORDER BY id DESC LIMIT 3;`——`trace_id` 在 CLI/定时入口（18/25 节）接入前可能为 null，属预期。
- 最终验收报告将附：`mvn clean verify` 输出、七个测试类清单、交付物存在性核对、H4 不变量自查与剩余人工项清单。
