# Quickstart: 定时任务子系统与通知渠道注册表

**Feature**: `specs/012-lesson28-scheduler-subsystem` | **Date**: 2026-10-31

> 本文是验证指南，不是实现说明。契约细节见 `contracts/`，表结构见 `data-model.md`。

## 1. 前置条件

- JDK 21、Maven、Node.js / npm（构建管理台）。
- 工作区可用 `-Dsableos.root=<临时目录>` 覆盖；默认 `.sableos/`。
- 无 key 路径用 `mock` provider；真链路（`SchedulerFlowIT`）需要 `DEEPSEEK_API_KEY`。
- 白名单：定时链路要访问的天气源 / webhook 域名必须进 `http.allowed_domains`；本地测试用 `127.0.0.1`。

## 2. 全量门禁

```bash
mvn -B clean verify
```

预期：`BUILD SUCCESS`；Spotless / Checkstyle / P3C / SpotBugs / FindSecBugs 全绿；`ScheduledTaskE2ETest` 与 `MultiAgentIsolationTest` 在门禁内跑通；`@Tag("integration")` 默认被排除。

只跑本节相关测试：

```bash
mvn -B -pl sableos-core,sableos-storage,sableos-tool,sableos-web -am test \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl sableos-boot -am test -Dtest=ScheduledTaskE2ETest,MultiAgentIsolationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

## 3. 管理台构建

```bash
cd sableos-web/src/main/frontend
npm ci
npm run build
```

预期：`vue-tsc` 类型检查通过、Vite 构建成功；产物写入 `sableos-web/src/main/resources/static/admin/`。**`mvn package` 不会自动重建前端**，改完前端必须手工执行这两条。

## 4. 无 key 场景：登记 → 立即执行 → 停用

1. 准备临时工作区，写一个 `provider: mock` 且带 `schedules` 的 Agent（cron 设远期，靠立即执行触发）：

   ```text
   <workspace>/agents/demo-agent/AGENT.md
   ---
   name: demo-agent
   provider:
     name: mock
     model: mock-model
   tools:
     - save_memory
   schedules:
     - id: daily-demo
       cron: "0 0 8 * * *"
       zone: Asia/Shanghai
       message: "记住：定时任务已执行"
   ---
   你是 demo-agent。
   ```

2. 启动整机（测试里由 `ScheduledTaskE2ETest` 自动完成；手工可用）：

   ```bash
   java -Dsableos.root=<workspace> -jar sableos-boot/target/sableos-boot-*.jar serve
   ```

3. 查任务列表：

   ```bash
   curl -s localhost:8080/api/v2/schedules | jq
   ```

   预期：`data` 中恰有一条 `profileName=demo-agent`、`scheduleKey=daily-demo`、`enabled=true`、`runCount=0`、`nextRunAt` 在未来。

4. 立即执行：

   ```bash
   curl -s -X POST localhost:8080/api/v2/schedules/1/run | jq
   ```

   预期：返回一条 `success=true` 的执行记录；再查列表 `runCount=1`、`lastStatus=success`；查历史：

   ```bash
   curl -s localhost:8080/api/v2/schedules/1/executions | jq
   ```

5. 停用与恢复：

   ```bash
   curl -s -X PUT localhost:8080/api/v2/schedules/1 \
     -H 'Content-Type: application/json' -d '{"enabled":false}' | jq
   curl -s -X PUT localhost:8080/api/v2/schedules/1 \
     -H 'Content-Type: application/json' -d '{"enabled":true}' | jq
   ```

   预期：列表状态随之变化；停用期间到点不执行、不新增历史，但立即执行仍可补跑。

6. 旧版兼容：

   ```bash
   curl -s localhost:8080/api/v1/schedules | jq
   curl -s -X POST localhost:8080/api/v1/schedules/daily-demo/run | jq
   ```

   预期：与 v2 同一份数据；若两个 Agent 都声明 `daily-demo`，按 key 的执行/启停返回 409，列表仍返回两条。

## 5. 通知渠道 CRUD 与 notify

1. 起一个本地 webhook 接收端（任何能收 POST 的服务即可）。
2. 登记渠道（域名必须在 `http.allowed_domains` 内）：

   ```bash
   curl -s -X POST localhost:8080/api/v1/notify-channels \
     -H 'Content-Type: application/json' \
     -d '{"name":"test-webhook","type":"webhook","url":"http://127.0.0.1:9999/hook","description":"本地测试"}' | jq
   ```

3. 让 Agent 执行一次会调用 `notify` 的对话/定时任务；预期 webhook 收到 `{"content":"..."}`，`tool_invocations` 留一条 `notify` 成功记录。
4. 把渠道 `url` 改到白名单外域名（如 `http://evil.example.com/hook`）后再次执行；预期 `tool_invocations` 留一条失败记录、错误原因可读、调度器仍能执行下一次任务。
5. 删除渠道：

   ```bash
   curl -s -X DELETE localhost:8080/api/v1/notify-channels/test-webhook | jq
   ```

## 6. 真 key 链路（手动）

```bash
DEEPSEEK_API_KEY=xxx mvn -B -pl sableos-boot -am test \
  -DexcludedGroups= -Dgroups=integration -Dtest=SchedulerFlowIT \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期（有 key 时）：

- `sessions` 中 `scheduler:scheduler:<profile>` 恰 1 条，连续触发两次仍 1 条且历史变长；
- `llm_calls` 恰 2 条、`tool_invocations` 恰 2 条（`http_get` + `notify`）且全成功；
- 测试 webhook 收到消息；
- 失败路径：渠道域名不在白名单 → `tool_invocations` 留 `success=false`，调度器仍能执行下一次。

无 key 时该测试以 `assumeTrue` 跳过，不算失败。

## 7. 重启恢复（手动 + IT）

```bash
mvn -B -pl sableos-boot -am test \
  -DexcludedGroups= -Dgroups=integration -Dtest=RestartRecoveryIT \
  -Dsurefire.failIfNoSpecifiedTests=false
```

IT 内用同一工作区先后启动两个上下文，断言会话、记忆、任务状态与历史、审计记录可读回。

进程级人工验收：

1. 跑几轮对话、攒一条记忆、触发一次定时任务；
2. `kill` 掉进程，重新 `serve`；
3. 依次查 `GET /api/v1/sessions/{id}`、`GET /api/v1/memory`、`GET /api/v2/schedules`、`GET /api/v2/schedules/{id}/executions`；
4. 预期四样都在，`runCount` / 上次结果 / 下次触发与重启前一致，`llm_calls` 跨重启连续。

## 8. 多 Agent 隔离（门禁内）

```bash
mvn -B -pl sableos-boot -am test -Dtest=MultiAgentIsolationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：

- A（只文件工具）的可用工具清单不含 B 独有的 HTTP 工具，反之亦然；
- A、B 的会话标识不同、互不覆盖；
- A 的定时任务失败后，B 的下一次触发仍正常执行并记账。

## 9. 人工验收清单

- [ ] 管理台 `/admin/schedules` 能列出任务，立即执行 / 启停 / 历史按钮可用；
- [ ] 停用后到点不跑、不新增历史；重新启用后恢复；
- [ ] 真 webhook 收到定时任务推送；
- [ ] 白名单外域名被拦，`tool_invocations` 留失败记录，调度器不死；
- [ ] `kill` 后重启，会话 / 记忆 / 定时状态与历史 / 审计四样都在；
- [ ] 两个 Agent 并存时工具 / 会话 / 定时三条边界都验过；
- [ ] 外部 MCP server 连不上时，进程照常启动，定时任务照常登记。
