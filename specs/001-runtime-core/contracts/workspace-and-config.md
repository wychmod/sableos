# 契约：工作区与配置文件

**性质**：这些是**用户可直接编辑**的对外形态（不是内部实现细节）。改动它们不需要改代码，这是"业务方不写 Java 也能定义 Agent"的基础。

## 1. 工作区根

由 `sableos init` 产出，路径固定为工作目录下的 `.sableos/`（可由配置覆盖根路径）。完整布局见 `data-model.md` 第 3.1 节。

## 2. `AGENT.md`（一个目录 = 一个 Agent）

`.sableos/agents/<name>/AGENT.md`。frontmatter 是运行配置，正文是任务指令。

```markdown
---
name: daily-weather
description: 每天早上查天气并生成穿搭建议推送到群
identity:
  agent_name: 天气小助手
  prompt: 你是一个细心的生活助理
provider:
  name: deepseek
  model: deepseek-chat
  temperature: 0.3
tools:
  - http_get
  - notify
mcp_servers: []
channels:
  - scheduler
schedules:
  - key: daily-8am
    cron: "0 0 8 * * *"
    zone: Asia/Shanghai
    message: 查一下今天的天气，给出一句话穿搭建议
bootstrap:
  - AGENTS.md
  - USER.md
settings:
  max_iterations: 10
  max_history_turns: 20
---

每天早上先用 http_get 查天气，再用 notify 推送到 team-lark 渠道，正文里只用一句话给建议。
```

### AGENT.md 约束

| 编号 | 约束 |
| --- | --- |
| W-01 | `provider.name` 必须在 provider 注册表中存在；`tools` 中的名字必须在 `ToolRegistry` 中已注册；`channels` 必须是受支持渠道；`bootstrap` 引用的文件必须存在。违反者该 Agent 加载失败并打印可定位错误，不阻断进程 |
| W-02 | **不出现** `notify_channels` 字段；通知渠道在正文中按名引用全局注册表 |
| W-03 | Skill 绑定**不写 frontmatter**：在 `<agent>/skills/<skill-name>` 建指向 `.sableos/skills/<skill-name>/` 的**相对软连接**，软连接集合是唯一绑定真相源 |
| W-04 | 目录内 `scripts/` 与 `REFERENCE.md` 不预载，按正文指引经 `read_file` / `shell` 取用 |
| W-05 | 核心阶段改 cron 需重启或触发重新加载（API 增删改 cron 定义属后续功能） |

## 3. `SKILL.md`（公共 Skill）

`.sableos/skills/<name>/SKILL.md`，frontmatter 至少含 `name`、`description`。

| 编号 | 约束 |
| --- | --- |
| S-01 | Prompt 每轮只注入**已绑定** Skill 的 `name` / `description` / Agent 本地绝对路径；正文与附属资源不预载 |
| S-02 | 软连接的真实目标必须落在公共 Skill 根内（防越界读取） |
| S-03 | 删除被引用的 Skill 默认拒绝，并返回引用它的 Agent 列表 |

## 4. `MEMORY.md`（默认长期记忆）

见 `data-model.md` 第 3.4 节。对外形态：

```markdown
## 核心记忆

- 2026-10-05 用户是后端开发，主要语言 Java

## 归档记忆

- 2026-10-04 用户更关注 AI 和芯片方向的资讯
```

| 编号 | 约束 |
| --- | --- |
| M-01 | 写入接口带 `scope`（`CORE` / `ARCHIVAL`），缺省 `ARCHIVAL` |
| M-02 | 核心区永不截断；截断只作用于归档区 |
| M-03 | 关键词检索只在归档区匹配，不区分大小写 |
| M-04 | 每轮组装上下文都重新读文件，修改后下一轮立即生效 |

## 5. `mcp_servers.yaml`

```yaml
servers:
  - name: news
    transport: stdio
    command: ["npx", "-y", "news-mcp-server"]
    env:
      NEWS_API_KEY: ${NEWS_API_KEY}
```

| 编号 | 约束 |
| --- | --- |
| C-01 | 启动时连接并调 `tools/list`，每个工具包装为 `SableTool` 注册进 `ToolRegistry`（来源标记 `mcp`） |
| C-02 | server 失联 / 超时 / 报错时不得让主进程退出；该工具调用返回失败结果并记入审计 |
| C-03 | 凭证走 `${ENV_VAR}` 占位符，不在文件里明文落盘 |

## 6. 应用配置（`application.yaml`）

| 配置项 | 含义 | 缺省行为 |
| --- | --- | --- |
| `workspace.root` | 工作区根路径 | 当前目录下的 `.sableos/` |
| `server.port` | REST 服务端口 | `8080` |
| `file.allowed_paths` | 文件读写白名单（目录/模式） | 空 = 全部拒绝 |
| `shell.allowed_commands` | 可执行文件白名单（精确比对，不经 Shell 解释） | 空 = 全部拒绝 |
| `http.allowed_domains` | HTTP 出站域名白名单（支持通配符） | 空 = 全部拒绝 |
| `smtp.allowed_endpoints` | SMTP 出站白名单（`host:port`），供后续 email 渠道使用 | 空 = 全部拒绝 |
| `sandbox.tool_result_max_chars` | 单次工具结果截断阈值 | 有默认值；超阈值截断并标注 |
| `memory.backend` | 长期记忆后端 | `markdown`（默认，`.sableos/memory/MEMORY.md`）或 `sqlite`（`memory_entries` 表）；两档语义一致 |
| provider 段 | 各 provider 的 API key、base URL、默认模型 | 通过环境变量注入 |

| 编号 | 约束 |
| --- | --- |
| A-01 | 白名单**未配置即拒绝**（最小权限）；`init` 生成的最小白名单必须能让示例 Agent 跑通 |
| A-02 | 密钥支持 `${ENV_VAR}` 占位符，加载时从环境变量解析；缺失或非法时给清晰报错 |
| A-03 | 密钥不得出现在日志、`/api/v1/info`、`profile show` 的输出里 |
