# Contract: 12 个子命令（轻/重分类与最小行为）

> 主入口 `SableOsCli`（sableos-cli）挂 12 个 `@Command`；轻/重分类见 research D-07。
> 重命令在 `run()` 内 `new SpringApplicationBuilder(SableOsRuntime.class)...run()` 自启动上下文（见 cli-bootstrap.md）；轻命令零 Spring。
> 课件明确：命令分流与 `--help` 属进程级行为，**不进 harness**，留人工清单。

## 命令清单（核心阶段 12 个）

| # | 命令 | 轻/重 | 落位 | 最小行为（本节） | 边界 |
| --- | --- | --- | --- | --- | --- |
| 1 | `chat` | 重 | cli `ChatCommand` → channel-cli `CliChannel` | `--profile`(默认 default)；`run()` 内 `SpringApplicationBuilder(SableOsRuntime).web(NONE).bannerMode(OFF).run()`→`getBean(CliChannel).run(profile, currentUser())`；`currentUser()=System.getProperty("user.name","unknown")`（**无 `--user`**） | 全功能（配 16/17 节即成 Demo 一对话版） |
| 2 | `serve` | 重 | cli `ServeCommand` | 起 `SERVLET` 上下文后打印"Web Service 归 26 节"占位，再 `keepAlive()` 常驻至 Ctrl-C；不实现完整 REST | 完整 Web 归 26 节 |
| 3 | `gateway` | 重 | cli `GatewayCommand` | 同 serve：起 `SERVLET` 上下文后打印占位、`keepAlive()` 常驻；不挂真实多通道 | 多 IM 通道归后续渠道节 |
| 4 | `init` | 轻 | cli `InitCommand` | 在工作区建 `.sableos/` 骨架（agents/ 等目录 + 最小配置），已存在则提示不覆盖 | 不起 Spring |
| 5 | `status` | 轻 | cli `StatusCommand` | 报工作区根、DB 路径、`.sableos/agents/` 下 Agent 计数 | 不起 Spring |
| 6 | `profile list` | 轻 | cli `ProfileCommand` 子命令 | 列 `.sableos/agents/` 下 Agent（目录名） | 不起 Spring |
| 7 | `profile create` | 轻 | 同上 | 从最小模板建一个 Agent 目录（`AGENT.md` 骨架） | 不起 Spring |
| 8 | `profile show` | 轻 | 同上 | 打印指定 Agent 的 `AGENT.md` | 不起 Spring |
| 9 | `profile delete` | 轻 | 同上 | 删除指定 Agent 目录（删除前提示，遵循"删除需确认"约定） | 不起 Spring |
| 10 | `provider list` | 轻 | cli `ProviderListCommand` | 读 providers 配置（`application.yaml` 的 `sableos.providers` / 环境）列出名与 type；**不做类型扫描**（原则 IV） | 不起 Spring |
| 11 | `tool list` | 轻 | cli `ToolListCommand` | 列已注册工具；当前 `ToolCatalog` 为空 → 提示"工具注册归 20 节" | 不起 Spring |
| 12 | `session list` | 轻 | cli `SessionListCommand` | `LightDbConfig` 裸 JDBC 直读 `sessions`：`SELECT session_id, profile_name, status, last_active_at FROM sessions ORDER BY last_active_at DESC`；库未建则提示"暂无会话" | 不起 Spring；只读，不做查询接口 |

> `profile` 是一个带 4 个子命令（list/create/show/delete）的 `@Command` 组，计入"12 个子命令"口径时按课件 §8.7 的展开计（init/status/chat/serve/gateway/profile×4/provider list/tool list/session list = 12）。

## 通用约定

- 参数解析、帮助、报错全交 Picocli（`mixinStandardHelpOptions=true` 提供 `--help`/`--version`），不自撸 `args[]`（FR-003）。
- 用户可控字符串（`--profile`/`--user`/stdin 行）入日志前先 `replaceAll("[\\r\\n]", "_")` 净化（AGENTS.md §5.2）。
- 轻命令目标目录不存在（如未 `init` 就 `profile list`）→ 清晰提示，不静默返回空、不崩溃（spec Edge Cases）。
- 凭证走环境变量占位，命令不硬编码任何 key。
- 重命令 `serve`/`gateway` 起 `SERVLET` 容器、共用同一 `server.port`（默认 8080，可被 `SERVER_PORT` 环境变量覆盖），故两者同时运行会争端口；且因 `SableOsCli.main` 末尾是 `System.exit`，常驻必须靠 `run()` 内 `keepAlive()` 阻塞（见 cli-bootstrap.md）。

## chat 交互契约（CliChannel，对齐 oryxos）

```
channel-cli CliChannel(AgentService, SessionManager):
  run(profileName, userId):
    session = sessionManager.getOrCreate("cli", userId, profileName)
    loop: print "> "; line = readLine(stdin)
          if line == null(EOF) or trim(line) == "/quit": print "再见。"; return
          if line.isBlank(): continue
          reply = agentService.process(session, line)   # 引擎跑 ReAct，内部 save 落库
          print reply
```

- CLI 到 `process` 即撒手，不含 Agent 智能（FR-001）；同一次 chat 复用同一 `session` 对象（多轮串接，FR-005）；EOF（Ctrl-D/管道结束）等同退出、不抛堆栈。
- `ChatCommand.run()` 自启动 `SableOsRuntime` 上下文后 `getBean(CliChannel.class).run(profileName, currentUser())`（见 cli-bootstrap.md）。
- `CliChannelTest`（可选，channel-cli）：喂脚本化 stdin（含 `/quit`），断言每行交给 `AgentService` 一次、回复被打印、读到 `/quit`/EOF 即停；断言 `CliChannel` 自身不触发模型/工具（mock `AgentService`）。
- oryxos 成熟版另有 `/new` 清空历史、打字机流式、RBAC `PrincipalContext`——分属 26/19/后续节，**本节不做**。

## 轻命令 datasource（LightDbConfig，对齐 oryxos）

`session list` 与重命令读同一份配置指向的库：`LightDbConfig.load()` 解析 datasource url（缺省 `jdbc:sqlite:${workspace.root:.sableos}/sableos.db`，`${ENV:default}` 占位解析），SQLite 档库文件未建则提示"暂无会话"，否则 `DriverManager.getConnection` 裸 JDBC 只读查询。零 Spring、零新增编译依赖（sqlite 驱动运行期由 fat JAR 提供）。
