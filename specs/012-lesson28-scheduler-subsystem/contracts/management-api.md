# Contract: 定时任务与通知渠道管理 API

**Base**: Spring MVC，统一 `ApiResponse` 信封（`code` / `message` / `data` / `timestamp` / `traceId`）。成功 `code=0`；错误 `code` 取 HTTP 状态值。

## 1. 定时任务 v2（管理台唯一调用）

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/api/v2/schedules` | 列出活动任务（`retired=false`） |
| GET | `/api/v2/schedules/{scheduleId}/executions` | 某任务的执行历史 |
| POST | `/api/v2/schedules/{scheduleId}/run` | 立即执行一次（无视启用状态） |
| PUT | `/api/v2/schedules/{scheduleId}` | 启用 / 停用 |
| POST | `/api/v2/agents/{profileName}/schedules/{key}/run` | 按 Agent + 任务键精确定位后立即执行 |

### `ScheduleView`

```json
{
  "scheduleId": 1,
  "profileName": "weather-agent",
  "scheduleKey": "daily-weather",
  "displayName": "daily-weather",
  "cron": "0 0 8 * * *",
  "zone": "Asia/Shanghai",
  "message": "查一下北京天气，把穿搭建议推送出去",
  "enabled": true,
  "retired": false,
  "nextRunAt": "2026-11-01T00:00:00Z",
  "lastRunAt": "2026-10-31T00:00:00Z",
  "lastStatus": "success",
  "runCount": 1
}
```

### `ScheduleExecutionView`

```json
{
  "id": 10,
  "scheduleId": 1,
  "sessionId": "scheduler:scheduler:weather-agent",
  "startedAt": "2026-10-31T00:00:00Z",
  "success": true,
  "errorMessage": null,
  "durationMs": 1234
}
```

### `PUT /api/v2/schedules/{scheduleId}`

请求：

```json
{ "enabled": false }
```

响应：更新后的 `ScheduleView`。`enabled` 缺失或非布尔 → 400；任务不存在 → 404。

### 状态码

| 情况 | HTTP |
| --- | --- |
| 列表 / 历史 / 执行成功 / 启停成功 | 200 |
| 任务不存在（含已退役） | 404 |
| 立即执行时任务锁被占 | 409，message 说明上一次尚未结束 |
| `enabled` 缺失或非布尔 | 400 |
| 存储或执行内部错误 | 500 |

## 2. 定时任务 v1 兼容层

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/api/v1/schedules` | 列出活动任务（与 v2 同一份数据） |
| GET | `/api/v1/schedules/{key}/executions` | 按任务键查执行历史 |
| POST | `/api/v1/schedules/{key}/run` | 按任务键立即执行 |
| PUT | `/api/v1/schedules/{key}` | 按任务键启用 / 停用 |

按 key 定位的解析规则：

- 0 条 → 404。
- 1 条 → 正常处理。
- 多条（多个 Agent 使用同一 key）→ 409，message 提示改用 `/api/v2/schedules/{scheduleId}`。

`GET /api/v1/schedules` 不歧义，返回全部活动任务。v1 与 v2 返回同一份状态与历史，不产生两套账。

## 3. 通知渠道（v1）

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/api/v1/notify-channels` | 列出全部渠道（按创建时间升序） |
| POST | `/api/v1/notify-channels` | 新建渠道 |
| PUT | `/api/v1/notify-channels/{name}` | 更新渠道 |
| DELETE | `/api/v1/notify-channels/{name}` | 删除渠道 |

### 请求体

```json
{
  "name": "team-webhook",
  "type": "webhook",
  "url": "https://example.com/hooks/team",
  "description": "团队通知群",
  "config": {}
}
```

### 响应体

```json
{
  "name": "team-webhook",
  "type": "webhook",
  "url": "https://example.com/hooks/team",
  "description": "团队通知群",
  "config": {},
  "createdAt": "2026-10-31T00:00:00Z",
  "updatedAt": "2026-10-31T00:00:00Z"
}
```

### 状态码

| 情况 | HTTP |
| --- | --- |
| 列表 / 新建 / 更新 / 删除成功 | 200 |
| 重名新建 | 409 |
| 更新 / 删除不存在 | 404 |
| 名称为空或非法、类型非 webhook、url 非法 | 400 |

## 4. 信封与错误

- 成功：`{"code":0,"message":"ok","data":{...},"timestamp":"...","traceId":"..."}`。
- 错误：`{"code":404,"message":"任务不存在: 99","data":null,"timestamp":"...","traceId":"..."}`。
- 错误 message 由服务端构造，不回显原始异常堆栈；用户可控串（任务键、渠道名）入日志前已做 CRLF 净化。

## 5. 与既有端点的关系

- 本节新增的 v2 路径不计入《技术方案》§7.2 的 11 个核心 v1 端点；README 单独列出"定时任务管理"与"通知渠道"两组。
- `POST /api/v2/agents/{profileName}/schedules/{key}/run` 与既有 `POST /api/v1/agents/{name}/invoke` 路径不冲突（版本与后缀不同）。
- 通知渠道的 `GET /api/v1/notify-channels` 等是 §6.8 规定的核心阶段管理面，不改变既有 11 个端点的行为。
