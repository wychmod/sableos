# Acceptance Report: 第 24 节 Sandbox 白名单校验

**Feature**: `007-lesson24-sandbox` | **Branch**: `007-lesson24-sandbox` | **Date**: 2026-10-27

**结论**: PASS——自动化门禁全绿；剩余人工项见第 6 节。

## 1. 全量门禁（实现完成的定义）

```bash
mvn clean verify
```

实测结果（2026-10-27）：

- Reactor 10 个模块全部 `SUCCESS`（core / provider / storage / memory / tool / channel-cli / web / cli / boot + parent）；
- 测试合计 **307**，`Failures: 0, Errors: 0, Skipped: 0`；
- Spotless、Checkstyle（0 violations）、阿里 P3C（PMD 6.55）、SpotBugs + Find Security Bugs 全部通过；
- `BUILD SUCCESS`，总耗时 01:56 min。

模块测试分布：core 91、provider 21、storage 13、memory 35、tool 92、channel-cli 2、web 33、boot 20。

## 2. 验收 harness 对号

| 测试类 | 用例数 | 守住的关键回归点 |
|---|---|---|
| `WhitelistSandboxTest` | 29 | 文件：白名单内放行 / 外拒绝 / `..` 穿越被拦 / 相似前缀不误命中 / 根自身放行 / 相对根可匹配 / 读写共用 / 空白拒绝 / 空配置 deny-all；Shell：允许 / 拒绝 / 前导空白 / 大小写 / 首 token / 空白 / 空配置 deny-all；HTTP：精确 / 通配子域 / 多级子域 / 形似域不命中 / 裸域不命中 / 白名单外 / 大小写 / 带端口 / 非 HTTP(S) / 无 host / 畸形 URL / 空配置 deny-all；配置边界：null 条目跳过 |
| `SandboxViolationAuditTest` | 1 | SC-005 直证：真实白名单拒绝 → `ToolExecutor` 收敛为失败结果，审计 `success=false` 且原因含被拒目标 |
| `NotifyToolsTest` | 6 | 无渠道报错且零发送；缺 content 零校验零发送；缺 Agent 上下文零校验零发送；缺省渠道取第一个；**`InOrder` 钉死 enforce 先于 send**；真实白名单拒绝时 webhook 零发送 |
| `FileToolsTest` | 14 | 既有"白名单外路径被拦且文件零接触"回归保持绿 |
| `ShellToolsTest` | 4 | 既有"白名单外可执行文件起进程前被拦"回归保持绿 |
| `HttpToolsTest` | 4 | 既有"白名单外域名被拦且请求零发出"回归保持绿 |
| `ProviderAssemblyTest` / `ReactAssemblyTest` | 2 / 5 | 新 bean 与三个属性记录可随上下文升起；白名单配置下注册工具成功审计仍成立 |

## 3. 本节交付物存在性核对

```text
sableos-tool/src/main/java/com/sableos/tool/sandbox/
├── Sandbox.java                     （沿用 20 节，未改）
├── SandboxAction.java               （沿用 20 节，未改）
├── ActionType.java                  （沿用 20 节，四值未改）
├── SandboxViolationException.java   （沿用 20 节，未改）
├── WhitelistSandbox.java            （新增，唯一生产实现）
├── FileSandboxProperties.java       （新增）
├── ShellSandboxProperties.java      （新增）
└── HttpSandboxProperties.java       （新增）

sableos-tool/src/test/java/com/sableos/tool/
├── sandbox/WhitelistSandboxTest.java（新增）
├── sandbox/SandboxViolationAuditTest.java（新增）
├── sandbox/PermissiveSandbox.java    （测试替身，仅测试源码）
└── builtin/NotifyToolsTest.java      （新增）

sableos-boot/src/main/resources/application.yaml
├── file.allowed_paths               （已存在，注释写明 deny-all）
├── shell.allowed_commands           （已存在）
└── http.allowed_domains             （已存在）
```

`grep` 实证：

```text
FileTools.java:44/60/72/92/118/142    sandbox.enforce(...)
ShellTools.java:62                    sandbox.enforce(new SandboxAction(SHELL_COMMAND, executable))
HttpTools.java:36/44                  sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))
NotifyTools.java:81                   sandbox.enforce(...)  ← 早于 :82 adapter.send
DuckDuckGoSearchProvider.java:70      sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))
```

## 4. 前序节全部测试回归绿

`mvn clean verify` 覆盖 core（16~17 节）、provider（16 节）、storage（16~18 节）、memory（22 节）、tool（19~20 节）、channel-cli（18 节）、web（地基）、boot（16~22 节装配）全部测试；305 个用例零失败。跨节契约未被破坏。

## 5. H4 全局不变量自查

