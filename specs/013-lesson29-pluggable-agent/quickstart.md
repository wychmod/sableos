# Quickstart: 插件化 Agent

**Feature**: `specs/013-lesson29-pluggable-agent` | **Date**: 2026-10-31

> 本文是验证指南，不是实现说明。契约细节见 `contracts/`，类型清单见 `data-model.md`。

## 1. 前置条件

- JDK 21、Maven 3.9+
- 无需 API key（本节全部 harness 用临时目录 + 假 provider 名单驱动，不调模型）

## 2. 全量门禁

```bash
mvn -B clean verify
```

预期：`BUILD SUCCESS`；Spotless / Checkstyle / P3C / SpotBugs / FindSecBugs 全绿。

## 3. 只跑本节 harness

```bash
mvn -B -pl sableos-core -am test \
  -Dtest='AgentLoaderTest,DeriveProfileTest,AgentScanRegisterTest,ProfileRegistryRuntimeTest,AgentSchedulerRegisterTest,ProgressiveDisclosureTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期 6 个测试类全部通过。

## 4. 手动验证：丢一个目录就多一个 Agent

1. 在任意工作区下准备：

   ```bash
   W=$(mktemp -d)
   mkdir -p "$W/agents/daily-reconcile"/{skills,scripts} "$W/skills/report-format"
   ```

2. 写 `AGENT.md`（frontmatter 是运行配置，正文是任务指令；**不含 `notify_channels`**）：

   ```markdown
   ---
   name: daily-reconcile
   description: 每天核对交易库与清算库当日订单的条数与金额是否一致，有差异就按规范生成分级报告并推送
   identity:
     agent_name: 对账小欧
     prompt: 你是一个严谨的对账助手，只根据脚本给出的确定性数据下结论，绝不臆测数字。
   provider:
     name: deepseek
     model: deepseek-chat
     temperature: 0.2
   tools: [shell, read_file, notify, save_memory]
   schedules:
     - {id: reconcile-morning, cron: "0 0 9 * * *", zone: Asia/Shanghai, message: 到点了，核对昨天的订单对账。}
   ---

   你是每日订单对账助手。被触发时严格按顺序做，不要跳步：
   1. 运行 `python scripts/reconcile.py` 拿数据，只依据它下结论。
   2. diffs 为空且条数、金额都相等 → 调 notify 向 ops-webhook 渠道发「对账通过」并结束。
   3. 否则读 `skills/report-format/SKILL.md` 按规范组织分级报告。
   4. 调 notify 推送报告到 ops-webhook；调 save_memory 记一笔结论。
   ```

3. 把子指令绑定成软连接（`skills/` 只认软连接，放普通 `.md` 会让整个 Agent 启动失败）：

   ```bash
   ln -s "$W/skills/report-format" "$W/agents/daily-reconcile/skills/report-format"
   # Windows（管理员或已开启开发者模式）：
   # mklink /D "%W%\agents\daily-reconcile\skills\report-format" "%W%\skills\report-format"
   ```

   规范正文照抄 `sableos-core/src/test/resources/skills/report-format/SKILL.md`；脚本与参考资料同样照抄 `sableos-core/src/test/resources/agents/daily-reconcile/` 下的 `scripts/reconcile.py` 与 `REFERENCE.md`。

4. 启动：

   ```bash
   java -Dsableos.root="$W" -jar sableos-boot/target/sableos-boot-*.jar serve
   ```

5. 核对：

   ```bash
   curl -s localhost:8080/api/v1/profiles | jq
   ```

   预期：`daily-reconcile` 出现在清单中，且 `schedules` 与声明一致。

## 5. 手动验证：坏 Agent 只废自己

在同一个 `agents/` 里再放两个目录：

```bash
mkdir -p "$W/agents/broken-yaml" "$W/agents/wrong-name"
printf -- '---\nname: broken-yaml\nprovider: [oops\n---\n正文\n' > "$W/agents/broken-yaml/AGENT.md"
printf -- '---\nname: not-the-dirname\nprovider:\n  name: deepseek\n  model: m\n---\n正文\n' > "$W/agents/wrong-name/AGENT.md"
```

重启后预期：
- 日志出现两条 ERROR，分别点名 `broken-yaml`（YAML 解析失败）与 `wrong-name`（name 与目录名不一致）；
- `GET /api/v1/profiles` 里 `daily-reconcile` 仍在，两个坏目录不在；
- 进程正常启动，定时照常注册。

## 6. 手动验证：资源按需加载

1. 确认 `AGENT.md` 正文进了 system prompt：触发一次对话或定时，日志里模型的输入应含「你是每日订单对账助手」。
2. 确认 `REFERENCE.md` 与 `scripts/reconcile.py` **没有**进 system prompt。
3. 确认脚本需要显式白名单：

   ```yaml
   shell:
     allowed_commands: [python]     # 默认是 []，不加则脚本调用被沙箱拒绝
   ```

   未加白名单时预期：`tool_invocations` 留一条 `shell` 失败记录，原因可读，调度器不受影响。

   > **信任边界（必须理解）**：把解释器加入 `shell.allowed_commands`，等价于把**当前系统用户的代码执行权限**交给模型——脚本可读写该用户能访问的任何文件、可发起网络请求；底座**不提供容器、文件或网络隔离**。只允许在可信单机部署中开启；不可信脚本或多租户场景要等后续的隔离执行能力。

## 7. 手动验证：改正文即时生效

改 `AGENT.md` 正文任意一句，**不重启**，再次触发——日志里模型输入应立即体现新正文（`ContextLoader` 每次现读、不缓存，17 节已钉死）。

## 8. 人工验收清单

- [ ] 往 `agents/` 放一个合法目录后，`GET /api/v1/profiles` 出现该 Agent，全程未写程序代码；
- [ ] 混放合法与非法目录时，非法者各留一条点名 ERROR，合法者全部可见，进程正常启动；
- [ ] 带 `schedules` 的 Agent 的定时已进入调度（`GET /api/v2/schedules` 能查到）；
- [ ] system prompt 只含正文，不含 `REFERENCE.md` / `scripts/` 内容；
- [ ] 改正文后不重启，下一次触发即生效；
- [ ] `skills/` 用软连接而非普通 `.md`（普通文件会让 system prompt 构造抛越界异常）。

## 9. 真模型链路（可选，人工项）

本节 harness 不依赖模型。若要验证"到点真自己跑"，需：

1. 配好 `DEEPSEEK_API_KEY` 与真实 provider；
2. 把天气源 / webhook 域名加入 `http.allowed_domains`；
3. 通过 `POST /api/v1/notify-channels` 登记 `ops-webhook`；
4. 用 `POST /api/v2/schedules/{id}/run` 立即执行一次，核对 `task_executions` 与 webhook 收到。
