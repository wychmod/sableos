# REST API 契约

**Feature**: `010-lesson26-web-service` | **Base**: `/api/v1` | **Envelope**: 既有 `ApiResponse<T>`

## 通用约定

- 请求与响应均为 `application/json`（UTF-8）。
- 成功：HTTP 2xx，信封 `code = 0`、`message = "ok"`、`data` 为业务数据。
- 失败：HTTP 4xx/5xx，信封 `code` 等于 HTTP 状态值、`data = null`、`message` 为可定位原因（500/503 为通用话术）。
- 每个响应都包含 `timestamp`（ISO-8601）与 `traceId`（无链路上下文时为 `null`，字段仍输出）。
- 核心阶段无认证、无流式、无限流。

## 1. 会话管理

### 1.1 `POST /api/v1/sessions` — 建会话

请求：

```json
{ "profile": "default", "userId": "alice" }
```

- `profile` 必填；未注册 → 404。
- `userId` 可选，缺省 `anonymous`。
- 渠道固定 `web`；同一 `(web, userId, profile)` 重复调用返回同一会话（幂等），`data` 为 `SessionView`（含既有历史）。

成功：`200` + `ApiResponse<SessionView>`。

### 1.2 `POST /api/v1/sessions/{id}/messages` — 发消息

请求：

```json
{ "content": "今天北京天气怎么样" }
```

- `content` 非空、长度 ≤ 32768 字符；否则 400。
- 会话不存在 → 404。
- 正常路径只调用一次统一处理入口。

成功：`200` + `ApiResponse<ReplyView>`：

```json
{ "code": 0, "message": "ok", "data": { "reply": "..." }, "timestamp": "...", "traceId": "..." }
```

### 1.3 `GET /api/v1/sessions/{id}` — 查历史

- 会话不存在 → 404。
- 返回 `SessionView`，`messages` 为最近最多 100 条，时间正序。
- 归档会话仍返回历史。

成功：`200` + `ApiResponse<SessionView>`。

### 1.4 `DELETE /api/v1/sessions/{id}` — 归档

- 软归档：`status=archived`、首次写 `archived_at`，行保留。
- 不存在 → 404；已归档再删 → 200（幂等）。

成功：`200` + `ApiResponse<Void>`（`data = null`）。

## 2. Agent 调用

### 2.1 `POST /api/v1/agents/{name}/invoke` — 无状态调用

请求：

```json
{ "message": "用一句话介绍你自己" }
```

- Agent 未注册 → 404；`message` 为空或超 32KB → 400。
- 每次调用使用独立的一次性会话（`userId = invoke-<uuid>`），不共享历史。
- 超过 60 秒 → 504。

成功：`200` + `ApiResponse<ReplyView>`。

## 3. 信息查询

### 3.1 `GET /api/v1/profiles` — Agent 清单

成功：`200` + `ApiResponse<ProfileView[]>`；无 Agent 时为空数组。

### 3.2 `GET /api/v1/memory?profile=default` — 长期记忆全文

- `profile` 可选，缺省 `default`。
- 已注册 Agent → 返回其记忆全文；未注册或未写入 → `content` 为空字符串。

成功：`200` + `ApiResponse<MemoryView>`：

```json
{ "code": 0, "message": "ok", "data": { "profile": "default", "content": "..." }, "timestamp": "...", "traceId": "..." }
```

### 3.3 `GET /api/v1/tools` — 工具清单

成功：`200` + `ApiResponse<ToolView[]>`；无工具时为空数组。

## 4. 系统状态

### 4.1 `GET /api/v1/health` — 存活探针

不依赖模型提供方或外部服务。

成功：`200` + `ApiResponse<HealthView>`，`data.status = "ok"`。

### 4.2 `GET /api/v1/info` — 系统信息与 Provider 连通状态

成功：`200` + `ApiResponse<SystemInfoView>`：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "app": "SableOS",
    "version": "0.1.0-SNAPSHOT",
    "providers": [
      { "name": "deepseek", "type": "openai", "reachable": true, "latencyMs": 42, "error": null }
    ]
  },
  "timestamp": "...",
  "traceId": "..."
}
```

- 探测为 HTTP 连通性，不调模型、不耗 token。
- 单条探测超时 / 失败只把该条标为 `reachable=false`，不影响其它条与整体 200。

## 5. 错误码总表

| 状态码 | 触发条件 | message 口径 |
|---|---|---|
| 400 | 参数缺失 / 空白 / 超长 / JSON 非法 | 可定位原因 |
| 404 | 会话或 Agent 不存在 | 点名资源 |
| 500 | 未预期内部异常 | `Internal server error` |
| 503 | 模型提供方调用失败 | `模型提供方调用失败` |
| 504 | Agent 调用超过 60 秒 | `Agent 调用超过 60 秒` |

## 6. 不在本契约内

- 认证 / API Key / JWT、SSE、WebSocket、RBAC、限流。
- `GET /api/v1/sessions` 列表（27 节）。
- Memory 写入 / 检索、Tool 调用历史、LLM 调用历史（扩展阶段）。
- `/api/v1/agents` 的增删改（29/30 节）。
- 管理台前端、静态资源、SPA 回落（独立 agent）。
