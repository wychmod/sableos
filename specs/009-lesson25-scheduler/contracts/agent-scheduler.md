# Contract: `AgentScheduler`（第 25 节）

**Type**: 进程内类契约（非 HTTP / 非表结构） | **Feature**: `specs/009-lesson25-scheduler`

## 1. 类型与位置

```text
package com.sableos.core.scheduler;

public final class AgentScheduler {
  public AgentScheduler(TaskScheduler taskScheduler, ProfileRegistry profileRegistry,
                        AgentService agentService, SessionManager sessionManager);
  public void registerAll();
  void runOnce(Profile profile, Profile.ScheduleConfig schedule);   // 包级可见，供同包测试直接驱动
  Lock lockFor(String profileName, String taskId);                  // 任务身份 = (Agent 名, 任务 id)
  Lock sessionLockFor(String profileName);                          // 包级可见，键 = SessionManager.sessionId(...)
}
```

- 构造参数全部为既有协作方，**不新增对外概念**（不引入新的接口、枚举、配置键）。
- `runOnce` / `lockFor` 之所以包级可见：课件要求「`runOnce` 拆成独立方法，直接调它就能测全部行为逻辑」，同时不把它们变成对外 API。

## 2. `registerAll()` 契约

| 项 | 契约 |
|---|---|
| 输入 | `profileRegistry.all()` 的全部 Profile 及其 `schedules()` |
| 动作 | 每条配置注册一次 `taskScheduler.schedule(Runnable, CronTrigger)` |
| Runnable | `TracedExecutors.wrapRoot(() -> runOnce(profile, schedule), TraceContext.CHANNEL_SCHEDULER)` |
| Trigger | `new CronTrigger(schedule.cron(), ZoneId.of(schedule.zone()))` |
| 无配置 | 不注册任何任务，不报错 |
| 单条非法 | `id` / `cron` / `zone` 缺失或解析失败：记 error 日志并跳过该条，**不影响**其余条目与进程启动（FR-010） |
| 幂等 | 不保证幂等；重复调用会重复注册（运行期增删归第 30 节） |

## 3. `runOnce(profile, schedule)` 契约

执行顺序固定：

1. `Lock taskLock = lockFor(profile.name(), schedule.id())`；`if (!taskLock.tryLock())` → 记 WARN「task {id} still running, skip this trigger」→ 返回（不排队）。
   任务身份是 `(Agent 名, 任务 id)` 的 record 键，不是裸 id：两个 Agent 各自声明 `id: daily` 是两条独立任务，不得互相跳过（FR-004）。
2. `Lock sessionLock = sessionLockFor(profile.name())`；**阻塞** `lock()`。同一 Agent 的多条 schedules 共用
   `scheduler:scheduler:<profile>` 会话，若并发执行会各自加载历史快照、`save` 整列覆盖 `messages_json`，后写者把前者抹掉；
   故此锁必须串行化。用阻塞而非 `tryLock`：两条不同任务的触发都应有结果，不能把后到的一次静默丢掉。
3. `Session session = sessionManager.getOrCreate("scheduler", "scheduler", profile.name())`。
4. `agentService.process(session, schedule.message())`。
5. 任何 `Exception`：记 error（含净化后的 id 与原因）后吞掉，**不外抛**。
6. `finally`：先释放会话锁，再释放任务锁——成功、失败、跳过以外的任何路径都必须释放；两把锁的获取顺序固定为「先任务、后会话」，杜绝环。

锁键：`sessionLockFor` 调 `SessionManager.sessionId(CHANNEL_SCHEDULER, "scheduler", profileName)` 生成，不在调度器内另拼字符串（H4④）。

不变式：

- `session_id` 只由 `SessionManager` 拼接，本类不出现 `channel + ":" + user + ":" + profile` 形态的字符串拼接（H4④）。
- 审计不经本类：`llm_calls` / `tool_invocations` 由 `AgentService` 链路内既有组件写入，本节零改动。
- 日志中的 `id` / `message` 来自用户可编辑的 `AGENT.md`，入日志前先 `replaceAll("[\r\n]", "_")`。

## 4. 装配契约

| 位置 | 职责 |
|---|---|
| `sableos-boot/.../scheduler/SchedulerConfiguration` | 定义 `ThreadPoolTaskScheduler`（bean 名 `agentTaskScheduler`）与 `AgentScheduler` |
| `ServeCommand` / `GatewayCommand` | 上下文启动成功后调用 `registerAll()`；常驻期间由调度器自行触发 |
| `ChatCommand` | **不**调用 `registerAll()`（§8.6：定时任务随 serve/gateway 调度） |

`ThreadPoolTaskScheduler` 配置：线程名前缀 `sableos-scheduler-`，池大小 ≥ 2，使不同任务的触发互不阻塞（FR-004）。不注册 `TaskDecorator`（全容器仍只允许 `ObservabilityConfig` 那一个）。
