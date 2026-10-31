package com.sableos.core.scheduler;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次定时任务执行的审计视图（技术方案 §8.5 / §9.2，第 28 节）。
 *
 * <p>成功失败都有一条；{@code scheduleId} 可为 null（迁移前无法可靠关联的历史）。
 *
 * @param executionId 执行历史主键
 * @param scheduleId 关联任务；迁移前历史可为 null
 * @param sessionId 本次触发所用的定时会话
 * @param startedAt 开始时刻
 * @param success 是否成功
 * @param errorMessage 失败原因，可读；成功为 null
 * @param durationMs 执行耗时（毫秒）
 */
public record TaskExecutionView(
    long executionId,
    Long scheduleId,
    String sessionId,
    Instant startedAt,
    boolean success,
    String errorMessage,
    long durationMs) {

  public TaskExecutionView {
    Objects.requireNonNull(sessionId, "sessionId 不能为空");
    Objects.requireNonNull(startedAt, "startedAt 不能为空");
    if (durationMs < 0) {
      throw new IllegalArgumentException("durationMs 不能为负数");
    }
  }
}
