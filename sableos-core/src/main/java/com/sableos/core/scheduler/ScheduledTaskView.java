package com.sableos.core.scheduler;

import java.time.Instant;
import java.util.Objects;

/**
 * 定时任务的运行态视图（技术方案 §8.5 / §9.2，第 28 节）。
 *
 * <p>契约在 core、JPA 实现在 storage：调度器与 Web 只认这个中立值对象，不接触 JPA 实体。字段与 {@code scheduled_tasks}
 * 表一一对应；{@code displayName} 在核心阶段取任务键，不参与运行态定位。
 *
 * @param scheduleId 全局运行态主键，跨重启稳定
 * @param profileName 归属 Agent
 * @param scheduleKey Agent 内配置键；同一 Profile 内唯一
 * @param displayName 展示名；核心阶段取 {@code scheduleKey}
 * @param cron cron 表达式
 * @param zone 时区标识
 * @param message 到点发给 Agent 的消息
 * @param enabled 运营开关
 * @param retired 定义已删除或 key 已修改
 * @param nextRunAt 下次触发时刻；cron 解析成功时必有值
 * @param lastRunAt 上次真正执行开始时刻
 * @param lastStatus {@code success} / {@code failed}；从未执行为 null
 * @param runCount 累计真正执行次数
 * @param updatedAt 状态更新时间
 */
public record ScheduledTaskView(
    long scheduleId,
    String profileName,
    String scheduleKey,
    String displayName,
    String cron,
    String zone,
    String message,
    boolean enabled,
    boolean retired,
    Instant nextRunAt,
    Instant lastRunAt,
    String lastStatus,
    long runCount,
    Instant updatedAt) {

  private static final String STATUS_SUCCESS = "success";

  private static final String STATUS_FAILED = "failed";

  public ScheduledTaskView {
    Objects.requireNonNull(profileName, "profileName 不能为空");
    Objects.requireNonNull(scheduleKey, "scheduleKey 不能为空");
    Objects.requireNonNull(displayName, "displayName 不能为空");
    Objects.requireNonNull(cron, "cron 不能为空");
    Objects.requireNonNull(zone, "zone 不能为空");
    Objects.requireNonNull(message, "message 不能为空");
    Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
    if (runCount < 0) {
      throw new IllegalArgumentException("runCount 不能为负数");
    }
    if (lastStatus != null
        && !STATUS_SUCCESS.equals(lastStatus)
        && !STATUS_FAILED.equals(lastStatus)) {
      throw new IllegalArgumentException("lastStatus 只能是 success / failed / null");
    }
  }
}
