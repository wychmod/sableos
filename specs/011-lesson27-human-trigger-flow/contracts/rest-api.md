# Contract: 会话列表端点

**Feature**: `011-lesson27-human-trigger-flow` | 本节新增第 11 个核心端点

## `GET /api/v1/sessions`

列出会话摘要，按最后活跃时间倒序。

### 请求

| 参数 | 位置 | 必填 | 说明 |
| --- | --- | --- | --- |
| `status` | query | 否 | 只返回该状态的会话（`active` / `archived`）；缺省或空白表示不过滤 |

### 响应 `200`

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    {
      "id": "web:anonymous:mock-agent",
      "profileName": "mock-agent",
      "channel": "web",
      "userId": "anonymous",
      "status": "active",
      "createdAt": "2026-10-31T07:02:47.722Z",
      "lastActiveAt": "2026-10-31T07:02:59.882Z",
      "messageCount": 4
    }
  ],
  "timestamp": "2026-10-31T07:02:59.994Z",
  "traceId": "bbe4078d8d42eb35aa924d92248212ea"
}
```

### 约定

- **最多 100 条**（`ApiConstraints.MAX_SESSION_LIST`），不提供分页。
- `data` 为数组，空存储返回 `[]` 而不是 `404`。
- **不含**对话正文；要看正文用 `GET /api/v1/sessions/{id}`。
- 未知 `status` 值不报错，返回空数组（等价于"该状态没有会话"）。
- 过滤、排序、截断都在存储侧完成。

### 错误

本节不新增错误码；信封与 `GlobalExceptionHandler` 沿用 26 节。

## 与既有端点的关系

| 端点 | 说明 |
| --- | --- |
| `POST /api/v1/sessions` | 创建（幂等） |
| `POST /api/v1/sessions/{id}/messages` | 发消息 |
| `GET /api/v1/sessions` | **本节新增** |
| `GET /api/v1/sessions/{id}` | 查历史（含正文） |
| `DELETE /api/v1/sessions/{id}` | 软归档 |

至此 `/api/v1` 下共 11 个端点（会话 5 / Agent 调用 1 / 信息查询 3 / 系统状态 2）。
