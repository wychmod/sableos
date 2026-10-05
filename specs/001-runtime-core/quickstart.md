# 验收与验证指南：SableOS 运行时内核

**用途**：本功能的端到端验证手册。每个场景都给出前置条件、执行动作与**可观察的预期结果**，用来判断实现是否真的满足规格。实现细节见 `plan.md` 与后续的 `tasks.md`。

## 0. 前置条件

| 项 | 要求 |
| --- | --- |
| JDK | 21（构建与本机运行都必须是 21） |
| 构建 | `mvn clean package` 通过，产出可执行 fat JAR |
| 模型提供方 | 至少两个 provider 的凭证可用（如 DeepSeek 与 Kimi），经环境变量注入 |
| 通知渠道 | 一个可用的 webhook 地址（企业 IM 群机器人），按 `contracts/workspace-and-config.md` 第 6 节的格式登记在 `.sableos/notify_channels.yaml`（`init` 已生成带注释的模板） |
| 网络 | 能访问模型 API 与上一步的 webhook 域名，且该域名在工作区白名单内 |

## 1. 准备：全新工作区

```bash
java -jar sableos-boot/target/sableos-boot-*.jar init
java -jar sableos-boot/target/sableos-boot-*.jar status
```

**预期**：生成 `.sableos/` 完整目录结构与示例 Agent；`status` 列出该 Agent、至少一个 provider、SQLite 可用；生成的白名单带注释且**不需要手工编辑即可跑通场景 2**。重复执行 `init` 不改变已有文件。

## 2. 场景一：每日天气（必过）

**前置**：`.sableos/agents/daily-weather/AGENT.md`（光杆目录，无 scripts、无 Skill 绑定），frontmatter 声明 provider、`tools: [http_get, notify]`、一个近期会触发的 `schedules`。

**执行**：先手动补跑一次验证链路，再等 cron 到点触发自动运行。

```bash
java -jar sableos-boot/target/sableos-boot-*.jar chat     # 选该 Agent，发一句"查今天天气"
curl -X POST localhost:8080/api/v1/agents/daily-weather/invoke -H 'Content-Type: application/json' \
     -d '{"message":"查今天天气","user_id":"smoke"}'
```

### 观察点与预期（场景一）

| 观察点 | 预期结果 |
| --- | --- |
| 循环 | 至少两轮：第一轮调用 `http_get`，第二轮产出建议并调用 `notify` |
| 沙箱 | 两次涉外调用都通过域名白名单；把域名从白名单移除后**必须被拒绝**且原因可读 |
| 审计 | `tool_invocations` 有两条成功记录（`http_get`、`notify`），被拒绝时多一条 `success=false` 且 `error_message` 有原因 |
| 会话 | `GET /api/v1/sessions/{id}` 能查到完整对话；定时触发的会话 `channel` 与 `user` 均为 `scheduler` |
| 链路一致 | 手动补跑与自动触发走同一条链路，产生的会话结构一致 |
| 记忆 | 长期记忆为空时系统仍能正常运行（不因缺文件报错） |

## 3. 场景二：每日科技日报（必过，零代码）

**前置**：`.sableos/agents/daily-tech-digest/AGENT.md`，其 `skills/digest-format` 是指向 `.sableos/skills/digest-format/` 的**相对软连接**；`mcp_servers.yaml` 配好新闻 MCP server。

**执行**：先做一次会话让 Agent 记住偏好（"以后更关注 AI 和芯片"），再触发日报。

### 观察点与预期（场景二）

| 观察点 | 预期结果 |
| --- | --- |
| 记忆写入 | `MEMORY.md` 归档区出现该偏好；核心区不受影响 |
| 记忆生效 | 新会话/新触发中日报内容体现该偏好，无需用户重复交代 |
| 渐进式披露 | prompt 中只有 `digest-format` 的 name/description/路径；正文是模型调 `read_file` 后才进入上下文 |
| 工具来源 | `tool_invocations` 中出现对 Agent 本地软连接路径的 `read_file`，以及 MCP 工具调用（来源标记 `mcp`） |
| 绑定边界 | 未绑定的 Skill 不出现在 prompt 中，也不可被读到 |
| 零代码 | 业务方全程未写任何 Java 代码 |

## 4. 失败路径验证

