# Quickstart：第 30 节（动态管理 Agent）

> 目标：从零起一次服务，验证「API 建 / 丢目录 / 关页面改」三条路径都免重启生效。全程不需要真实模型 key（创建与监听走模板与文件；只有「用大模型生成」需要 key）。

## 1. 前置

```bash
# JDK 21 + Maven 3.9
java -version            # 21.x
mvn -v

# 管理台构建（只有改了前端源码才需要；产物已入库时跳过）
cd sableos-web/src/main/frontend
npm ci && npm run build
cd -
```

## 2. 准备一个干净工作区

```bash
export SABLEOS_HOME=/tmp/sableos-lesson30
rm -rf "$SABLEOS_HOME"
mkdir -p "$SABLEOS_HOME/agents"
```

用系统属性把工作区指过去（`application.yaml` 的 `workspace.root: ${sableos.root:.sableos}`）：

```bash
java -Dsableos.root="$SABLEOS_HOME" -jar sableos-boot/target/sableos-boot-*-exec.jar serve
```

> 端口默认 8080。若被占用：`--server.port=18080`。

## 3. US1 — API 建 Agent（免重启即上线）

```bash
curl -s -X POST http://localhost:8080/api/v1/agents \
  -H 'Content-Type: application/json' \
  -d '{"name":"weather-daily","description":"每天早上九点查北京天气，把穿搭建议发到团队群"}' | jq .

# 立刻可见（不重启）
curl -s http://localhost:8080/api/v1/agents | jq '.data[].name'
curl -s http://localhost:8080/api/v1/agents/weather-daily | jq '.data | {name, provider, model, files: [.files[].name]}'

# 磁盘落点
ls -R "$SABLEOS_HOME/agents/weather-daily"
```

**期望**：`GET /agents` 立刻含 `weather-daily`；目录下有 `AGENT.md`、`REFERENCE.md`、`scripts/example.py`。

**同名冲突（400，零文件新增）**：
```bash
before=$(find "$SABLEOS_HOME/agents" -type f | wc -l)
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/v1/agents \
  -H 'Content-Type: application/json' -d '{"name":"weather-daily","description":"重复"}'
after=$(find "$SABLEOS_HOME/agents" -type f | wc -l)
echo "$before -> $after"   # 必须相等
```

## 4. US2 — 手工丢目录也即上线

```bash
mkdir -p "$SABLEOS_HOME/agents/manual-agent"
cat > "$SABLEOS_HOME/agents/manual-agent/AGENT.md" <<'EOF'
---
name: manual-agent
description: 手工丢进来的 Agent
identity:
  agent_name: Manual
  prompt: 你是一个测试助手
provider:
  name: mock
  model: mock-model
settings:
  max_iterations: 5
  max_history_turns: 10
---

被触发时说一句「收到」。
EOF

# 几秒内出现（无需重启）
sleep 2
curl -s http://localhost:8080/api/v1/agents | jq '.data[].name'
```

**改正文即生效**：
```bash
sed -i 's/收到/明白了/' "$SABLEOS_HOME/agents/manual-agent/AGENT.md"
sleep 2
curl -s http://localhost:8080/api/v1/agents/manual-agent | jq -r '.data.description'
```

**删目录即下线**：
```bash
rm -rf "$SABLEOS_HOME/agents/manual-agent"
sleep 2
curl -s http://localhost:8080/api/v1/agents | jq '.data[].name'
```

## 5. US5 — 文件浏览器与穿越防护

```bash
curl -s 'http://localhost:8080/api/v1/workspace/tree' | jq '.data.children[].name'
curl -s 'http://localhost:8080/api/v1/workspace/file?path=agents/weather-daily/AGENT.md' | jq -r '.data' | head -5

# 穿越必须 400
curl -s -o /dev/null -w '%{http_code}\n' \
  'http://localhost:8080/api/v1/workspace/file?path=../../etc/passwd'   # 400
curl -s -o /dev/null -w '%{http_code}\n' \
  'http://localhost:8080/api/v1/workspace/file?path=/etc/passwd'        # 400
```

## 6. US3 — 编辑保存即生效

```bash
curl -s -X POST http://localhost:8080/api/v1/workspace/file \
  -H 'Content-Type: application/json' \
  -d '{"path":"agents/weather-daily/AGENT.md","content":"---\nname: weather-daily\ndescription: 改过的描述\nidentity:\n  agent_name: Weather\n  prompt: 你是天气助手\nprovider:\n  name: mock\n  model: mock-model\nsettings:\n  max_iterations: 5\n  max_history_turns: 10\n---\n\n你好。"}' | jq .

curl -s http://localhost:8080/api/v1/agents/weather-daily | jq -r '.data.description'   # 改过的描述
```

## 7. US3 — 固定会话

```bash
curl -s http://localhost:8080/api/v1/agents/weather-daily/session | jq '.data.id'    # admin:console:weather-daily
curl -s -X POST http://localhost:8080/api/v1/agents/weather-daily/session/messages \
  -H 'Content-Type: application/json' -d '{"content":"你好"}' | jq .data
```

> 需要 provider `mock` 已在 `sableos.providers` 里登记（见 `application.yaml` 注释）。

## 8. US4 — 记忆跟着 Agent 走

```bash
curl -s http://localhost:8080/api/v1/agents/weather-daily/memory | jq .data
# 该 Agent 跑过 save_memory 后：
cat "$SABLEOS_HOME/agents/weather-daily/MEMORY.md"
```

## 9. US3 — 删除与归档

```bash
curl -s -X DELETE http://localhost:8080/api/v1/agents/weather-daily | jq .code
ls "$SABLEOS_HOME/archive/"          # weather-daily 在这里
curl -s http://localhost:8080/api/v1/agents | jq '.data[].name'   # 已消失
```

## 10. US3 — 用大模型生成（需要 key）

```bash
# 配置 sableos.providers 与 sableos.author.*，然后：
curl -s -X POST http://localhost:8080/api/v1/agents/weather-daily/generate-files \
  -H 'Content-Type: application/json' \
  -d '{"description":"每天早上九点查北京天气并推送到团队群"}' | jq '.data.files | keys'
```

未配置 `sableos.author.model` 时期望 **503** 且 message 可读。

## 11. 管理台

浏览器打开 <http://localhost:8080/admin/agents>：

1. 点「新建 Agent」→ 填 name + description → 创建 → 列表出现
2. 点行进入详情 → 五个页签逐个打开（基本信息 / 生成 / 文件 / 会话 / 记忆）
3. 文件页改一行 → 保存 → 回到基本信息看描述已变
4. 会话页发一句话（需 mock 或真 provider）→ 气泡显示答复
5. 删除 → 二次确认 → 列表消失，`archive/` 下可找到

## 12. 回归

```bash
mvn -B clean verify
```
