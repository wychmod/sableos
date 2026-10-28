# Quickstart: 第 24 节 Sandbox 白名单校验——验收向导

**Feature**: `007-lesson24-sandbox` | 契约见 [contracts/sandbox-whitelist.md](./contracts/sandbox-whitelist.md)、规则与不变量见 [data-model.md](./data-model.md)、取舍见 [research.md](./research.md)。

## 前置

- JDK 21 + Maven 3.9+；分支 `007-lesson24-sandbox`。
- 零 key、零出网可跑全部自动化验证：白名单测试直接构造实现；通知测试用 Mockito 与假 adapter；既有工具测试用 JDK `HttpServer` 或拒绝型替身。
- 首次改动后先 `mvn spotless:apply`（格式违规直接挂构建）。
- 依赖核实结果（2026-10-27）：`sableos-tool` 经 `sableos-core` 已有 `spring-context` / `spring-beans`（6.2.19）；`spring-boot`（3.5.16）当前仅测试作用域，本节按 `sableos-provider` 先例以 BOM 管理版本引入 compile 作用域，用于 `@ConfigurationProperties`。无新增第三方构件。

## 1. 全量门禁（实现完成的定义）

```bash
mvn clean verify
```

预期：**BUILD SUCCESS**——Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / PMD / 全部单测；前序 16~22 节测试回归全绿。`ProviderAssemblyTest` 等 boot 装配测试会实际创建 `WhitelistSandbox` bean，可验证属性启用与 bean 唯一性。

## 2. 本节测试（harness 直跑）

```bash
mvn -B -pl sableos-tool -am test -Dtest='WhitelistSandboxTest,NotifyToolsTest,FileToolsTest,ShellToolsTest,HttpToolsTest'
```

预期全绿。逐个对号：

| 测试类 | 守点 |
|---|---|
| `WhitelistSandboxTest` | 文件：白名单内放行 / 外拒绝 / `..` 穿越被拦 / 相似前缀不误命中 / 相对根可匹配 / 空配置 deny-all；Shell：允许 / 拒绝 / 前导空白 / 大小写变体 / 首 token / 空配置 deny-all；HTTP：精确 / 通配子域 / 形似域不命中 / 裸域不命中 / 不可解析 / 无 host / 非 HTTP(S) / 大小写 / 空配置 deny-all |
| `NotifyToolsTest` | 无渠道明确报错；缺省渠道取第一个；真实白名单拒绝时 webhook 零发送；`InOrder` 断言 `enforce` 先于 `send` |
| `FileToolsTest` / `ShellToolsTest` / `HttpToolsTest` | 既有"白名单外被拦 + 真实 IO 零发生"用例保持绿（跨节契约） |

## 3. 只跑关键回归单测

```bash
mvn -B -pl sableos-tool -am test -Dtest=WhitelistSandboxTest#relativePathTraversal_isRejected+wildcardDomain_doesNotMatchLookalike
```

预期：两条最值钱的绕过用例通过——`..` 无法爬出白名单根；`*.example.com` 不命中 `evil-example.com`。

## 4. 装配级验证

```bash
mvn -B -pl sableos-boot -am test -Dtest=ProviderAssemblyTest
```

预期全绿：上下文升起时 `WhitelistSandbox` 作为唯一 `Sandbox` bean 被创建，三个属性记录绑定成功；若忘记 `@EnableConfigurationProperties` 或保留旧 `@Bean`，此测试会因缺 bean / bean 冲突直接失败。

## 5. 配置样例（人工链路验证前）

`sableos-boot/src/main/resources/application.yaml` 已预留三个键，默认全为 `[]`（全拒）。做真实链路验证时按需放行，例如：

```yaml
file:
  allowed_paths:
    - .sableos/agents/daily-tech-digest
    - .sableos/agents/github-daily
shell:
  allowed_commands:
    - python
http:
  allowed_domains:
    - api.open-meteo.com
    - "*.feishu.cn"
```

注意：

- 相对路径根会按 JVM 当前工作目录解析；从仓库根启动时 `.sableos/...` 指向仓库内工作区。
- 空列表 = 该类**全部拒绝**，不是"不校验"。
- `smtp.allowed_endpoints` 保留但核心阶段不读取，邮件通知属扩展阶段。

## 6. 人工项（harness 判卷之外）

| 项 | 做法 | 预期 |
|---|---|---|
| 真实链路拦截 | 配一个只允许 `ls` 的 Shell 白名单，经 `sableos chat` 让 Agent 执行白名单外命令 | 链路上抛出 `SandboxViolationException`；回答里能看到拒绝原因 |
| 审计落库目检 | 上一步后查 `.sableos/sableos.db` 的 `tool_invocations`（如 `sqlite3 .sableos/sableos.db "select tool_name, success, error_message from tool_invocations order by id desc limit 3;"`） | `success=0`，`error_message` 含"命令不在白名单内: <命令>"且人能读懂 |
| 通知真实推送 | 配真 webhook 渠道并把域名加入 `http.allowed_domains`，经 `sableos chat` 让 Agent 推送 | webhook 所在群真实收到消息；域名未放行时零请求 |
| 接口中立性自查 | 脑中用容器/microVM 实现套 `Sandbox.enforce(SandboxAction)` | 不需要新增方法即可实现 |
| 配置边界说明 | 检查 `application.yaml` 注释 | 已写明"空列表 = 该类全部拒绝" |

## 7. H4 全局不变量自查（grep 级）

| 检查 | 命令/口径 | 预期 |
|---|---|---|
| 涉外 IO 首行过校验 | `grep -rn "enforce(" sableos-tool/src/main/java` | 文件 / Shell / HTTP / 通知均在真实动作前 |
| 通知顺序 | `grep -n "enforce\|adapter.send" sableos-tool/src/main/java/com/sableos/tool/builtin/NotifyTools.java` | enforce 行号早于 send |
| 审计不建旁路 | `grep -rn "SandboxViolationException" sableos-core sableos-storage` | 仅工具侧抛出、执行层既有 catch 收敛；无沙箱专用审计代码 |
| 无明文 key | `grep -rn "sk-\|api.key\|password" sableos-tool/src/main/java`（人工判读） | 无硬编码凭证 |
| 无 Reactor / CompletableFuture / 自建线程池 | `grep -rn "CompletableFuture\|reactor\|Executors.new" sableos-tool/src/main` | 仅既有 Shell 虚拟线程执行器 |
| 无 `synchronized` | `grep -rn "synchronized" sableos-tool/src/main` | 无新增 |
| 四值未改 | `grep -n "FILE_READ\|FILE_WRITE\|SHELL_COMMAND\|HTTP_REQUEST" sableos-tool/src/main/java/com/sableos/tool/sandbox/ActionType.java` | 恰好四值 |
| 零清单外对外概念 | 对照 [spec.md](./spec.md) 与契约逐类型核对 | 新增 public 类型仅 `WhitelistSandbox` + 三个属性记录 |

## 8. 完成后（用户决定）

- 不自动 commit/push/package.sh；变更总结在验收报告中输出后停止，由用户决定同步时机。
