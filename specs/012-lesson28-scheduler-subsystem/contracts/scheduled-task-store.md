# Contract: `ScheduledTaskStore`（core）

**Package**: `com.sableos.core.scheduler` | **实现**: `com.sableos.storage.scheduler.JpaScheduledTaskStore`

## 接口

```java
public interface ScheduledTaskStore {

  ScheduledTaskView reconcile(
      String profileName,
      String scheduleKey,
      String displayName,
      String cron,
      String zone,
      String message,
      Instant nextRunAt);

  void retire(long scheduleId);

  Optional<TaskExecutionView> recordExecution(
      long scheduleId,
      String sessionId,
      Instant startedAt,
      boolean success,
      String errorMessage,
      long durationMs,
      Instant nextRunAt);

  boolean isEnabled(long scheduleId);

  boolean setEnabled(long scheduleId, boolean enabled);

  Optional<ScheduledTaskView> find(long scheduleId);

  List<ScheduledTaskView> list();

  List<TaskExecutionView> executions(long scheduleId);
}
```

## 语义

### `reconcile`

- 按 `(profileName, scheduleKey)` 查找行（含退役行）。
- 命中：复用 `scheduleId`；更新 `displayName / cron / zone / message / nextRunAt / updatedAt`；`enabled`、`runCount`、`lastRunAt`、`lastStatus` 保留；若 `retired=true` 则置回 false。
- 未命中：新建行；`enabled=true`、`retired=false`、`runCount=0`、`lastRunAt=null`、`lastStatus=null`。
- 返回当前行视图。`profileName` / `scheduleKey` / `displayName` / `cron` / `zone` / `message` 为空白时抛 `IllegalArgumentException`（调用方已在注册期校验，这里是最后一道防线）。

### `retire`

- 命中：`retired=true`、`updatedAt=now`；不删行、不删历史。
- 未命中：静默 no-op（启动对账可能遇到已被并发删除的行）。

### `recordExecution`

- 在**同一事务**内：插入一条 `task_executions`，并更新任务行：
  - `lastRunAt = startedAt`
  - `lastStatus = success ? "success" : "failed"`
  - `runCount = runCount + 1`
  - `nextRunAt = nextRunAt`（非 null 时覆盖；null 时保留原值）
  - `updatedAt = now`
- 任务行不存在时返回 `Optional.empty()`，不插入历史（避免悬空外键），调用方记录错误日志；成功时返回落库后的执行历史（含自增 `executionId`）。
- `errorMessage` 在写入前由调度器做 CRLF 净化与截断；store 原样保存。
- 成功时 `errorMessage` 必须为 null。

### `isEnabled`

- 行存在且 `enabled=true` 且 `retired=false` → true；其余（包含行不存在）→ false。

### `setEnabled`

- 命中：更新 `enabled`、`updatedAt`；返回 true。
- 未命中：返回 false。

### `find`

- 按 `scheduleId` 返回视图（含退役行）；不存在返回 `Optional.empty()`。

### `list`

- 只返回 `retired=false` 的行，按 `profileName ASC, scheduleKey ASC` 排序。

### `executions`

- 返回该任务的全部执行历史，按 `startedAt DESC, id DESC` 排序；任务不存在返回空列表。

## 实现约束（storage）

- `@Transactional` 覆盖 `reconcile` / `retire` / `recordExecution` / `setEnabled`；只读方法可不加。
- 实体只做映射，列定义以 Flyway 脚本为准；禁 `ddl-auto`。
- 不把 JPA 实体泄漏到 core；返回 core 值对象。
- `recordExecution` 的 `runCount` 更新必须在数据库侧完成（读取当前行 + 加一，或原子 UPDATE），不得依赖调用方传入的旧值。
