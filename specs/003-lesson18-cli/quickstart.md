# Quickstart: 第 18 节验收向导（CLI 与会话持久化）

> 运行/验证指南。实现细节见 [plan.md](./plan.md)、[data-model.md](./data-model.md)、[contracts/](./contracts/)。
> 完成的定义：`mvn clean verify` 全绿 + 下列 harness 判卷 + 人工项过。

## 前置

- JDK 21、Maven 3.9+（enforcer 钉死）；本地仓库 `D:\maven\Repository`。
- 零 key 可跑 harness（会话层不依赖真模型）；`chat` 真对话需 16 节 provider 配置 + 环境变量 key（人工项）。

## 自动化 harness（判卷，mvn test 全绿即通过）

两个测试类均在 **sableos-storage**，`@SpringBootTest`（真 Flyway + 临时 SQLite，零网络）：

| 测试类 | 覆盖 | 关键回归点（课件原文进 `@DisplayName`，方法名英文） |
| --- | --- | --- |
| `SessionManagerTest` | FR-006/007/008 | ①`getOrCreate_sameTriple_returnsSameSession`（同一三元组历次都是同一个 Session=幂等）②`getOrCreate_differentChannel_returnsDifferentSession`（channel 不同即不同会话；user/profile 任一不同同理）③`sessionId_isFormula_andSinglePoint`（id == `channel:user:profile`） |
| `SessionRepositoryTest` | FR-009/010/011 | ④`sessionsTable_hasRequiredColumns_viaPragma` + 迁移历史含 V1/V2/V3 ⑤`messagesJson_roundTrips_allFields`（带 toolCalls 的 ASSISTANT、带 toolName/success 的 TOOL、USER 空 toolCalls 逐字段还原）⑥`restart_newContext_historyPersists`（新建上下文重查历史仍在） |

运行：

```bash
# 只跑本节 harness
mvn -q -pl sableos-storage -am test -Dtest=SessionManagerTest,SessionRepositoryTest

# 前序回归（16/17 节，跨节契约证据）
mvn -q -pl sableos-core,sableos-storage,sableos-boot test

# 全量门禁（完成的定义）
mvn -q -B clean verify
```

预期：全绿；`SessionRepositoryTest` 的 PRAGMA 断言 9 列齐、`flyway_schema_history` 含 version 1/2/3。

## 端到端验证（重命令，需真模型——人工）

```bash
# 1. 构建 fat JAR（boot 产出，主类 = com.sableos.cli.SableOsCli）
mvn -q -B clean package -DskipTests

# 2. 初始化工作区（轻命令，秒回、无 Spring banner）
java -jar sableos-boot/target/sableos-boot-*.jar init
java -jar sableos-boot/target/sableos-boot-*.jar profile list      # 秒回

# 3. 交互对话（重命令，起 Spring；需先在配置/环境备好 provider + key）
java -jar sableos-boot/target/sableos-boot-*.jar chat --profile default
# > 你好
# <Agent 回复>
# > /quit          # 退出
```

## 人工确认清单（课件"五、做完怎么验"）

- [ ] `sableos chat` 能进入交互、完成一次多轮对话、`/quit` 正常退出；Demo 一对话版从头走通。
- [ ] 轻命令（`init`/`profile list`）秒回、日志无 Spring banner；重命令（`chat`/`serve`）才启动 Spring。
- [ ] `chat` 启动日志 "Found N JPA repository interfaces" 的 **N > 0**（含 SessionRepository）。
- [ ] 三种运行模式共享同一份 Profile 与 Session 存储，切换模式数据不丢（chat 存的会话，重启后 `session list` 仍可见）。
- [ ] 12 个子命令都能跑、`--help` 正常（Picocli 自带）。
- [ ] 会话幂等/隔离/持久化——已由 harness 覆盖，`mvn test` 绿即打勾。

## 全局不变量自查（收尾 H4，详见 acceptance 阶段）

- ②每次 chat 处理：LLM 调用落 `llm_calls`、工具执行落 `tool_invocations`（经 `AgentService.process` 复用 16/17 节路径），且会话落 `sessions`——三表 `session_id` 同源、`trace_id` 同口径。
- ③`grep` 无明文 key（凭证走环境变量占位）。
- ④`session_id` 只在 `SessionManager.sessionId(...)` 一处拼接（`grep -rn '":"' --include=*.java` 无第二处按 channel:user:profile 形态拼接）。
- ⑤无 Reactor/`CompletableFuture`/自建线程池；⑥无 Spring AI 自动工具执行路径。
