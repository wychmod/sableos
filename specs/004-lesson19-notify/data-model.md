# Data Model: Notify——出站通知（第 19 节）

**Feature**: 004-lesson19-notify | **Date**: 2026-10-15 | 上游：[spec.md](./spec.md) · [research.md](./research.md)

三类实体：**通知目标（NotifyTarget）**、**出站通知抽象（NotifyChannelAdapter）**、**通用 webhook 实现（WebhookNotifyAdapter）**；另有**全局通知渠道注册表**（随后续节落地、本节只依赖其「按名引用」语义）与**通知投递线格式**（一次 POST 的 body 契约）。**本节零数据库迁移、零新表**。

## 1. 通知目标（NotifyTarget）

> "把一条内容送到哪里"的载体：渠道类型 + 一份配置映射；对上层是黑盒，配置如何解释由实现类决定——接口语汇零渠道特有词（FR-001）。

**形态**：`record NotifyTarget(String channelType, Map<String, String> config)`（`com.sableos.tool.notify`，课件字面量）。

| 成员 | 类型 | 说明 |
| --- | --- | --- |
| channelType | String | 渠道类型（如 `webhook`；扩展阶段 `feishu`/`email` 等按实现增挂） |
| config | `Map<String, String>` | 实现相关的配置映射；webhook 档取 `config.get("url")` 为投递地址；**恒非 null 且不可变**（防御拷贝） |

**不变量（compact 构造强制）**：`config == null → Map.of()`；否则 `Map.copyOf(config)`——与 core `ProviderResponse` 同款约定（research D-01）。

**边界**：`NotifyTarget` 不携带"发送逻辑"，也不是 Spring bean；调用方（未来 `NotifyTools`）从注册表组装它，或由装配层提供。

## 2. 出站通知抽象（NotifyChannelAdapter）

> "送内容到目标"这一意图的唯一表达；核心阶段一档实现，扩展阶段按渠道类型增挂（不改接口、不改调用方——接口先行）。

**形态**：`interface NotifyChannelAdapter { void send(NotifyTarget target, String content); }`

**行为契约**：

| 情形 | 契约 |
| --- | --- |
| 正常发送 | 同步阻塞完成一次投递；返回 `void`（成功即返回，不吞错） |
| 对端 4xx/5xx | MUST 异常上抛（实现不得 catch 后静默） |
| 连接失败/超时 | MUST 异常上抛 |
| 目标配置不满足实现要求（如 webhook 缺 url） | MUST 发送前明确报错（不发出任何请求） |
| 空 content | 照常发送（通知层不替调用方判断内容意义） |

**实现方约束**：接口签名与语汇 MUST NOT 出现任何一档实现特有的词（如 `webhook`/`url`/`feishu`）——`send(NotifyTarget, String)` 对"换成企业微信官方 SDK 实现要不要改签名"的回答应为"不需要"（SC-005 人工自查项）。

## 3. 通用 webhook 实现（WebhookNotifyAdapter）

> 核心阶段唯一实现（FR-002）：把内容包成 JSON 向目标地址发一次 POST；目标地址取自 `target.config()`，不得硬编码。

**形态**：`class WebhookNotifyAdapter implements NotifyChannelAdapter`（纯 POJO，零 Spring 注解——装配归 20 节）；构造注入 `RestClient`（课件骨架字面量）。

**send 行为（两条路径）**：

| 路径 | 条件 | 行为 |
| --- | --- | --- |
| 快速失败 | `config.get("url")` 为 null/空白 | 抛 `IllegalArgumentException`，消息含 `url` 字面（报错点名缺失键）；**零请求发出** |
| 正常发送 | url 就绪 | `restClient.post().uri(url).contentType(APPLICATION_JSON).body(Map.of("content", content)).retrieve().toBodilessEntity()` |

**失败语义（不外扩）**：4xx/5xx → `RestClientResponseException` 上抛；连接失败 → `ResourceAccessException` 上抛（均依赖 RestClient 默认判定，不注册吞错处理器，research D-03）；3xx 本节不特判（出网管控归 23/24 节）。

**日志纪律**：url 与 config 内容不进日志（FR-005：webhook 地址可能含凭证 token）。

## 4. 通知投递线格式（一次 POST 的 body）

| 项 | 值 |
| --- | --- |
| HTTP 方法 | `POST`（恰好一次/调用） |
| Content-Type | `application/json` |
| body | `{"content":"<推送内容>"}`（`Map.of("content", content)` 序列化；content 为裸字符串值） |
| 目标地址 | `NotifyTarget.config()["url"]`（运行时配置，不在对话/接口签名中出现） |
| 响应 | body 不消费（`toBodilessEntity()`）；仅状态码参与成败判定 |

**不做**：重试、幂等键、签名（钉钉加签/飞书签名属扩展阶段专用 Adapter）；发送前域名白名单校验位归 23/24 节接线（本节实现层不出现校验代码）。

## 5. 全局通知渠道注册表（后续节实体，本节只依赖契约语义）

> 通知出口的唯一真相源（技术方案 §6.8）：渠道存 SQLite，Agent 按名引用；本特性**不实现**其存储与 CRUD，只在契约层依赖它供 `NotifyTools` 做渠道解析（`contracts/notify-tool.md`）。

**注册项字段口径**（§6.8 预告，20/26 节落地时以此为准）：

| 字段 | 说明 |
| --- | --- |
| name | 渠道全局注册名（LLM 通过 `channel` 参数引用） |
| type | 渠道类型（对应 `channelType`；核心阶段 `webhook`） |
| url | HTTP 类渠道的目标地址（webhook/feishu 等；凭证走 `${ENV}` 占位） |
| description | 可选描述 |
| config | 承载类型相关多字段（如 email 的 host/port/from/to） |

**本节使用方式**：仅依赖两条语义——①「按名引用」（不落在任何 Profile 字段里）；②「有序」的存在（第一个渠道 = 缺省渠道）。具体载体（表/CRUD/管理台）随后续节。

## 6. 与既有实体的关系

```text
（未来）NotifyTools ──从注册表按名解析──▶ NotifyTarget ──send(target, content)──▶ NotifyChannelAdapter
                                                                                    └─ 核心阶段唯一实现：WebhookNotifyAdapter ──POST──▶ webhook 地址
审计：notify 作为工具被调用时，成败经 ToolExecutor 既有路径落 tool_invocations（本节不新增审计逻辑、不改表）
```

- **与入站 Channel 的关系**：语义方向相反，分开建模（入站 `ChannelAdapter` = "什么触发 Agent 跑"；出站 `NotifyChannelAdapter` = "跑完结果送到哪"）；**不合并抽象**（FR 边界）。
- **与 Session/ToolResult 的关系**：`send` 的成功/失败最终由 `NotifyTools` 翻译为 `ToolResult`（返回给 ReActLoop）；失败经 `ToolExecutor` 的既有 catch 与审计路径落库——本节实现的异常形态（`IllegalArgumentException` / `RestClientResponseException` / `ResourceAccessException`）就是给那条路径的输入。
- **与 Sandbox 的关系**：白名单校验位在 `NotifyTools` 层（"发出前"），不在适配器实现层——适配器只负责投递语义；接线位留到 24 节（本节不出现 Sandbox 类型）。
