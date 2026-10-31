# Contract: AgentScheduler（单 Agent 登记与句柄）

**Feature**: `specs/013-lesson29-pluggable-agent` | 类：`com.sableos.core.scheduler.AgentScheduler`

## 新增成员

```java
public void registerProfile(Profile profile);          // 新增
private final ConcurrentMap<Long, ScheduledFuture<?>> scheduledTasks;  // 新增字段
public boolean hasScheduledTask(long scheduleId);      // 新增
```

既有 `registerAll()` 改为遍历 `profileRegistry.all()` 后逐条委派 `registerProfile(profile)`，行为等价。

## registerProfile 语义

对 `profile.schedules()` 的**每条** schedule 独立执行：

1. 校验 `id` / `cron` / `zone` 非空；为空记 ERROR 并跳过**该条**（不影响同 Agent 其他条、不影响其他 Agent）。
2. `CronTrigger(cron, ZoneId.of(zone))`；解析失败抛出的运行时异常在此捕获为 ERROR，跳过该条。
3. `scheduledTaskStore.reconcile(profileName, key, displayName, cron, zone, message, nextRunAt)` 取得稳定 `scheduleId`。
4. `taskScheduler.schedule(TracedExecutors.wrapRoot(...), trigger)` 取得 `future`。
5. `registeredTasks.put(scheduleId, new RegisteredTask(...))`（既有）。
6. `scheduledTasks.put(scheduleId, future)`；**若非 null 且引用不同**，对旧值 `cancel(false)`。

**不变量**：
- 单条配置非法只跳过该条，不向调用方抛异常（FR-003 的同类要求沿用）。
- 同一 `(profileName, key)` 被重复登记时，`scheduleId` 不变（`reconcile` 保证），句柄表条目数为 1。
- `cancel(false)` 不中断正在执行的那一次，只阻止后续触发（research D-06）。

## hasScheduledTask 语义

```java
public boolean hasScheduledTask(long scheduleId)
```

- 该 `scheduleId` 当前是否持有句柄；供第 30 节的"注销前确认"与测试断言使用。
- 只读，不触发任何调度动作。

## 与既有方法的关系

| 既有 | 本节变化 |
| --- | --- |
| `registerAll()` | 循环体抽出为 `registerProfile`；`retireMissing` 逻辑保留在 `registerAll` 外层，不改 |
| `runOnce(...)` | 不改 |
| `runNow(long)` | 不改 |
| `taskLocks` / `sessionLocks` | 不改 |
| `CALL_TIMEOUT`（28 节稳定性） | 不改 |

## 线程与链路

- 句柄表为 `ConcurrentHashMap`，无 `synchronized`（AGENTS §5.2：JDK 21 下锁内阻塞会钉住虚拟线程）。
- 每条 trigger 的 Runnable 仍由 `TracedExecutors.wrapRoot(..., CHANNEL_SCHEDULER)` 包装，每次触发开新根链路。
- 不新增 `TaskDecorator`（全容器只允许 `ObservabilityConfig` 那一个）。

## 不做的事

- 不提供 `unregisterProfile(Profile)`（第 30 节的注销入口；本节只立句柄与查询，按课件"新增句柄表（30 节注销/更新用）"的措辞，注销实现留给第 30 节）。
- 不改 `scheduled_tasks` 表结构（第 28 节已定稿）。
