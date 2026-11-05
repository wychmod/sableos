package com.sableos.web.api.dto;

/**
 * 定时声明视图（第 30 节）：只含 {@code AGENT.md} frontmatter 里写的四字段，运行态（scheduleId / 启停 / 历史）走 28 节的 {@code
 * /api/v2/schedules}，两者不混。
 */
public record ScheduleConfigView(String id, String cron, String zone, String message) {}
