# Contract: AgentService——统一编排入口

**Feature**: 002-lesson17-react | 契约类型：进程内 Java 类（`com.sableos.core.agent`）

三种触发源（CLI / Web / 定时）复用的唯一处理入口（FR-011）。

## 1. 类结构

```java
public final class AgentService {
  public AgentService(ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionStore sessionStore);
  public String process(Session session, String userMessage);
}
```

| 参数 | 语义 |
| --- | --- |
| session | 本次会话（`profileName()` 决定用哪个 Agent 的运行配置） |
| userMessage | 本条用户消息 |

**返回值**：最终答复文本（透传 `ReActLoop.run` 的结果）。

## 2. 行为规约（FR-011 / FR-012）

```text
process(session, userMessage):
    profile = profileRegistry.get(session.profileName())          # 取当前 Agent 运行配置
    if profile == null: throw IllegalArgumentException("未注册的 Agent：" + session.profileName())

    ProfileContext.set(profile)                                   # 放入线程级上下文（FR-012）
    try (TraceContext.Scope ignored = TraceContext.openIfAbsent()):
        traceContext.attachBusiness(session.id(), profile.name(), session.channel())   # MDC 业务键
        reply = reActLoop.run(session, userMessage, profile)      # 跑循环
        sessionStore.save(session)                                # 处理完保存会话（FR-015）
        return reply
    finally:
        ProfileContext.clear()                                    # 异常路径也必须清（FR-011）
```

| 规约 | 对应需求 |
| --- | --- |
| 取 Profile → 建 `ProfileContext` | FR-011 / FR-012 |
| `try` 内跑循环、循环后保存会话 | FR-011 |
| `finally` 清 `ProfileContext`（异常也清） | FR-011 / SC-005 |
| 异常时不保存会话（`save` 在 `run` 之后，异常跳过） | Edge Case「会话保存虽被跳过，但上下文仍清」 |
| 三种触发源共用 `process` | FR-011 |

## 3. ProfileContext 与 traceId 的配合

- `ProfileContext`（`ThreadLocal<Profile>`）承载「当前 Agent 运行配置」，供工具执行时按当前 Agent 取值（FR-012）。
- 本节同时用 `TraceContext` 把 `sessionId` / `profileName` / `channel` 投影进 MDC 业务键（沿第 16 节 `TraceContext` 既有能力，`MDC_SESSION_ID`/`MDC_PROFILE_NAME`/`MDC_CHANNEL`）；`TraceContext.openIfAbsent()` 保证「外层入口已开链路时复用、否则新开根链路」（research D-06）。
- **两者在同一 `finally` 收口**（`Scope.close()` + `ProfileContext.clear()`），保证异常路径也清理。
- 每请求独占一虚拟线程 → 天然不串台（FR-012）；**不添加第二个 `TaskDecorator`**（AGENTS.md §5.2，全容器唯一在 `ObservabilityConfig`）。

## 4. 边界

| 项 | 规约 |
| --- | --- |
| 会话跨重启持久化 | 归 18 节；本节 `SessionStore` 内存实现（FR-015） |
| 三种触发源入口本身（CLI 子命令 / REST / 调度） | 归 18/25/26 节；本节只提供入口**契约** |
| 会话内并发处理 | 本次不做（每请求一线程）；不加锁、无 `synchronized`（AGENTS.md §5.2） |

## 5. 关键回归测试（课件 harness）

| 测试（英文方法名） | 守点 | 课件原文 |
| --- | --- | --- |
| `process_duringRun_profileContextHoldsCurrentProfile` | 处理期间线程级上下文可取到当前 Profile（FR-012） | 处理期间线程级上下文可取到当前 Profile |
| `process_loopThrows_contextStillCleared` | 处理抛异常时 `finally` 也清上下文（SC-005） | 处理抛异常时 finally 也必须清掉 |
| `process_afterLoop_savesSession` | 处理正常结束后会话被保存（FR-015） | 处理完成后会话被保存 |

第二个测试为课件 harness **写出代码的关键回归测试**，断言逻辑逐条保真（在 `run` 中抛异常 → `assertThatThrownBy` → `assertThat(ProfileContext.current()).isNull()`），方法名译英文、课件中文进 `@DisplayName`。
