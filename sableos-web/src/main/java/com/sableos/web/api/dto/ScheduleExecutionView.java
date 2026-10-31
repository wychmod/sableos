package com.sableos.web.api.dto;

import com.sableos.core.scheduler.TaskExecutionView;

/**
 * 定时任务执行历史视图（第 28 节）。
 *
 * @param id 执行历史主键
 * @param scheduleId 关联任务
 * @param sessionId 定时会话
 * @param startedAt 开始时刻（ISO-8601）
 * @param success 是否成功
 * @param errorMessage 失败原因（成功为 null）
 * @param durationMs 耗时毫秒
 */
public record ScheduleExecutionView(
    long id,
    Long scheduleId,
    String sessionId,
    String startedAt,
    boolean success,
    String errorMessage,
    long durationMs) {

  public static ScheduleExecutionView from(TaskExecutionView view) {
    return new ScheduleExecutionView(
        view.executionId(),
        view.scheduleId(),
        view.sessionId(),
        view.startedAt().toString(),
        view.success(),
        view.errorMessage(),
        view.durationMs());
  }
}
