# Quickstart: Web Service 验证指南

**Feature**: `010-lesson26-web-service` | **Date**: 2026-10-29

本文只列“怎么跑、看到什么算对”。接口字段以 [contracts/rest-api.md](./contracts/rest-api.md) 为准。

## 前置

- JDK 21、Maven、仓库根目录已执行过 `git config core.hooksPath .githooks`（可选）。
- 至少一个 Provider key（仅“发真实消息”与“真模型联调”需要；其余端点无 key 可跑）。
- 工作区由 `sableos init` 创建（`.sableos/`），或直接使用仓库根目录的既有 `.sableos/`。

## 1. 自动化验收（默认，无需网络 / 模型）

```bash
mvn spotless:apply
mvn clean verify
```

预期：

- `sableos-web`：`SessionApiControllerTest`、`AgentCallExecutorTest`、`GlobalExceptionHandlerTest` 全绿。
- `sableos-provider`：`HttpProviderHealthProbeTest` 全绿（本地 HttpServer，不碰外网）。
- `sableos-boot`：`WebSmokeIT` 全绿（真实装配 + 临时 SQLite + MockMvc；`ProviderSmokeIT` 因 `@Tag("integration")` 被跳过）。
- 全量 Spotless / Checkstyle / P3C / SpotBugs 通过。

只跑本节相关测试：

```bash
mvn -pl sableos-web,sableos-provider,sableos-boot -am test \
  -Dtest='SessionApiControllerTest,AgentCallExecutorTest,GlobalExceptionHandlerTest,HttpProviderHealthProbeTest,WebSmokeIT'
```

## 2. 启动服务

```bash
# 仅启动服务（不配置 provider 也能起，查询类端点可用）
mvn -q -pl sableos-boot -am package -DskipTests
java -jar sableos-boot/target/sableos-boot-*.jar serve

# 或开发态
mvn -q -pl sableos-boot -am package -DskipTests
java -jar sableos-boot/target/sableos-boot-*.jar serve
```

预期：监听 `8080`，控制台不再打印“完整实现归第 26 节”的占位提示。

## 3. 只读端点（无 key 可验证）

```bash
curl -s localhost:8080/api/v1/health
curl -s localhost:8080/api/v1/info
curl -s localhost:8080/api/v1/profiles
curl -s localhost:8080/api/v1/tools
curl -s 'localhost:8080/api/v1/memory?profile=default'
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/v3/api-docs
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/swagger-ui/index.html
```

预期：

- 前 5 个都返回 `code: 0` 的统一信封；`/tools` 非空；`/profiles` 可能为空数组。
- `/info.data.providers` 与 `sableos.providers` 清单一一对应；未配 provider 时为空数组。
- `/v3/api-docs` 与 Swagger UI 均返回 `200`。

## 4. 会话链路（需要至少一个可用 Agent 与 provider key）

```bash
# 建会话（profile 换成你的 Agent 名）
curl -s -X POST localhost:8080/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"profile":"default","userId":"alice"}'

# 发消息（把 <id> 换成上一步返回的 data.id）
curl -s -X POST localhost:8080/api/v1/sessions/<id>/messages \
  -H 'Content-Type: application/json' \
  -d '{"content":"今天北京天气怎么样，穿什么合适"}'

# 查历史
curl -s localhost:8080/api/v1/sessions/<id>

# 归档
curl -s -X DELETE localhost:8080/api/v1/sessions/<id>

# 归档后仍可查历史
curl -s localhost:8080/api/v1/sessions/<id>
```

预期：

- 建会话两次（同 `profile`/`userId`）拿到同一个 `id`。
- 发消息返回 `data.reply` 非空；查历史能看到 USER / ASSISTANT / TOOL 条目。
- 归档后 `status = archived`，再次查询仍有历史；再次 DELETE 仍返回 `200`。

## 5. 无状态调用

```bash
curl -s -X POST localhost:8080/api/v1/agents/default/invoke \
  -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍你自己"}'
```

预期：返回 `data.reply`；连续两次调用的会话标识不同（历史不串）。

## 6. 错误路径

```bash
# 32KB 超长 → 400
python - <<'PY'
import json,urllib.request
body=json.dumps({"content":"x"*(32*1024+1)}).encode()
req=urllib.request.Request("http://localhost:8080/api/v1/sessions/s-1/messages",data=body,headers={"Content-Type":"application/json"})
try:
    urllib.request.urlopen(req)
except urllib.error.HTTPError as e:
    print(e.code, e.read().decode())
PY

# 不存在的会话 → 404
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/sessions/does-not-exist
```

预期：400 / 404，响应体均为统一信封，`data = null`。

## 7. 人工联调项（harness 覆盖不到）

- 10 个端点用真实模型逐条走通，确认审计表 `llm_calls` / `tool_invocations` 有对应记录。
- CLI 聊过的会话能被 `GET /api/v1/sessions/{id}` 查到（两个入口共享存储）。
- 断开 Provider 网络后发消息 → 503，响应不含连接串 / 密钥。
- 构造超过 60 秒的调用 → 504；期间 `/health` 仍可响应。
- 200 并发调用 `POST /agents/{name}/invoke`，无请求线程饥饿，`/health` 全程可响应。
- Swagger UI 页面可打开，10 个端点与请求/响应形状齐全。
- CORS：从任意源的浏览器页面发起 `POST /api/v1/sessions`，预检与请求均成功。
