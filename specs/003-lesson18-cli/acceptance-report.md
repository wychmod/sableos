# 第 18 节验收报告：CLI 与会话持久化

**Feature**: `003-lesson18-cli` | **分支**: `003-lesson18-cli` | **日期**: 2026-10-15 | **结论**: harness 已判卷全绿，`mvn verify` 全绿，运行时冒烟通过

## 六项证据 DoD

### 1. `mvn clean verify` 全绿（含四道静态门禁）

```
sableos-core ....... SUCCESS   sableos-provider ... SUCCESS   sableos-memory ..... SUCCESS
sableos-tool ....... SUCCESS   sableos-channel-cli  SUCCESS   sableos-web ........ SUCCESS
sableos-storage .... SUCCESS   sableos-cli ........ SUCCESS   sableos-boot ....... SUCCESS
BUILD SUCCESS
```

- Spotless（google-java-format）：过；Checkstyle：**0 violations**；P3C-PMD：过（曾拦下 `ProfileCommand` 4 处魔法值，已抽常量修复）；SpotBugs + Find Security Bugs：过。
- OWASP Dependency-Check 按本地默认 `-Dowasp.skip=true` 跳过（权威门禁在 CI 独立 job，AGENTS.md §5.3）。

### 2. 课件 harness 映射：两个测试类存在、非空、关键回归对号

| 课件测试类 | 落地 | 关键回归点（课件原文进 `@DisplayName`） |
| --- | --- | --- |
| `SessionManagerTest` | `sableos-storage/src/test/.../SessionManagerTest.java`，4 个测试 | `@DisplayName("同一三元组_历次getOrCreate都是同一个Session")` **原样落地**（`getOrCreate("cli","wang","default")` 两次 `assertEquals(first.id(),second.id())`）；隔离（channel/user/profile 任一不同）；id 公式 `channel:user:profile` |
| `SessionRepositoryTest` | `sableos-storage/src/test/.../SessionRepositoryTest.java`，3 个测试 | PRAGMA 9 列齐 + 迁移历史含 V1/V2/V3；`messages_json` 回读各角色字段完整（USER/ASSISTANT带toolCalls/TOOL带toolName+success）；模拟重启（全新 `JpaSessionManager` 重查）历史仍在 |

测试方法名一律英文驼峰，课件中文原文进 `@DisplayName`（符合 skill 写中门禁）。

### 3. 本节交付物逐项存在性核对（齐全）

- 代码：`SableOsCli`（主入口，纯 Picocli）✓、12 个 `@Command` 子命令（chat/serve/gateway/init/status/profile×4/provider list/tool list/session list）✓、`CliChannel`（channel-cli）✓、`SessionEntity`+`SessionRepository`（storage）✓、`SessionManager` 契约（core）+`JpaSessionManager`（storage）✓。
- 测试：`SessionManagerTest`、`SessionRepositoryTest` ✓（另加非 harness 的 `CliChannelTest` 2 例）。
- 表：`sessions` 手工双轨脚本 `db/migration/{sqlite,postgresql}/V3__sessions.sql` ✓；`session_id` 由 `SessionManager.sessionId(channel,user,profile)` 唯一拼接 ✓。
- 约定：轻命令不启动 Spring ✓；重命令启动类 `SableOsRuntime` 显式声明 `@EnableJpaRepositories`/`@EntityScan("com.sableos.storage")` ✓；**三个重命令（chat/serve/gateway）都在 `run()` 内自启动 `SableOsRuntime`** ✓（chat `NONE`+try-with-resources；serve/gateway `SERVLET`+`keepAlive()` 常驻，见 §7）。
- `InMemorySessionStore` 已删除 ✓（被 `JpaSessionManager` 取代）。

### 4. 前序节全部测试回归绿（跨节契约证据）

`mvn verify` 全量：**core 91 · provider 21 · channel-cli 2 · web 33 · storage 13 · boot 12**，全绿。含 16 节 `ProviderAssemblyTest`/`ProviderServiceTest`、17 节 `ReactAssemblyTest`/`ReActLoopTest`/`AgentServiceTest`/`ToolInvocationRepositoryTest`/`LlmCallRepositoryTest` 均未破。

### 5. H4 全局不变量 + AGENTS.md §5.1/5.2 逐条自查

