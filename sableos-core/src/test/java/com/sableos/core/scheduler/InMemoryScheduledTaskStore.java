package com.sableos.core.scheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 调度器单测用的内存 store：语义与 JPA 实现对齐，避免每个用例启 Spring 上下文。 */
final class InMemoryScheduledTaskStore implements ScheduledTaskStore {

  private final Map<Long, ScheduledTaskView> tasks = new ConcurrentHashMap<>();
  private final Map<Long, List<TaskExecutionView>> executions = new ConcurrentHashMap<>();
  private final AtomicLong nextTaskId = new AtomicLong(1);
  private final AtomicLong nextExecutionId = new AtomicLong(1);

  @Override
  public ScheduledTaskView reconcile(
      String profileName,
      String scheduleKey,
      String displayName,
      String cron,
      String zone,
      String message,
      Instant nextRunAt) {
    Optional<ScheduledTaskView> existing =
        tasks.values().stream()
            .filter(
                view ->
                    view.profileName().equals(profileName)
                        && view.scheduleKey().equals(scheduleKey))
            .findFirst();
    if (existing.isPresent()) {
      ScheduledTaskView old = existing.get();
      ScheduledTaskView updated =
          new ScheduledTaskView(
              old.scheduleId(),
              profileName,
              scheduleKey,
              displayName,
              cron,
              zone,
              message,
              old.enabled(),
              false,
              nextRunAt,
              old.lastRunAt(),
              old.lastStatus(),
              old.runCount(),
              Instant.now());
      tasks.put(old.scheduleId(), updated);
      return updated;
    }
    long id = nextTaskId.getAndIncrement();
    ScheduledTaskView created =
        new ScheduledTaskView(
            id,
            profileName,
            scheduleKey,
            displayName,
            cron,
            zone,
            message,
            true,
            false,
            nextRunAt,
            null,
            null,
            0,
            Instant.now());
    tasks.put(id, created);
    return created;
  }

  @Override
  public void retire(long scheduleId) {
    tasks.computeIfPresent(
        scheduleId,
        (id, old) ->
            new ScheduledTaskView(
                old.scheduleId(),
                old.profileName(),
                old.scheduleKey(),
                old.displayName(),
                old.cron(),
                old.zone(),
                old.message(),
                old.enabled(),
                true,
                old.nextRunAt(),
                old.lastRunAt(),
                old.lastStatus(),
                old.runCount(),
                Instant.now()));
  }

  @Override
  public Optional<TaskExecutionView> recordExecution(
      long scheduleId,
      String sessionId,
      Instant startedAt,
      boolean success,
      String errorMessage,
      long durationMs,
      Instant nextRunAt) {
    ScheduledTaskView old = tasks.get(scheduleId);
    if (old == null) {
      return Optional.empty();
    }
    TaskExecutionView execution =
        new TaskExecutionView(
            nextExecutionId.getAndIncrement(),
            scheduleId,
            sessionId,
            startedAt,
            success,
            errorMessage,
            durationMs);
    executions.computeIfAbsent(scheduleId, id -> new CopyOnWriteArrayList<>()).add(execution);
    tasks.put(
        scheduleId,
        new ScheduledTaskView(
            old.scheduleId(),
            old.profileName(),
            old.scheduleKey(),
            old.displayName(),
            old.cron(),
            old.zone(),
            old.message(),
            old.enabled(),
            old.retired(),
            nextRunAt == null ? old.nextRunAt() : nextRunAt,
            startedAt,
            success ? "success" : "failed",
            old.runCount() + 1,
            Instant.now()));
    return Optional.of(execution);
  }

  @Override
  public boolean isEnabled(long scheduleId) {
    ScheduledTaskView view = tasks.get(scheduleId);
    return view != null && view.enabled() && !view.retired();
  }

  @Override
  public boolean setEnabled(long scheduleId, boolean enabled) {
    ScheduledTaskView old = tasks.get(scheduleId);
    if (old == null) {
      return false;
    }
    tasks.put(
        scheduleId,
        new ScheduledTaskView(
            old.scheduleId(),
            old.profileName(),
            old.scheduleKey(),
            old.displayName(),
            old.cron(),
            old.zone(),
            old.message(),
            enabled,
            old.retired(),
            old.nextRunAt(),
            old.lastRunAt(),
            old.lastStatus(),
            old.runCount(),
            Instant.now()));
    return true;
  }

  @Override
  public Optional<ScheduledTaskView> find(long scheduleId) {
    return Optional.ofNullable(tasks.get(scheduleId));
  }

  @Override
  public List<ScheduledTaskView> list() {
    return tasks.values().stream()
        .filter(view -> !view.retired())
        .sorted(
            Comparator.comparing(ScheduledTaskView::profileName)
                .thenComparing(ScheduledTaskView::scheduleKey))
        .toList();
  }

  @Override
  public List<TaskExecutionView> executions(long scheduleId) {
    return List.copyOf(executions.getOrDefault(scheduleId, new ArrayList<>()));
  }
}
