# Quickstart: 第 19 节验收向导（Notify 出站通知）

> 运行/验证指南。实现细节见 [plan.md](./plan.md)、[research.md](./research.md)、[data-model.md](./data-model.md)、[contracts/](./contracts/)。
> 完成的定义：`mvn clean verify` 全绿 + 本节 harness 判卷 + 人工项过。

## 前置

- JDK 21、Maven 3.9+（enforcer 钉死）；本地仓库 `D:\maven\Repository`。
- **零网络可跑**：harness 假 webhook 是 JDK 内置 `HttpServer`（本地随机端口、真 HTTP、不出网）；无任何外部依赖、无 key。
- `sableos-tool` 类路径 = `sableos-core`（传递 `jackson-databind`）+ `spring-web`（Boot BOM 6.2.19）；测试框架来自父 POM 自动注入的 `spring-boot-starter-test`。

## 自动化 harness（判卷，mvn test 全绿即通过）

单测类在 **sableos-tool**，**零 Spring 上下文**（`new WebhookNotifyAdapter(RestClient.create())` 直连假 webhook）：

| 测试类 | 覆盖 | 关键回归点（课件原文进 `@DisplayName`，方法名英文） |
| --- | --- | --- |
| `WebhookNotifyAdapterTest` | FR-002、SC-001/002/003 | ①`sendPostsJsonBodyContainingContent`（发送后收到一次 POST，body 里带 content）②`urlComesFromTargetConfigNotHardcoded`（换通知目标只改配置零代码）③`serverErrorPropagatesAsException`（webhook 返回 5xx 异常向上抛、不静默吞掉）④`missingUrlFailsFastWithoutRequest`（缺 url：报错点名且零请求发出） |

运行：

```bash
# 只跑本节 harness
mvn -q -pl sableos-tool -am test -Dtest=WebhookNotifyAdapterTest

# 前序回归（16/17/18 节，跨节契约证据）
mvn -q -pl sableos-core,sableos-provider,sableos-storage,sableos-cli,sableos-channel-cli,sableos-boot test

# 全量门禁（完成的定义）
mvn -q -B clean verify
```

预期：全绿；4 个测试方法全过；`mvn clean verify` 含 Spotless/Checkstyle/P3C/SpotBugs+FindSecBugs 零告警。

## 第二批 harness（登记——24 节后补跑，本轮不实现）

`NotifyToolsTest`（渠道解析三断言 + `InOrder` enforce 先于 send 的课件示例）与 `NotifyTools` 实现同批，完成时点 24 节之后（见 [contracts/notify-tool.md](./contracts/notify-tool.md)）。27/28 节串联时全量验证。

## 人工确认清单（课件"五、做完怎么验"）

- [ ] **接口中立性自查（本轮可做）**：换成企业微信官方 SDK 的实现，`NotifyChannelAdapter.send(NotifyTarget, String)` 签名需要改吗？——不需要（SC-005）。
- [ ] **真 webhook 验证配置（顺延至 24 节后）**：`notify` 调用真把消息送到配置好的**真实** webhook、群里收到——依赖 `NotifyTools` 接线（`@Tool` 注册 20 节、`Sandbox.enforce` 24 节），本轮不可独立执行；假 webhook 测的是协议、真 webhook 验的是配置。
- [ ] 渠道未配置报错、白名单先行、回归不破——第二批 harness 覆盖（24 节后），`mvn test` 绿即打勾。

## 全局不变量自查（收尾 H4）

- ①涉外 IO 过白名单校验位：本节适配器是纯投递层（不自行发明校验）；`Sandbox.enforce` 的**调用位在 `NotifyTools`**（登记契约：resolve → enforce → send，enforce 先于 send），24 节接线——本节不出现 Sandbox 类型、不加自定义校验。
- ②审计两态：本节实现尚不是 Tool（未接入 ToolExecutor）；接入后成败走既有 `tool_invocations` 路径（登记契约），不建旁路、不改表。
- ③`grep` 无明文 key：测试目标是 `http://127.0.0.1:<port>` 假服务，零凭证；生产代码不落任何地址。
- ④`session_id`/`trace_id`：本节不触碰会话与链路标识。
- ⑤无 Reactor/`CompletableFuture`/自建线程池：`RestClient` 同步阻塞调用；测试假 webhook 用 JDK `HttpServer` 缺省调度（不 `setExecutor` 自建池）。
- ⑥无 Spring AI 自动工具执行路径：本节不引 Spring AI 类型。
- AGENTS.md §5.1/5.2 特有项：不新增 `TaskDecorator`、不用 `UUID.randomUUID()`、无 `synchronized` 阻塞调用、无用户可控字符串入日志（本类不打印 url/config）。