| 项 | 检查 | 结果 |
|---|---|---|
| ① 涉外 IO 首行过 `Sandbox.enforce` | `grep -rn "enforce(" sableos-tool/src/main` | 文件 6 处、Shell 1 处、HTTP 2 处、通知 1 处、web_search 1 处；均在真实动作前 |
| ② 工具成败落 `tool_invocations` | `ToolExecutor` 既有 `catch (RuntimeException)` → `ToolResult.failure` → auditor | 未改逻辑；新增 `SandboxViolationAuditTest` 直证失败审计含被拒目标；`ReactAssemblyTest` 成功审计回归通过 |
| ③ 无明文 key | `grep -rn "sk-[A-Za-z0-9]\|api[_-]key\s*[:=]\|password\s*[:=]" sableos-tool/src/main sableos-boot/.../tool` | 无命中 |
| ④ `session_id` 单点拼接 | 本节未触碰 session 相关代码 | 未新增散落拼装 |
| ⑤ 无 Reactor / `CompletableFuture` / 自建线程池 | `grep -rn "CompletableFuture\|reactor\|Executors.new" sableos-tool/src/main` | 仅既有 `ShellTools` 虚拟线程执行器（允许） |
| ⑥ 无 Spring AI 自动工具执行 | `grep -rn "ToolCallbacks\|ToolCallback" sableos-tool/src/main` | 仅 `ToolRegistry.registerAnnotated` 的 schema 包装与 `AnnotatedToolAdapter` 适配层；执行仍由 `ToolExecutor` 驱动，未改 |
| AGENTS §5.2 `synchronized` | `grep -rn "synchronized" sableos-tool/src/main` | 无命中 |
| AGENTS §5.2 traceId 传播 / 唯一 TaskDecorator / `TraceIds` | 本节未触碰 trace / 线程装配 | 无新增 |
| AGENTS §5.2 Provider 显式映射 | 本节未触碰 Provider | 无改动 |
| AGENTS §5.2 用户可控字符串 CRLF 净化 | 本节不新增日志；移动后的 `PermissiveSandbox` 仍保留 `replaceAll("[\\r\\n]", "_")` 形态 | 符合 |

## 6. 剩余人工项（harness 已判卷，这几项等你人工过）

1. **真实链路拦截**：配一个只允许 `ls` 的 `shell.allowed_commands`，经 `sableos chat` 让 Agent 执行白名单外命令；确认真实链路上抛 `SandboxViolationException`，回答里能看到拒绝原因。
2. **审计落库目检**：自动化已由 `SandboxViolationAuditTest` 直证「失败审计含被拒目标」（SC-005）；真实链路仍建议查 `.sableos/sableos.db` 的 `tool_invocations`（`select tool_name, success, error_message from tool_invocations order by id desc limit 3;`），确认真库落盘同样可读。
3. **真实 webhook 推送**：配置真实通知渠道并把域名加入 `http.allowed_domains`，经 `sableos chat` 触发 notify，确认群内收到消息；域名未放行时确认零请求。
4. **接口中立性自查**（思维练习）：用容器/microVM 实现反向套 `Sandbox.enforce(SandboxAction)`，确认不需要新增方法。
5. **配置边界确认**：`application.yaml` 已写明"空列表 = 该类全部拒绝"，人工复核即可。

## 7. 取舍与偏差记录

| 项 | 处理 | 说明 |
|---|---|---|
| 技术方案 §6.7 的 `smtp.allowed_endpoints` / `checkSmtpEndpoint` | 不实现，按课件三项口径 | `ActionType` 四值无 SMTP 动作、邮件通知属扩展阶段；已记入 `AGENTS.md` §10 |
| `PermissiveSandbox` | 从 `src/main` 移到 `src/test`（同包同 FQN），文案定稿为「测试替身」 | 生产只保留唯一白名单实现；前序 5 个测试零改动 |
| `sableos-tool` 新增 `spring-boot` 依赖 | BOM 管理版本，仅用于 `@ConfigurationProperties` | 先例见 `sableos-provider/pom.xml`；非新第三方构件 |
| `ReactAssemblyTest` 成功读文件用例 | 测试文件改放入工作区临时目录，`@DynamicPropertySource` 显式放行 `file.allowed_paths[0]` | 新默认 deny-all 下必须像真实部署一样配置白名单；断言强度未降低 |
| 行为变化 | 默认 deny-all | 未配置白名单时文件 / Shell / HTTP / 通知全部拒绝；28/31 节 Demo 需按 quickstart 显式配置 |

## 8. Review 修复记录（2026-10-27 定稿）

| 编号 | 问题 | 修复 |
|---|---|---|
| I1 | `Sandbox` javadoc 指向已移入测试源码的 `PermissiveSandbox` | 改为指向生产实现 `WhitelistSandbox`，注明放行替身不在生产 classpath |
| I2 | 技术方案 §6.7 四键 / SMTP 口径漂移 | 同步为三键 + SMTP 扩展阶段预留，`AGENTS.md` §10 标记定稿 |
| D1 | 契约写 `trim()`、实现用 `strip()` | 契约 / 数据模型 / 研究 / 任务统一为 `strip()` |
| D2 | 测试替身日志仍写「24 节替换」 | 文案改为「测试替身放行」 |
| D3 | `application.yaml` 承诺 `sableos init` 生成白名单（未实现） | 删除过期承诺，改为「示例工作区不预置白名单」 |
| O1 | T002 先移走临时实现、T005 才删引用，Phase 1 检查点不可构建 | T002 改为先复制，T005 装配替换后删除主源码 |
| R1 | 属性记录 `List.copyOf` 先于跳过过滤器拒绝 null 条目 | 记录构造时过滤 null，`WhitelistSandbox` 保留空白过滤；补用例 |
| T1 | FR-007「点名被拒目标」只断言前缀 | 文件 / Shell / HTTP 三处拒绝用例补目标断言 |
| C1 | SC-005 无直接自动化证据 | 新增 `SandboxViolationAuditTest` 直证失败审计含被拒目标 |

定稿复核：`mvn -B verify` → EXIT 0 / BUILD SUCCESS，307 个用例全绿。
