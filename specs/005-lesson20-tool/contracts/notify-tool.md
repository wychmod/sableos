# Contract: NotifyTools——19 节登记契约的兑现（本节实现，完整验证 24 节后）

> **状态：实现契约**。19 节已把行为契约定死于 `specs/004-lesson19-notify/contracts/notify-tool.md`（渠道解析四态 + `InOrder` + 审计无旁路），本节按 clarify #1 拍板的**有序 `Map<渠道名, 通知目标>` 最小组合**落地实现与注册；完整 harness（`NotifyToolsTest`：渠道解析三断言 + `InOrder`）仍按 19 节口径 **24 节后补跑**（enforce 真实现 24 节才到），本节只登记测试目标、不落码。

## 实现形态（兑现 19 节登记，零偏离）

| 项 | 本节契约 |
| --- | --- |
| 模块/包 | `sableos-tool`，`com.sableos.tool.builtin`（课件骨架包名） |
| 类型形态 | `NotifyTools implements SableTool`（**直接实现**来源——19 契约口径"类型形态 SableTool"；非 `@Tool` 注解） |
| 构造 | `NotifyTools(Sandbox sandbox, NotifyChannelAdapter adapter, Map<String, NotifyTarget> channels)`；channels 经 `Collections.unmodifiableMap(new LinkedHashMap<>(channels))` **保序**（不用 `Map.copyOf`——不保证顺序，与"取第一个"语义冲突） |
| 工具名 | `notify` |
| 入参 schema | `{"type":"object","properties":{"content":{…},"channel":{…}},"required":["content"]}`——content 必传（模型侧声明 + 运行期复查双保险）；channel 可选 = 全局注册表渠道名 |
| 出参 | 成功 `ToolResult.success("已推送")`；解析类失败 `ToolResult.failure(点名原因, false)` |
| 重名防护 | 注册进 `ToolRegistry` 时由注册表统一拒绝重名（本类不管） |

## 解析与发送契约（严沿 19 节，逐条）

执行顺序（顺序即安全不变量）：

```text
① content 缺失/空白          → failure("notify 缺少必填参数 content", false)，零请求
② ProfileContext.current()==null → failure("当前无 Agent 上下文…", false)，零请求
③ channel 解析（下表）        → 未命中/空表 → failure 点名，零请求
④ sandbox.enforce(SandboxAction(HTTP_REQUEST, target.config().get("url")))
⑤ adapter.send(target, content)     ← 发送失败由适配器上抛（19 节口径）
⑥ return success("已推送")
```

| 入参 channel | 解析规则 | 结果 |
| --- | --- | --- |
| 缺省 / 空白 / 字面量 `default` | **哨兵语义优先于同名匹配** → 取 Map **第一个**条目 | 命中即发送；Map 为空 → 失败点名"无已注册渠道"、零请求 |
| 非空且非 `default` | 按名查找 channels | 命中即发送；**不存在 → 失败点名、不回退默认**、零请求 |

- 上下文检查走 `ProfileContext.current()` **静态调用**（该类 only-static，无实例可注入——19 契约表述的落地形态）。
- 渠道命中但 config 缺 `url`：由适配器快速失败兜底（19 节第一批已钉：IAE 含 `url`、零请求）；本类不重复校验。
- **顺序口径**：解析（含空表/未命中判断）→ `enforce` → `send`；`enforce` 早于 `send` 被调用是安全不变量（顺序反了就是漏洞）——`InOrder` 钉死归 24 节后。
- 审计：不建旁路——成败经 `ToolExecutor` 既有路径落 `tool_invocations`（19 节契约 §审计）。

## 本节装配（空表）与后续节接口

- `ToolConfiguration` 装配：`registry.register(new NotifyTools(sandbox, new WebhookNotifyAdapter(RestClient), Map.of()))`——**空表**：调用即失败点名"无已注册渠道"、零请求（不造假渠道）。
- 渠道注册表本体（`notify_channels` 表 + CRUD）留后续节落地；**届时不改 `NotifyTools`**（构造接入点即 Map，载体变化不影响本类）。

## 登记（本节不落码，完成时点 24 节之后）

| 测试方法（英文） | @DisplayName（19 节课件原文） | 守住的验收项 |
| --- | --- | --- |
| `missingChannelConfigFailsLoud` | 渠道未配置 → 明确报错 | 空表/上下文缺失：`failure` 点名 + 零请求 |
| `channelDefaultsToFirstRegistered` | channel 参数缺省 → 取第一个渠道 | 缺省/空白/`default` 三形态同口径送往第一个渠道 |
| `enforceIsCalledBeforeSend` | 发送前必须先过白名单校验 | `InOrder.verify(sandbox).enforce(argThat(a -> a.type()==ActionType.HTTP_REQUEST))` 先于 `InOrder.verify(adapter).send(any(), eq("hello"))` |

测试类：`sableos-tool/src/test/java/com/sableos/tool/builtin/NotifyToolsTest.java`（**登记名，本节不建文件**）；mock `Sandbox` + `NotifyChannelAdapter`。
