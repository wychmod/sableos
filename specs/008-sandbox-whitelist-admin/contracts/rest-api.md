# REST API 契约

**Feature**: `008-sandbox-whitelist-admin` | 基址：`/api/v1/sandbox/whitelist`

## 通用约定

- 响应信封恒为 5 字段：`code` / `message` / `data` / `timestamp` / `traceId`（见 `ApiResponse`）。
- 成功：`code = 0`，`message = "ok"`。
- 错误：`code` 取标准 HTTP 状态值，`data = null`。
- 不使用 204；`DELETE` 成功返回 200 + `data: null`。
- 所有路径在 `sableos.sandbox.admin.enabled=false` 时不存在（走既有 404 映射）。

## 端点

| 方法 | 路径 | 语义 | 成功码 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/sandbox/whitelist` | 列表；`type` 可选 | 200 |
| `GET` | `/api/v1/sandbox/whitelist/{id}` | 单条详情 | 200 |
| `POST` | `/api/v1/sandbox/whitelist` | 新增 | 201 |
| `PUT` | `/api/v1/sandbox/whitelist/{id}` | 修改 value / description | 200 |
| `DELETE` | `/api/v1/sandbox/whitelist/{id}` | 删除 | 200 |

## 1. GET /api/v1/sandbox/whitelist

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | 枚举字符串 | 否 | `FILE_PATH` / `SHELL_COMMAND` / `HTTP_DOMAIN`；缺省返回全部 |

**响应 200**：

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    {
      "id": 7,
      "type": "HTTP_DOMAIN",
      "value": "api.open-meteo.com",
      "description": "天气 Demo",
      "createdAt": "2026-10-27T10:00:00Z",
      "updatedAt": "2026-10-27T10:00:00Z"
    }
  ],
  "timestamp": "2026-10-27T10:00:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

排序：`type ASC, value ASC`。

**错误**：

| 场景 | 码 | message |
| --- | --- | --- |
| `type` 取值非法 | 400 | `type 不是合法的白名单类型` |

## 2. GET /api/v1/sandbox/whitelist/{id}

**响应 200**：`data` 为单个条目对象（同上结构）。

**错误**：

| 场景 | 码 | message |
| --- | --- | --- |
| id 不存在 | 404 | `白名单条目不存在: {id}` |
| id 非数字 | 400 | 由既有 `MethodArgumentTypeMismatchException` 处理 |

## 3. POST /api/v1/sandbox/whitelist

**请求体**：

```json
{
  "type": "FILE_PATH",
  "value": ".sableos/agents",
  "description": "所有 Agent 目录"
}
```

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `type` | 是 | 三枚举值之一 |
| `value` | 是 | 见校验规则 |
| `description` | 否 | ≤255 |

**响应 201**：`data` 为新建条目（含归一后的 `value` 与 `id`）。

**错误**：

| 场景 | 码 | message |
| --- | --- | --- |
| type 缺失/非法 | 400 | `type 不是合法的白名单类型` |
| value 空白/超长 | 400 | `value 不能为空` / `value 长度超过 1024` |
| 格式非法 | 400 | `HTTP_DOMAIN 格式非法：只接受主机名或 *.主机名` |
| 非 ASCII 域名 | 400 | `HTTP_DOMAIN 只接受 ASCII 主机名，请使用 punycode（xn--...）形式` |
| 已存在 | 409 | `白名单条目已存在: {type} {归一后的 value}` |
| 该类已达 1000 条 | 400 | `FILE_PATH 类型条目数已达上限 1000` |

**关键约束**：错误 message **不含调用方原始输入**。

## 4. PUT /api/v1/sandbox/whitelist/{id}

**请求体**：

```json
{
  "value": ".sableos/agents-v2",
  "description": "更新后的说明"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `value` | 是 | 归一后判重 |
| `description` | 否 | 传 `null` 表示清空 |

**`type` 不可变**：请求体不含 `type`；若携带则忽略（或用 `@JsonIgnoreProperties` 拒绝，实现时二选一并写测试）。**决策：忽略**，避免严格 Jackson 配置影响其他端点。

**响应 200**：`data` 为更新后的条目。

**错误**：

| 场景 | 码 | message |
| --- | --- | --- |
| id 不存在 | 404 | `白名单条目不存在: {id}` |
| 新值与该类型另一条重复 | 409 | `白名单条目已存在: {type} {value}`，**原值保持不变** |
| 校验失败 | 400 | 同 POST |

## 5. DELETE /api/v1/sandbox/whitelist/{id}

**响应 200**：

```json
{ "code": 0, "message": "ok", "data": null, "timestamp": "...", "traceId": "..." }
```

**错误**：

| 场景 | 码 | message |
| --- | --- | --- |
| id 不存在 | 404 | `白名单条目不存在: {id}` |

## 校验规则汇总

| 类型 | 规则 | 归一化 |
| --- | --- | --- |
| `FILE_PATH` | 非空、≤1024；`Path.of` 不抛 `InvalidPathException`；不含 NUL/CR/LF；归一后非空 | `Path.of(trim).normalize()` → 分隔符替换为 `/` |
| `SHELL_COMMAND` | 非空、≤1024；单 token（不含空白）；不含 CR/LF | `trim()`；大小写保留 |
| `HTTP_DOMAIN` | 非空、≤1024；host 或 `*.host`；无 scheme/port/path/空白/`..`；通配仅最左；**仅 ASCII** | `trim()` + `toLowerCase(Locale.ROOT)` |

## 错误响应示例

```json
{
  "code": 400,
  "message": "HTTP_DOMAIN 格式非法：只接受主机名或 *.主机名",
  "data": null,
  "timestamp": "2026-10-27T10:00:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

## 配置键

```yaml
sableos:
  sandbox:
    admin:
      enabled: true   # 默认 true；false 时该 Controller 不装配
```
