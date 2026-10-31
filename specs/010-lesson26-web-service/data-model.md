# Phase 1 Data Model: Web Service 门面

**Feature**: `010-lesson26-web-service` | **Date**: 2026-10-29

本节不新增数据库表。下面描述的是对外传输对象（DTO）与既有领域对象之间的映射关系，以及会话状态的唯一新增迁移。

## 1. 对外传输对象

### 1.1 请求体

| 名称 | 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|---|
| `CreateSessionRequest` | `profile` | string | 是 | 非空白；必须是已注册 Agent 名 |
| | `userId` | string | 否 | 缺省 `anonymous`；空白视为缺省 |
| `SendMessageRequest` | `content` | string | 是 | 非 null、非空白；长度 ≤ 32 × 1024 字符 |
| `InvokeRequest` | `message` | string | 是 | 非 null、非空白；长度 ≤ 32 × 1024 字符 |

> 本节只有这三个请求体。发消息与 invoke 的大小校验口径一致（课件只对消息端点写了 32KB，invoke 复用同一上限以免出现第二个口径）。

### 1.2 响应体

| 名称 | 字段 | 类型 | 说明 |
|---|---|---|---|
| `SessionView` | `id` | string | 会话标识（`web:<userId>:<profile>`） |
| | `profileName` | string | Agent 名 |
| | `channel` | string | 固定 `web` |
| | `userId` | string | 用户标识 |
| | `status` | string | `active` / `archived` |
| | `createdAt` | ISO-8601 string | 创建时刻 |
| | `lastActiveAt` | ISO-8601 string | 最后活跃时刻 |
| | `messages` | `MessageView[]` | 最多最近 100 条，时间正序 |
| `MessageView` | `role` | string | `USER` / `ASSISTANT` / `TOOL` |
| | `content` | string | 文本；工具条目为结果或失败原因 |
| | `toolName` | string/null | 仅 `TOOL` 条目 |
| | `success` | boolean/null | 仅 `TOOL` 条目 |
| `ReplyView` | `reply` | string | Agent 最终答复；可能为空字符串 |
| `ProfileView` | `name` | string | Agent 名 |
| | `description` | string/null | 简述 |
| | `provider` | string | 提供方名 |
| | `model` | string | 模型名 |
| | `tools` | string[] | 声明的工具名 |
| | `channels` | string[] | 声明的渠道名 |
| `ToolView` | `name` | string | 工具名 |
| | `description` | string | 工具描述 |
| | `inputSchema` | string | JSON Schema 文本 |
| `MemoryView` | `profile` | string | 实际查询的 Agent 名 |
| | `content` | string | 记忆全文；未写入为空字符串 |
| `HealthView` | `status` | string | 固定 `ok` |
| `SystemInfoView` | `app` | string | 应用名 |
| | `version` | string | 应用版本 |
| | `providers` | `ProviderStatusView[]` | 已配置提供方状态 |
| `ProviderStatusView` | `name` | string | 提供方名 |
| | `type` | string | `openai` / `dashscope` |
| | `reachable` | boolean | 探测结果 |
| | `latencyMs` | number/null | 探测耗时；不可达时可为 null |
| | `error` | string/null | 失败原因（已净化、已截断） |

所有响应均包在既有 `ApiResponse<T>` 中：`code`（0 成功，否则 HTTP 状态值）、`message`、`data`、`timestamp`、`traceId`。

## 2. 领域对象映射

| 领域对象 | 来源 | 映射规则 |
|---|---|---|
| `Session` | `com.sableos.core.session.Session` | `id/profileName/channel/userId/status/createdAt/lastActiveAt` 直取；`messages()` 截取末尾 100 条后映射为 `MessageView` |
| `Profile` | `com.sableos.core.profile.Profile` | 只取 `name/description/provider.name/provider.model/tools/channels`；**不输出** `identity.prompt`、`bootstrap`、`settings` |
| `SableTool` | `com.sableos.core.tool.SableTool` | `getName/getDescription/getInputSchema` |
| `ProviderStatus` | `com.sableos.core.provider.ProviderStatus` | 直出；`error` 已在探测实现内净化 |
| 记忆全文 | `com.sableos.core.memory.MemoryProvider#memorySection(Profile)` | 已注册 Profile 才调用；未注册返回空字符串 |

## 3. 会话状态迁移

```text
（新会话）──POST /sessions 或 POST /messages 命中不存在的 id──> active
active ──DELETE /sessions/{id}──> archived（写 archived_at）
archived ──DELETE /sessions/{id}（重复）──> archived（archived_at 不变）
archived ──POST /sessions/{id}/messages──> archived（允许继续追加历史；本节不做状态迁移）
```

约束：

- `session_id` 只能由 `SessionManager.sessionId(channel, user, profile)` 生成；Controller 不得自行拼接。
- `status` 只允许 `active` / `archived` 两值（技术方案 §9.2）。
- `archived_at` 在首次归档时写入；重复归档不覆盖。
- 归档不物理删除行；`GET /sessions/{id}` 对归档会话仍返回历史。

## 4. 校验与错误对照

| 场景 | 状态码 | 响应 message |
|---|---|---|
| 建会话缺 `profile` / 空白 | 400 | 点名缺失字段 |
| 建会话引用的 Agent 未注册 | 404 | `Agent 不存在: <name>` |
| 发消息 `content` 为空 / 空白 / 超 32KB | 400 | 可定位原因 |
| 会话不存在（发消息 / 查历史 / 归档） | 404 | `会话不存在: <id>` |
| 无状态调用的 Agent 未注册 | 404 | `Agent 不存在: <name>` |
| Agent 调用超过 60 秒 | 504 | `Agent 调用超过 60 秒` |
| 模型提供方调用失败 | 503 | `模型提供方调用失败`（不含原始异常） |
| 未预期内部异常 | 500 | `Internal server error`（不含原始异常） |
| 请求体缺失 / JSON 语法错 | 400 | MVC 可读原因 |
