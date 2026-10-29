# Quickstart: 定时任务调度（第 25 节）

**Feature**: `specs/009-lesson25-scheduler`

## 1. 构建与测试

```bash
# 全量门禁（格式 / P3C / Checkstyle / SpotBugs / 单测；本地默认跳过 OWASP）
mvn clean verify

# 只跑本节单元测试
mvn -pl sableos-core test -Dtest=AgentSchedulerTest
```

预期：`AgentSchedulerTest` 全部通过；`mvn clean verify` 全绿。

## 2. 手动验证：真实到点触发一次

1. 在 `.sableos/agents/demo/AGENT.md` 的 frontmatter 增加一条「每分钟」的定时配置（cron 六段含秒）：

   ```yaml
   schedules:
     - id: demo-every-minute
       cron: "0 * * * * *"
       zone: "Asia/Shanghai"
       message: "报告当前时间，并说明这是定时触发。"
   ```

2. 启动常驻模式（定时任务只在 `serve` / `gateway` 下调度）：

   ```bash
   java -jar sableos-boot/target/sableos-boot-0.1.0-SNAPSHOT.jar serve
   ```

3. 观察：
   - 日志出现 `定时任务` 相关记录，每次触发一条独立 `traceId`；
   - 到点后 Agent 自动发起对话（模型返回内容打印/记录），人未输入任何消息；
   - `llm_calls`、`tool_invocations` 中有对应 `session_id = scheduler:scheduler:demo`、`channel = scheduler` 的记录。

4. 改 `cron` 为 `"0 */5 * * * *"`，重启后按新时间触发——全程未改代码、未重新编译。

## 3. 手动验证：重叠与失败隔离

- **重叠**：把 Agent 的消息改成一条会跑较久的任务（例如让它执行多次工具调用），cron 设成每分钟；观察耗时超过一分钟时，后一次触发记 WARN 跳过、`llm_calls` 不出现叠加执行。
- **失败**：临时把 provider 的 key 置空或让工具白名单拒绝一次调用，观察该次触发记 error、进程不退出，下一分钟仍能正常触发。

## 4. 人工项清单（harness 判不了的部分）

- 真实到点触发一次并看到审计落库；
- 改 cron 表达式不重新编译、重启后按新时间跑；
- 端到端预演一次「到点 → ReAct → 审计」，为第 31 节定时 Demo 打底。

> 重叠跳过、失败隔离、锁释放、时区、会话身份已由 `AgentSchedulerTest` 覆盖，`mvn test` 绿即打勾。