| 不变量 | 结论 | 证据 |
| --- | --- | --- |
| ① 涉外 IO 首行过 Sandbox | N/A（本节） | CLI 无工具涉外动作；`init`/`profile` 只写工作区骨架、`session list` 只读本库，均非 Tool 涉外 IO；Sandbox 归 24 节 |
| ② LLM 落 `llm_calls`、工具落 `tool_invocations` | ✅ | chat 经 `AgentService.process`→`ReActLoop` 复用 16/17 节审计路径，未新增旁路；`ReactAssemblyTest` 仍验证一条工具审计落库 |
| ③ grep 无明文 key | ✅ | `grep -niE "sk-…word *=" "` 新代码零命中 |
| ④ `session_id` 只在 core 单点拼接 | ✅ | `grep '":"'` 仅 `SessionManager.sessionId` 一处（`channel+":"+userId+":"+profileName`）；调用方一律 `SessionManager.sessionId(...)`；运行期实证 id=`cli:Administrator:demo` |
| ⑤ 无 Reactor/`CompletableFuture`/自建线程池 | ✅ | grep 新代码零命中；重命令用 `SpringApplicationBuilder`，无手工线程 |
| ⑥ 无 Spring AI 自动工具执行路径 | ✅ | 本节不引 Spring AI 类型；chat 走自实现 `ReActLoop`+`ToolExecutor` |
| 全容器只一个 `TaskDecorator` | ✅ | 仅 `ObservabilityConfig.traceTaskDecorator()`；`ReactConfiguration`/`TracedExecutors` 的提及均为注释 |
| traceId 用 `TraceContext`/`TraceIds` 非 `UUID.randomUUID()` | ✅ | 本节未新增 traceId 生成；`CliChannel` 用 `TraceContext.CHANNEL_CLI` 常量 |
| 用户可控字符串入日志前 CRLF 净化 | ✅ | 新代码无 logger 记录用户输入（`CliChannel` 写 stdout 非日志）；`ProfileCommand` 名字经 `requireSafeName` 校验 |
| `synchronized` 内不做阻塞调用 | ✅ | 新代码无 `synchronized` |

### 6. 运行时冒烟（fat JAR，`java -jar` 实证）

- `--help`：`Start-Class=com.sableos.cli.SableOsCli`，列出全部命令树。
- 轻命令秒回不起 Spring：`init` 0.45s（含 JVM 启动）建 `.sableos/agents/`；`status`/`profile create+list`/`session list` 均无 Spring banner。
- 重命令自启动：`chat` 启动 `SableOsRuntime` → 日志 **"Found 3 JPA repository interfaces"（N>0，含 SessionRepository）** → Flyway **Successfully applied 3 migrations … now at version v3**（含 `V3 - sessions`）→ EOF 干净退出。
- `serve` / `gateway` 自启动 + 常驻（2026-10-15 修 §7 后复测）：`serve` 与 `gateway` 均启动 `SableOsRuntime` → 同样 **"Found 3 JPA repository interfaces"** → **"Tomcat started on port(s): 8080 (http)"** → 打印占位文案 → **进程保持运行**（`timeout 30` 打满 30s 才被杀，退出码 124；未修前实测约 50ms 即 `GracefulShutdown`）。`gateway` 常驻期间 `curl http://127.0.0.1:8080/actuator/health` 返回 **`{"status":"UP",...}` / HTTP 200**，证明 SERVLET 容器真在服务。
- 跨命令持久化闭环：`chat` 建的会话被 `session list`（轻命令裸 JDBC）读回 —— `cli:Administrator:demo  demo  active`，证明三运行模式共享同一 Session 存储、`session_id` 公式正确、跨上下文持久化。

### 7. 验收后复核修正（2026-10-15）：serve/gateway 补齐"起上下文 + 常驻"

