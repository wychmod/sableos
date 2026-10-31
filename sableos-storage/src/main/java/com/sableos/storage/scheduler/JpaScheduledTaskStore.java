package com.sableos.storage.scheduler;

import com.sableos.core.scheduler.ScheduledTaskStore;
import com.sableos.core.scheduler.ScheduledTaskView;
import com.sableos.core.scheduler.TaskExecutionView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ScheduledTaskStore} 的 JPA 实现（技术方案 §8.5 / §9.2；第 28 节）。
 *
 * <p>契约在 core、实现在此（依赖倒置）。写操作各自一个事务；{@code recordExecution} 把"插历史 + 更新任务状态"
 * 放在同一事务里，避免出现"历史有、列表没变"或反之。
 */
public class JpaScheduledTaskStore implements ScheduledTaskStore {

  private static final String STATUS_SUCCESS = "success";

  private static final String STATUS_FAILED = "failed";

  private final ScheduledTaskRepository taskRepository;
  private final TaskExecutionRepository executionRepository;

  public JpaScheduledTaskStore(
      ScheduledTaskRepository taskRepository, TaskExecutionRepository executionRepository) {
    this.taskRepository = taskRepository;
    this.executionRepository = executionRepository;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ScheduledTaskView reconcile(
      String profileName,
      String scheduleKey,
      String displayName,
      String cron,
      String zone,
      String message,
      Instant nextRunAt) {
    String safeProfile = requireText(profileName, "profileName");
    String safeKey = requireText(scheduleKey, "scheduleKey");
    ScheduledTaskEntity entity =
        taskRepository
            .findByProfileNameAndScheduleKey(safeProfile, safeKey)
            .orElseGet(
                () -> {
                  ScheduledTaskEntity created = new ScheduledTaskEntity();
                  created.setProfileName(safeProfile);
                  created.setScheduleKey(safeKey);
                  created.setEnabled(true);
                  created.setRetired(false);
                  created.setRunCount(0);
                  return created;
                });
    entity.setDisplayName(requireText(displayName, "displayName"));
    entity.setCron(requireText(cron, "cron"));
    entity.setZone(requireText(zone, "zone"));
    entity.setMessage(requireText(message, "message"));
    // 配置再次出现时复活退役行；enabled / runCount / lastRunAt / lastStatus 保留。
    entity.setRetired(false);
    entity.setNextRunAt(nextRunAt);
    entity.setUpdatedAt(Instant.now());
    return toView(taskRepository.save(entity));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void retire(long scheduleId) {
    taskRepository
        .findById(scheduleId)
        .ifPresent(
            entity -> {
              entity.setRetired(true);
              entity.setUpdatedAt(Instant.now());
              taskRepository.save(entity);
            });
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Optional<TaskExecutionView> recordExecution(
      long scheduleId,
      String sessionId,
      Instant startedAt,
      boolean success,
      String errorMessage,
      long durationMs,
      Instant nextRunAt) {
    Optional<ScheduledTaskEntity> found = taskRepository.findById(scheduleId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    TaskExecutionEntity execution = new TaskExecutionEntity();
    execution.setScheduleId(scheduleId);
    execution.setSessionId(requireText(sessionId, "sessionId"));
    execution.setStartedAt(startedAt == null ? Instant.now() : startedAt);
    execution.setSuccess(success);
    execution.setErrorMessage(success ? null : defaultError(errorMessage));
    execution.setDurationMs(Math.max(0, durationMs));
    TaskExecutionEntity saved = executionRepository.save(execution);

    ScheduledTaskEntity task = found.get();
    task.setLastRunAt(execution.getStartedAt());
    task.setLastStatus(success ? STATUS_SUCCESS : STATUS_FAILED);
    task.setRunCount(task.getRunCount() + 1);
    if (nextRunAt != null) {
      task.setNextRunAt(nextRunAt);
    }
    task.setUpdatedAt(Instant.now());
    taskRepository.save(task);
    return Optional.of(toView(saved));
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isEnabled(long scheduleId) {
    return taskRepository
        .findById(scheduleId)
        .map(entity -> entity.isEnabled() && !entity.isRetired())
        .orElse(false);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean setEnabled(long scheduleId, boolean enabled) {
    Optional<ScheduledTaskEntity> found = taskRepository.findById(scheduleId);
    if (found.isEmpty()) {
      return false;
    }
    ScheduledTaskEntity task = found.get();
    task.setEnabled(enabled);
    task.setUpdatedAt(Instant.now());
    taskRepository.save(task);
    return true;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ScheduledTaskView> find(long scheduleId) {
    return taskRepository.findById(scheduleId).map(JpaScheduledTaskStore::toView);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ScheduledTaskView> list() {
    return taskRepository.findByRetiredFalseOrderByProfileNameAscScheduleKeyAsc().stream()
        .map(JpaScheduledTaskStore::toView)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<TaskExecutionView> executions(long scheduleId) {
    return executionRepository.findByScheduleIdOrderByStartedAtDescIdDesc(scheduleId).stream()
        .map(JpaScheduledTaskStore::toView)
        .toList();
  }

  private static ScheduledTaskView toView(ScheduledTaskEntity entity) {
    return new ScheduledTaskView(
        entity.getScheduleId(),
        entity.getProfileName(),
        entity.getScheduleKey(),
        entity.getDisplayName(),
        entity.getCron(),
        entity.getZone(),
        entity.getMessage(),
        entity.isEnabled(),
        entity.isRetired(),
        entity.getNextRunAt(),
        entity.getLastRunAt(),
        entity.getLastStatus(),
        entity.getRunCount(),
        entity.getUpdatedAt());
  }

  private static TaskExecutionView toView(TaskExecutionEntity entity) {
    return new TaskExecutionView(
        entity.getId(),
        entity.getScheduleId(),
        entity.getSessionId(),
        entity.getStartedAt(),
        entity.isSuccess(),
        entity.getErrorMessage(),
        entity.getDurationMs());
  }

  private static String defaultError(String errorMessage) {
    return (errorMessage == null || errorMessage.isBlank()) ? "执行失败（未提供原因）" : errorMessage;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value;
  }
}
