# REST API 契约：动态管理 Agent（第 30 节）

> 统一信封沿用 26 节 `ApiResponse`（`code` / `message` / `data` / `timestamp` / `traceId`）。错误码：400 参数或定义非法、404 不存在、503 生成模型未配置或上游失败、504 调用超时。以下路径均在 `/api/v1` 下。

## 1. 端点全表（13 行）

| # | 方法 | 路径 | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | `POST` | `/agents` | 创建 `{name, description}` → 脚手架完整目录 + 派生注册（失败回滚） | 新增 |
| 2 | `GET` | `/agents` | 列表 | 新增 |
| 3 | `GET` | `/agents/{name}` | 单个详情（含文件树） | 新增 |
| 4 | `PUT` | `/agents/{name}` | 覆写 `AGENT.md`；schedules 变则先注销旧再注册新 | 新增 |
| 5 | `DELETE` | `/agents/{name}` | 注销定时 → 移出索引 → 目录归档 | 新增 |
| 6 | `POST` | `/agents/{name}/invoke` | 无状态调用（26 节） | **不变** |
| 7 | `GET` | `/agents/{name}/memory` | 该 Agent 的专属记忆 | 新增 |
| 8 | `GET` | `/agents/{name}/session` | 该 Agent 的固定管理台会话 | 新增 |
| 9 | `POST` | `/agents/{name}/session/messages` | 往固定会话发消息、触发处理 | 新增 |
| 10 | `POST` | `/agents/{name}/generate-files` | 描述 → 模型生成文件草稿（不落盘） | 新增 |
| 11 | `POST` | `/agents/{name}/files` | 保存一组文件、写入即生效 | 新增 |
| 12 | `GET` | `/workspace/tree` | 目录树（agents/ + archive/） | 新增 |
| 13 | `GET` | `/workspace/file?path=` | 读文件（防穿越） | 新增 |
| 14 | `POST` | `/workspace/file` | 写文件（防穿越；`AGENT.md` 走 update 重注册） | 新增 |

> 课件 §5.3 的表把 `GET /agents` 与 `GET /agents/{name}` 合并为一行、`/agents/generate` 已取消，故 14 个 HTTP 端点对应 13 行语义。

**移除**：`GET /api/v1/memory`（全局，D-10）。

## 2. 逐端点契约

### 2.1 `POST /agents`

**请求**
```json
{ "name": "weather-daily", "description": "每天早上九点查北京天气，把穿搭建议发到团队群" }
```

**200 响应 `data`**：`AgentDetailView`

**副作用**：写出 `agents/<name>/AGENT.md` + `scripts/example.py` + `REFERENCE.md`；`AGENT.md` 无 `schedules`（脚手架不含定时，定时由用户编辑或生成加入）。

**错误**
| 状态 | 触发 |
| --- | --- |
| 400 | name 缺失/空白/含非法字符/超长；name 已存在；description 超长 |

**回滚**：`writeAll` 之后任一环节抛异常 → `agentStore.delete(dir)` → 重新抛出（注册表与调度器不留残留）。

### 2.2 `GET /agents`

**200 `data`**：`List<AgentView>`（按 name 排序）。

### 2.3 `GET /agents/{name}`

**200 `data`**：`AgentDetailView`（含 `files` = 该 Agent 目录的文件树）。

**404**：未注册。

### 2.4 `PUT /agents/{name}`

**请求**
```json
{ "content": "---\nname: weather-daily\n...\n---\n\n正文..." }
```

**200 `data`**：`AgentDetailView`（新定义）

**语义**：先 `agentLoader.parse(content, name)` 预校验（非法 400、原文件不动）→ `writeAll` → `refresh`（先注销旧定时 → 注册新的）。

**404**：未注册。

### 2.5 `DELETE /agents/{name}`

**200 `data`**：`null`

**定序**（必须保持）：`unregisterProfile` → `registry.remove` → `agentStore.archive`。

**404**：未注册。

### 2.6 `POST /agents/{name}/invoke`（既有，不变）

`{"message": "..."}` → `{"reply": "..."}`；404 未注册 / 400 空白或超 32KB / 504 超时。