- **问题**：首次验收时 `ServeCommand`/`GatewayCommand` 的 `run()` 仅 `System.out.println` 占位即返回，**未起任何 Spring 上下文**——偏离 T017（明确要求 `web(SERVLET).run()` 起上下文后打印占位）、FR-002（"启动服务/守护进程"属重命令须起上下文）、cli-bootstrap.md:50（"gateway 同 serve 起上下文后占位"）与 cli-commands.md 表格第 2 行。当时把这条写进了"剩余人工项"却未标注为对 T017 的偏离，属证据链缺口。
- **修正**：两个命令改为 `new SpringApplicationBuilder(SableOsRuntime.class).web(WebApplicationType.SERVLET).run()` → 打印占位 → `keepAlive()`。
- **修正中发现的关键点**：仅照 T017 写"不套 try-with-resources"**不足以常驻**——`SableOsCli.main` 末尾是 `System.exit(execute(args))`，`run()` 一返回整机即退（实测打印后约 50ms 进 `GracefulShutdown`）。故补齐 `keepAlive()`（`new CountDownLatch(1).await()`，对齐 oryxos `ServeCommand.keepAlive()`），Ctrl-C 由 Spring 关闭钩子优雅收场。
- **影响面**：FR-002 对 serve/gateway 落实；SC-005（重命令上下文 N>0 仓储）与 SC-007（三模式共享同一 Profile 与会话存储）对这两个命令可演示。harness（`SessionManagerTest`/`SessionRepositoryTest`）与既有测试不受影响，无新增依赖。
- **运维注意**：两者共用 `server.port`（默认 8080），同时运行会争端口；端口可被 `SERVER_PORT` 环境变量覆盖。**本机 IDE 环境注入了 `SERVER__PORT=13470`（WorkBuddy 自身占用）**，在此 shell 里直接跑 `serve` 会因端口冲突启动失败——验端口时用 `env -u SERVER__PORT` 或显式指定端口。与 oryxos 的差异：oryxos `serve` 有 `--port` 选项，本节按契约未加。
- 同步更新：`contracts/cli-commands.md`、`contracts/cli-bootstrap.md`、`docs/study/第18节-CLI代码走读.md`（§1/§2/§3 调用链/§5）。

## 剩余人工项（课件"五、做完怎么验"，harness 判不了、等你人工过）

- [ ] **真模型多轮对话**：配好 provider（`sableos.providers` + 环境变量 key）后 `sableos chat --profile <name>`，完成一次真实多轮对话、`/quit` 正常退出；Demo 一对话版从头走通。（冒烟用的是 EOF 立即退出 + 未注册 provider，未跑真实模型推理）
- [ ] **轻/重启动差异主观确认**：轻命令"秒回"、重命令才起 Spring 的体感（已用计时 + banner 客观佐证，人工再确认一遍）。
- [ ] **`serve`/`gateway` 的完整行为**：起上下文与常驻已在 §7 修正并冒烟（`serve` 常驻、`gateway` `/actuator/health` 200）；仍需人工确认的是**完整 Web Service（REST 端点）归 26 节、多通道挂载归后续渠道节**——手动跑一次 `sableos serve`（记得避开 `SERVER__PORT`）看到 Tomcat 8080 + 占位 + Ctrl-C 干净退出即可。
- [ ] **12 个子命令 `--help` 逐个走查**：Picocli 自带，进程级行为未进 harness。
- [ ] **CI 的 OWASP Dependency-Check**：本地跳过，以 CI 独立 job 为权威（AGENTS.md §5.3）。

## 软门禁落实复述（已按用户"参照 oryxos"指示定夺）

- **SG#4**：core `Session` 追加 `rehydrate(...)`（追加式，既有 API/L17 测试不变）；删除 `InMemorySessionStore`；改 `ReactConfiguration`（`sessionStore()`→`sessionManager()`）与 `ReactAssemblyTest` 断言（→`JpaSessionManager`）；`SableOsApplication` 剥 JPA 扫描注解降为 vestigial。oryxos 全仓无 `InMemorySessionStore`、`AgentService`/`CliChannel` 依赖 `SessionManager` —— 佐证方向一致。
- **SG#1**：cli 升为聚合入口（+= storage/channel-cli/data-jpa/sqlite/snakeyaml，均 BOM 内既有构件，零新第三方）；新增 `SableOsRuntime` 装配根；fat JAR `mainClass` 改指 `SableOsCli`（boot 仍产 fat JAR，AGENTS.md 口径不变）。
- **对 oryxos 的有意微 divergence**（research D-10）：① `SessionManager extends SessionStore` 且 `AgentService` 签名不变（oryxos 改 AgentService 依赖 SessionManager，属其后续节演进）；② `session_id` 公式放 core 接口静态方法（oryxos 放 storage 私有方法）以严丝合缝满足 sableos H4④"core 侧单点"；③ `save()` 做 upsert（oryxos 瘦 Session 无法补全整行故抛异常；sableos 富 Session 可 upsert，更健壮且 L17 直接构造 Session 的调用方不破）。
