# Contract: 配置契约——全局 provider 清单与 Agent frontmatter

**Feature**: 001-lesson16-provider | 契约类型：YAML 配置结构（application.yaml）+ AGENT.md frontmatter（由 `ProvidersProperties` / `AgentLoader` 消费）

## 1. 全局层：`sableos.providers`（application.yaml）

```yaml
sableos:
  providers: []            # 默认空清单——无 key 也可启动（CI/装配测试友好）
  # 声明示例（按需启用，凭证一律走环境变量占位）：
  #  - name: deepseek       # 名字键；Profile 按此名引用
  #    type: openai         # openai | dashscope，缺省 openai
  #    api-key: ${DEEPSEEK_API_KEY:}
  #    base-url: https://api.deepseek.com
  #  - name: qwen
  #    type: dashscope
  #    api-key: ${DASHSCOPE_API_KEY:}
```

### 1.1 条目字段

| 键 | 必填 | 规则 |
| --- | --- | --- |
| `name` | ✅ | 非空白、全局唯一（重复即启动失败）；错误消息含该名字 |
| `type` | — | 取值仅 `openai` / `dashscope`（缺省 `openai`）；其余取值启动失败并点名 |
| `api-key` | ✅ | 非空白；包含未展开的 `${` 前缀视为配置错误（视为漏配环境变量），启动失败并点名 |
| `base-url` | openai 型必填；dashscope 型可选 | 非空白 URL；工厂层归一：去尾部 `/`、去尾部 `/v1` |

### 1.2 校验时机与后果（FR-002）

`ProvidersProperties.validate()` 在启动装配期执行一次（`@ConfigurationProperties` 绑定后）——**任何违规直接让启动失败并点名该 provider**，不存在「静默跳过某个 provider」。

### 1.3 禁用 DashScope 自动装配（boot 必备项）

```yaml
spring:
  ai:
    dashscope:
      enabled: false       # 禁用 alibaba starter 全部 10 个 autoconfig（单开关全覆盖）
```

- 原因：`spring-ai-alibaba-autoconfigure-dashscope` 的 10 个 autoconfig 全部带 `@ConditionalOnDashScopeEnabled`，默认激活且无 key 即启动失败；我们只消费 `DashScopeApi`/`DashScopeChatModel`（显式构造），不需要自动装配。
- 若未来升级引入第 11 个 autoconfig，只要沿用同一条件注解即自动被覆盖（对比：排除类清单在升级后会静默失效）。

## 2. Profile 层：`.sableos/agents/<name>/AGENT.md`

```markdown
---
name: ops-agent                    # 必须与目录名 ops-agent 一致
description: 运维助手
identity:
  agent_name: 运维小助手
  prompt: 你是负责服务器巡检的运维专家……
provider:
  name: deepseek                   # 必须能在全局清单找到同名项
  model: deepseek-chat
  temperature: 0.7
tools: [read_file, shell]
settings:
  max_iterations: 10
  max_history_turns: 20
---

你负责每日巡检……（正文，本节不解析，17 节 ContextLoader 消费）
```

- frontmatter 之外的内容本节**不解析**（正文归 17 节 ContextLoader）；
- 字段全集与校验规则见 data-model.md §1；
- **不做环境变量占位解析**——Profile 不承载凭证（凭证只在全局层，spring 占位符机制负责）。

## 3. 两层职责边界

| 层 | 回答的问题 | 改动影响面 |
| --- | --- | --- |
| 全局层（application.yaml） | 「连不连得上」：有哪些 provider、凭证从哪来、走哪家协议栈 | 实例级；增减 provider 重启生效 |
| Profile 层（AGENT.md frontmatter） | 「怎么用」：这个 Agent 选哪家（provider.name）、哪个模型、什么温度 | Agent 级；换模型只改此文件（SC-003） |

## 4. 测试用配置形态（供测试类遵循）

| 场景 | 配置形态 |
| --- | --- |
| 单测（ProviderServiceTest / ToolSchemaAdapterTest） | 不碰配置——直接构造 mock `ChatModel` 注入路由表，`ProvidersProperties` 仅需 type 供选项分派 |
| ProvidersPropertiesTest | 直接构造 record 实例调 `validate()`；违规项断言消息含 provider 名 |
| ProviderAssemblyTest / 日常启动 | `sableos.providers: []`（默认），零 key 启动 |
| ProviderSmokeIT | `@DynamicPropertySource` 注入 `DEEPSEEK_API_KEY` 环境值（无则 Assumptions 跳过） |
