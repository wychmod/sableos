# 契约：命令行（核心阶段 12 个子命令）

**入口**：`sableos`（Picocli）｜**命名**：以 `sableos-core` 起 Spring 上下文的命令与纯文件操作的命令分开，后者不启动 Spring 以保证启动速度

## 命令清单

### 工作区与状态

| 命令 | 作用 | 是否需要 Spring 上下文 |
| --- | --- | --- |
| `sableos init` | 初始化 `.sableos/` 工作区：目录结构、默认模板、示例 Agent、带注释的最小白名单 | 否 |
| `sableos status` | 打印工作区路径、已注册 Agent、provider 列表、SQLite 状态、最近调度结果 | 否 |

### 运行

| 命令 | 作用 | 说明 |
| --- | --- | --- |
| `sableos chat` | 交互式对话（读 stdin 写 stdout），维持当前会话；`/quit` 退出 | 需要 Spring；用户标识取本机用户名 |
| `sableos serve` | 启动 REST 服务（默认 8080），定时任务随其常驻调度 | 需要 Spring |
| `sableos gateway` | 守护进程模式，同时挂多个渠道（核心阶段等价于 `serve` + CLI 渠道预留） | 需要 Spring |

### 查询与管理

| 命令 | 作用 |
| --- | --- |
| `sableos profile list` | 列出已注册 Agent（名字、provider、工具数、是否有 schedules） |
| `sableos profile create <name>` | 生成一个 Agent 目录骨架（`AGENT.md` 模板） |
| `sableos profile show <name>` | 打印该 Agent 的派生运行配置（脱敏，不含密钥） |
| `sableos profile delete <name>` | 归档该 Agent 目录（移入 `.sableos/archive/`，不物理删除） |
| `sableos provider list` | 列出已配置的 provider name 与默认模型（不含密钥） |
| `sableos tool list` | 列出已注册工具及其来源（内置 / MCP / 注解） |
| `sableos session list` | 列出会话（id、profile、channel、user、最后活跃时间、状态） |

> 子命令数为 12：技术方案第 10 章为 13 个（025 起加 `agent import`），本功能不含 `agent import`（人格库/专家导入属后续功能）。命令行数的文档漂移见 `AGENTS.md` 第 10 章。

## 行为约定

| 编号 | 约定 |
| --- | --- |
| L-01 | 密钥只从环境变量或本地配置文件加载，`profile show` 与日志中一律不出现明文 |
| L-02 | `init` 必须幂等：已存在工作区时不覆盖用户文件，只补缺失项 |
| L-03 | 未注册 provider、未注册工具、缺失 Bootstrap 的 Agent：加载失败并打印可定位错误，但**不阻断**其他 Agent 与进程启动 |
| L-04 | `chat` 与 `POST /api/v1/agents/{name}/invoke` 走同一条处理链路，同一输入应得到结构一致的响应 |
| L-05 | 退出码：成功 0；参数错误 2；运行期失败 1 |

## 契约测试要点

| 编号 | 断言 |
| --- | --- |
| L-06 | 全新目录执行 `init` 后，`status` 能列出示例 Agent，且无需手工配置即可跑通一个涉外工具调用 |
| L-07 | `init` 重复执行不改变已有文件内容（幂等） |
| L-08 | 无 Spring 上下文的命令（`init` / `status`）在 1 秒内返回（不加载框架） |
| L-09 | `profile delete` 后目录进入 `.sableos/archive/`，且 `profile list` 不再列出 |