| 编号 | 触发方式 | 预期 |
| --- | --- | --- |
| V-F1 | 构造一个在迭代上限内无法收敛的任务 | 循环在上限处终止，`terminated_reason=max_iterations`，返回最后一条模型输出 + 终止原因；会话历史保留全部中间步骤；`llm_calls` 记录全部轮次 |
| V-F2 | 让一次 `http_get` 返回远超阈值的内容 | 结果被截断且带「已截断 + 原始长度」标注；循环继续；`tool_invocations` 记成功 |
| V-F3 | 请求白名单外的路径、域名、命令各一次 | 三次都被拒绝；每次调用都留下 `success=false` 的审计记录；进程不受影响 |
| V-F4 | 使用一个未在配置中注册的 provider name 启动 | 该 Agent 加载失败并打印可定位错误；其他 Agent 与进程正常 |
| V-F5 | 让 `MEMORY.md` 被外部删除，然后继续对话；再把它写坏（非法结构）后重试 | 删除 = 按空记忆继续；写坏 = 按纯文本整体视作归档区、核心区视为空，并在日志留明确告警；两种情况下对话都不中断 |
| V-F6 | 提供方返回认证失败 | 返回 503 或明确的失败信息；不静默重试、不静默切换到别的 provider |
| V-F7 | 对同一会话并发发起两次调用 | 两次串行执行（等待上限为单次调用超时）；会话历史无交叉写入、无丢失；不返回"拒绝"类错误 |
| V-F8 | 让模型返回无法解析的工具调用输出；再让一次工具调用超时或抛异常 | 以带"是否可重试"的失败结果回填并继续循环；失败调用同样落库；最终由迭代上限兜底终止 |

## 5. 工程化验证

| 编号 | 验证 | 预期 |
| --- | --- | --- |
| V-E1 | 会话跨重启 | 重启进程后 `GET /api/v1/sessions/{id}` 仍返回完整历史 |
| V-E2 | 多 Agent 并存 | 两个不同 provider 的 Agent 同时可用，配置与会话互不串扰 |
| V-E3 | 定时触发 | 不人工干预，到点自动运行；同一任务上次未结束时不重叠触发（跳过而非排队） |
| V-E4 | 手动补跑 | 用 `POST /api/v1/agents/{name}/invoke` 补跑成功，且可在会话记录中看到 |
| V-E5 | 密钥不外泄 | 日志、`GET /api/v1/info`、`profile show` 中均无明文密钥 |
| V-E6 | 可运维性 | 新用户在 30 分钟内完成部署并跑通场景一（SC-003）。计时口径：从拿到 fat JAR 开始，到场景一返回预期结果为止；**不含**申请模型密钥与准备 webhook 地址的时间 |
| V-E7 | CLI 覆盖 | 12 个子命令均可执行，`--help` 与错误提示清晰 |
| V-E8 | 记忆后端切换 | 把 `memory.backend` 从 `markdown` 改成 `sqlite`（或反向）后重启，同一段记忆可读、可写、可检索，且上层提示词组装与记忆工具的行为无差异（FR-042） |

## 6. 场景三：每日 GitHub 日报（必过）

**前置**：`.sableos/agents/github-daily/`（`AGENT.md` + `scripts/github_trending.py`），frontmatter 声明 `tools: [github_daily, notify]` 与每天 09:30 的 `schedules`；`github_daily` 由专用 Tool 或 MCP 封装（**不交给通用 `shell`**）。

**执行**：手动补跑一次，再等 cron 到点触发。

### 观察点与预期（场景三）

| 观察点 | 预期结果 |
| --- | --- |
| 脚本边界 | `tool_invocations` 中有 `github_daily` 调用；脚本产出的 JSON 进入上下文，**脚本代码不进** |
| 三段结构 | 日报按"今日 / 本月 / AI 重点"三段组织，AI 段体现长期记忆中的偏好 |
| 即时生效 | 改一次 `AGENT.md` 正文后无需重启，下一次触发即按新正文执行 |
| 沙箱 | 脚本访问的网络域名必须在白名单内；越界即被拒绝并留审计 |

## 7. 性能与稳定性（对应 SC-004 ~ SC-006）

| 编号 | 方法 | 预期 |
| --- | --- | --- |
| V-P1 | 单节点同时挂 10 个 Agent 连续运行 4 小时 | 无中断、无串扰 |
| V-P2 | 100 并发会话压测 | 无错误；会话创建 P99 < 200ms |
| V-P3 | 压测时观察入口到内部的转发开销 | < 50ms |
