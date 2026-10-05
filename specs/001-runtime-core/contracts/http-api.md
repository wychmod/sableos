# 契约：HTTP API（核心阶段 10 个端点）

**前缀**：`/api/v1`｜**服务启动**：`sableos serve`，默认端口 `8080`｜**契约与实现分离**：仅描述对外可观察行为，不约束内部实现

## 统一响应信封

成功与错误共用一个信封：

```json
{
  "code": 0,
  "message": "ok",
  "data": { },
  "timestamp": "2026-10-05T15:00:00Z"
}
```

- `code`：`0` 表示成功；非 0 为内部错误码
- `message`：人类可读信息（错误时为可定位原因）
- `data`：成功时为业务数据，错误时为 `null`

## 状态码约定

| 状态码 | 语义 |
| --- | --- |
| 200 | 成功 |
| 400 | 参数错误（缺参、格式非法、超长、provider 未注册等可归因于请求的问题） |
| 404 | 资源不存在（会话或 Agent 名不存在） |
| 409 | 冲突（例如同一名称的多义定位） |
| 500 | 内部错误 |
| 503 | 模型提供方故障（核心阶段不做 fallback，直接暴露） |
| 504 | Agent 调用超时（默认 60 秒） |

## 全局限制

| 项 | 值 |
| --- | --- |
| 单条消息最大 | 32KB |
| 会话历史返回条数 | 默认最近 100 条 |
| Agent 调用超时 | 60 秒 |
| CORS | 核心阶段放开（扩展阶段加白名单） |
| 认证 | 核心阶段无（假设受信内网） |

## 1. 会话管理（4 个）

### 1.1 创建会话

```http
POST /api/v1/sessions
```

| 参数 | 位置 | 必填 | 说明 |
| --- | --- | --- | --- |
| `profile_name` | body | 是 | 目标 Agent 的运行配置名 |
| `user_id` | body | 否 | 用户标识；缺省 `anonymous` |
| `channel` | body | 否 | 缺省 `web` |

**响应**：`data.session_id`、`data.created_at`。同一 `(channel, user_id, profile_name)` 组合已存在活动会话时，返回既有会话而不是新建。

### 1.2 发送消息

```http
POST /api/v1/sessions/{id}/messages
```

| 参数 | 位置 | 必填 | 说明 |
| --- | --- | --- | --- |
| `content` | body | 是 | 用户消息（≤32KB） |

**响应**：`data.reply`（最终回答）、`data.iterations`（本次循环轮数）、`data.terminated_reason`（正常结束为 `final`；达到上限为 `max_iterations`）。**循环的中间步骤留在该会话历史中，不在此响应里回传。**

### 1.3 查询会话历史

```http
GET /api/v1/sessions/{id}?limit=100
```

**响应**：`data.session`（元信息与状态）、`data.messages`（最近 N 条，含模型输出与工具结果，按时间顺序）。

### 1.4 归档会话

```http
DELETE /api/v1/sessions/{id}
```

**语义**：置为 `archived`（**不物理删除**）。归档后可查历史，不可再追加消息（返回 400）。

## 2. Agent 调用（1 个）

### 2.1 无状态调用

```http
POST /api/v1/agents/{name}/invoke
```

| 参数 | 位置 | 必填 | 说明 |
| --- | --- | --- | --- |
| `message` | body | 是 | 一次性输入 |
| `user_id` | body | 否 | 缺省 `anonymous`（该次调用同样落会话，便于事后追溯与手动补跑） |

**响应**：与 1.2 相同的结构。**约定**：定时任务的手动补跑即调用本端点，与自动触发共用同一条处理链路。

## 3. 信息查询（3 个）

### 3.1 `GET /api/v1/profiles`

返回已注册的 Agent 运行配置列表：`name`、`description`、`provider.name`、`provider.model`、`tools`、`channels`、是否有 `schedules`。**不返回**密钥、Bootstrap 正文、Agent 正文。

### 3.2 `GET /api/v1/memory?profile_name=`

返回长期记忆的**结构化视图**：核心区条目、归档区条目（含 `scope`）。核心区不截断、归档区按配置截断。**不提供写入**（写入只能由 Agent 通过 `save_memory` 触发）。

### 3.3 `GET /api/v1/tools`

返回已注册工具列表：`name`、`description`、`source`（`builtin` / `mcp` / `annotation`）、入参 JSON Schema。可选 `?profile_name=` 返回该 Agent 可用子集。

## 4. 系统状态（2 个）

### 4.1 `GET /api/v1/health`

存活与依赖健康：进程状态、SQLite 可达性、已注册 Agent 数、最近一次调度触发结果。**不返回**任何密钥或路径明细。

### 4.2 `GET /api/v1/info`

运行信息：版本、JDK 版本、启动时间、工作区根路径、已加载 provider name 列表、已连接 MCP server 列表、各 Agent 的 `schedules` 概要。

## 5. 契约测试要点

| 编号 | 断言 |
| --- | --- |
| C-01 | 10 个端点全部可达且返回统一信封 |
| C-02 | 不存在的会话/Agent 返回 404 且 `message` 可定位 |
| C-03 | `user_id` 缺省为 `anonymous`，且同组合复用同一会话 |
| C-04 | 达到迭代上限时 `terminated_reason=max_iterations`，且历史保留中间步骤 |
| C-05 | 超过 32KB 的消息返回 400；历史查询不超过 100 条 |
| C-06 | 提供方故障返回 503；调用超时返回 504 |
| C-07 | 归档后的会话仍可查询、不可再追加消息 |
