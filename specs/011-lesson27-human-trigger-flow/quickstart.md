# Quickstart: 无 key 走通人推全链路

**Feature**: `011-lesson27-human-trigger-flow`

## 1. 一条命令跑门禁内 harness

```bash
mvn clean verify
```

其中两个无 key 全链路测试自动执行：

- `MockProviderFlowTest`（手工装配，秒级）
- `MockAgentE2ETest`（真端口 + 临时 SQLite）

## 2. 手动起服务，用 mock 走一遍

```bash
# 工作区（也可以用 -Dsableos.root 指到别处）
mkdir -p .sableos/agents/mock-agent
cat > .sableos/agents/mock-agent/AGENT.md <<'EOF'
---
name: mock-agent
description: 27 节无 key 全链路自测
provider:
  name: mock
  model: mock-model
tools:
  - save_memory
---

你是 mock-agent，只用于验证 SableOS 全链路。
EOF

# 用环境变量登记 mock provider（不必改 yaml）
export SABLEOS_PROVIDERS_0_NAME=mock
export SABLEOS_PROVIDERS_0_TYPE=mock

mvn clean package -DskipTests
java -jar sableos-boot/target/sableos-boot-*.jar serve
```

## 3. 对账

```bash
B=http://localhost:8080/api/v1

# 建会话 -> data.id = web:anonymous:mock-agent
curl -s -X POST $B/sessions -H 'Content-Type: application/json' -d '{"profile":"mock-agent"}'

# 发一条"记住…"（含中文建议先写入 UTF-8 文件再 --data-binary @file）
curl -s -X POST $B/sessions/web:anonymous:mock-agent/messages \
  -H 'Content-Type: application/json' -d '{"content":"记住：我喜欢喝美式咖啡"}'

# 会话历史：恰好 4 条 user/assistant/tool/assistant
curl -s $B/sessions/web:anonymous:mock-agent

# 列表：该会话出现，messageCount=4，且没有 messages 字段
curl -s $B/sessions

# 记忆：含"美式咖啡"
curl -s "$B/memory?profile=mock-agent"
```

确认三张表不多不少：`sessions` 1 条、`llm_calls` 2 条（全成功）、`tool_invocations` 1 条 `save_memory`（成功）。SQLite 文件在 `sableos.root` 指向的工作区里（默认 `.sableos/sableos.db`）。

## 4. 管理台

浏览器打开 `http://localhost:8080/admin/sessions`，「会话」页应列出刚才那条。注意：

- 管理台是 Vue 构建产物，**改前端后必须重跑** `cd sableos-web/src/main/frontend && npm ci && npm run build`，`mvn package` 不会重建它。
- 首次打开建议硬刷新。

## 5. 真模型（可选）

把 `profile` 换成使用真实 provider 的 Agent（需配好 api-key），问"今天北京天气怎么样，穿什么合适"，应产生 2 条模型调用 + 1 条 `http_get`。

## 6. 真 key 集成验收（可选）

```bash
DEEPSEEK_API_KEY=xxx mvn -pl sableos-boot -am test \
  -DexcludedGroups= -Dgroups=integration -Dtest=HumanTriggerFlowIT
```

无 key 时用例以 `assumeTrue` 跳过，不会失败。
