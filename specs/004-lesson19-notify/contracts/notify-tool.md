# Contract: notify 内置工具（NotifyTools，登记——本轮不实现，完成时点 24 节之后）

> **状态：登记契约**。按课件「实现顺序说明」（授课顺序 ≠ 构建顺序）：`NotifyTools` 的完整接线依赖 20 节（`@Tool` 注册机制 / `SableTool` 形态归 ToolRegistry 收编）与 23/24 节（`Sandbox.enforce` 真实现），本节先把契约定死，24 节后落地、随第二批 harness 判卷（27/28 节串联全量验证）。
> 渠道模型：**全局注册表按名引用**（技术方案 §6.8 / AGENTS.md §6，clarify #1 拍板）——课件骨架中 `ProfileContext.resolveNotifyChannel(...)` 与 Profile `notify_channels` 字段为过时漂移，本契约按注册表模型改写**语义等价**的解析规则；`ProfileContext` 在本工具中的角色仅剩"当前 Agent 上下文存在性"检查。

## 工具形态

| 项 | 契约 |
| --- | --- |
| 模块/包 | `sableos-tool`，`com.sableos.tool.builtin`（课件骨架包名） |
| 类型形态 | `SableTool`（core 既有统一抽象：`getName`/`getDescription`/`getInputSchema`/`execute(JsonNode)→ToolResult`）；`@Tool` 注解注册归 20 节 ToolRegistry 收编 |
| 工具名 | `notify` |
| 入参 schema | `{"type":"object","properties":{"content":{"type":"string",...},"channel":{"type":"string",...}},"required":["content"]}` —— **content 必传**（模型侧声明 + 运行期复查双保险）；`channel` 可选，语义为**全局注册表渠道名** |
| 出参 | `ToolResult`：成功 `ToolResult.success("已推送")`；失败 `ToolResult.failure(点名原因, false)` |
| 装配 | 归 20 节（`ToolRegistry` 统一收编）；构造注入 `Sandbox`（24 节真实现）、`NotifyChannelAdapter`（本节 `WebhookNotifyAdapter`）、`ProfileContext` |

## 渠道解析契约（FR-003 / SC-004，第二批 harness 钉死）

解析来源：**全局通知渠道注册表**（语义：有序列表 + 按名查找；载体——`notify_channels` 表 + `/api/v1/notify-channels` CRUD——随后续节落地，本节不实现）。解析顺序与结果：

| 入参 channel | 解析规则 | 结果 |
| --- | --- | --- |
| 缺省 / 空白 / 字面量 `default` | 哨兵语义（优先于同名匹配）→ 取注册表**第一个**渠道 | 命中则发送；注册表为空 → **明确报错、零请求** |
| 非空且非 `default`（渠道名） | 按名查找注册表 | 命中则发送；**不存在 → 明确报错点名、不回退默认渠道**（回退会把消息发错地方）、零请求 |
| 任意 | 当前 **Agent 上下文缺失**（工具在处理链路外被调用） | **明确报错、零请求**（无归属上下文不允许发送） |

其余入参守卫：

- `content` 缺失/空：`ToolResult.failure("notify 缺少必填参数 content", false)` —— 点名缺失参数。
- 渠道命中但配置缺 `url`：由适配器快速失败兜底（第一批已钉：IAE 含 `url`、零请求）；本工具层不重复校验。

**顺序口径**：解析（含空注册表/未命中判断）→ `sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target.config().get("url")))` → `adapter.send(target, content)` → 返回成功。**校验必须先于发送**——`enforce` 早于 `send` 被调用是安全不变量，顺序反了就是漏洞（第二批 InOrder 断言钉死）。

## 审计契约（不新增逻辑）

- `notify` 被调用的成败经 `ToolExecutor` 既有路径落 `tool_invocations`（success=true/false 两态；`session_id` 同源、`trace_id` 同口径）——本工具不建旁路审计、不改表。
- 失败形态：解析类失败（未注册/无渠道/上下文缺失/缺 content）返回失败结果；发送类失败（4xx/5xx/连接失败）由适配器上抛、经 `ToolExecutor` 的 `执行异常` 路径收敛为失败结果并审计——两条路径 Agent 都看到**失败**，绝不 `success=true`。

## 第二批 Harness（登记，24 节后补跑）

| 测试方法（英文） | @DisplayName（课件原文） | 守住的验收项 |
| --- | --- | --- |
| `missingChannelConfigFailsLoud` | 渠道未配置 → 明确报错 | 无已注册渠道/上下文缺失：`ToolResult.failure` 点名原因 + 零请求 |
| `channelDefaultsToFirstRegistered` | channel 参数缺省 → 取第一个渠道 | 缺省/空白/`default` 三形态同口径送往第一个渠道 |
| `enforceIsCalledBeforeSend` | 发送前必须先过白名单校验 | `InOrder.verify(sandbox).enforce(argThat(a -> a.type() == ActionType.HTTP_REQUEST))` 先于 `inOrder.verify(adapter).send(any(), eq("hello"))`——课件示例 `notify("hello", "default")` |

测试类：`sableos-tool/src/test/java/com/sableos/tool/builtin/NotifyToolsTest.java`（登记名）；mock `Sandbox` + `NotifyChannelAdapter`（Mockito 父 POM 已注入）；注册表用测试桩（或 20 节落地的查询契约的 mock）。

## 边界（本契约不做）

- 注册表本体（存储表、Web CRUD、管理台）随后续节——本工具只依赖「有序 + 按名查找」语义。
- 富文本/卡片/签名——走扩展阶段专用 Adapter 或 MCP（§6.8 两条路并存）。
- 本节仅登记契约；实现与二批 harness 的落点在 24 节之后。
