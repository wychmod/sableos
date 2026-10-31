package com.sableos.web.api.dto;

import com.sableos.core.scheduler.ScheduledTaskView;
import java.time.Instant;

/**
 * 定时任务管理视图（第 28 节，v2 契约见 {@code
 * specs/012-lesson28-scheduler-subsystem/contracts/management-api.md}）。
 *
 * @param scheduleId 全局运行态主键
 * @param profileName 归属 Agent
 * @param scheduleKey Agent 内任务键
 * @param displayName 展示名
 * @param cron cron 表达式
 * @param zone 时区
 * @param message 到点消息
 * @param enabled 启用状态
 * @param retired 是否退役
 * @param nextRunAt 下次触发时刻（ISO-8601，可空）
 * @param lastRunAt 上次触发时刻（ISO-8601，可空）
 * @param lastStatus {@code success} / {@code failed}（可空）
 * @param runCount 累计执行次数
 * @param updatedAt 状态更新时间（ISO-8601）
 */
public record ScheduleView(
    long scheduleId,
    String profileName,
    String scheduleKey,
    String displayName,
    String cron,
    String zone,
    String message,
    boolean enabled,
    boolean retired,
    String nextRunAt,
    String lastRunAt,
    String lastStatus,
    long runCount,
    String updatedAt) {

  public static ScheduleView from(ScheduledTaskView view) {
    return new ScheduleView(
        view.scheduleId(),
        view.profileName(),
        view.scheduleKey(),
        view.displayName(),
        view.cron(),
        view.zone(),
        view.message(),
        view.enabled(),
        view.retired(),
        text(view.nextRunAt()),
        text(view.lastRunAt()),
        view.lastStatus(),
        view.runCount(),
        text(view.updatedAt()));
  }

  private static String text(Instant instant) {
    return instant == null ? null : instant.toString();
  }
}