### 2.7 `GET /agents/{name}/memory`

**200 `data`**：`{"profile": "<name>", "content": "<MEMORY.md 全文或空串>"}`

**404**：未注册。未写过的记忆返回空串而非 404（沿用 26 节口径）。

### 2.8 `GET /agents/{name}/session`

**200 `data`**：`SessionView`（固定三元组 `admin` / `console` / `<name>`；最多最近 100 条消息）。

**404**：未注册。

### 2.9 `POST /agents/{name}/session/messages`

**请求**：`{"content": "..."}`

**200 `data`**：`{"reply": "..."}`

**语义**：`getOrCreate("admin","console",name)` → `agentCallExecutor.process(session, content)`（60 秒上限）。

**错误**：400 空白或超 32KB；404 未注册；504 超时。

### 2.10 `POST /agents/{name}/generate-files`

**请求**：`{"description": "每天早上九点查天气并推送到团队群"}`

**200 `data`**：
```json
{
  "files": {
    "AGENT.md": "---\nname: weather-daily\n...",
    "scripts/fetch_weather.py": "..."
  }
}
```

**语义**：一次 `providerService.chat` → 剥围栏 → 按 `===FILE: <path>===` 切分 → 每个 `AGENT.md` 走 `agentLoader.parse` → 返回。**不落盘、不注册。**

**错误**
| 状态 | 触发 |
| --- | --- |
| 400 | description 空；模型输出缺 `AGENT.md`；`AGENT.md` 解析失败；产出路径含 `..`/绝对路径/`skills/` 前缀 |
| 503 | `sableos.author.model` 未配置；上游模型调用失败 |

**provider 沿用**：若目标 Agent 已存在，输出 `AGENT.md` 的 `provider.name` 强制改写成该 Agent 现有 provider（保证生成结果仍可跑）。

### 2.11 `POST /agents/{name}/files`

**请求**
```json
{ "files": { "AGENT.md": "...", "scripts/x.py": "..." } }
```

**200 `data`**：`AgentDetailView`

**语义**：`files` 必须含 `AGENT.md`；先 `parse` 预校验（400 且不写坏目录）→ `writeAll` → `refresh`。

### 2.12 `GET /workspace/tree`

**200 `data`**：`FileNode`（根名为工作区目录名，`children` = `agents/` 与 `archive/` 两棵子树）。

### 2.13 `GET /workspace/file?path=agents/x/AGENT.md`

**200 `data`**：文件文本（String）。

**错误**：400 路径越界（不在 `agents/`、`archive/` 内）；404 文件不存在。

### 2.14 `POST /workspace/file`

**请求**：`{"path": "agents/x/AGENT.md", "content": "..."}`

**200 `data`**：`null`

**语义**
| 目标路径 | 处理 |
| --- | --- |
| `agents/<name>/AGENT.md` | 走 `AgentLifecycleService.saveFiles(name, Map.of("AGENT.md", content))`（校验 + 重注册） |
| 其它（`agents/<name>/<其它>`、`archive/**`） | 边界校验通过后直接写盘 |

**错误**：400 越界或 `AGENT.md` 非法；404 目标 Agent 未注册。

## 3. 前端调用映射

| 页面 / 动作 | 端点 |
| --- | --- |
| Agent 列表 | `GET /agents` |
| 新建 Agent | `POST /agents` |
| 详情·基本信息 | `GET /agents/{name}` |
| 详情·生成（草稿） | `POST /agents/{name}/generate-files` |
| 详情·生成（保存生效） | `POST /agents/{name}/files` |
| 详情·文件（树） | `GET /agents/{name}` 的 `files` 字段 |
| 详情·文件（读） | `GET /workspace/file?path=` |
| 详情·文件（写） | `POST /workspace/file` |
| 详情·会话（历史） | `GET /agents/{name}/session` |
| 详情·会话（发消息） | `POST /agents/{name}/session/messages` |
| 详情·记忆 | `GET /agents/{name}/memory` |
| 删除 Agent | `DELETE /agents/{name}` |
