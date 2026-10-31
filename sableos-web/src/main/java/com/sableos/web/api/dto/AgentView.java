package com.sableos.web.api.dto;

import java.util.List;

/**
 * Agent 列表/详情共用的公开视图（第 30 节）。
 *
 * <p>与 26 节 {@link ProfileView} 同口径：不含 {@code identity.prompt}、{@code bootstrap}、{@code
 * settings}——系统提示词与运行参数 属内部指令，不是对外查询契约。额外带上定时声明，供管理台详情页展示。
 */
public record AgentView(
    String name,
    String description,
    String provider,
    String model,
    List<String> tools,
    List<String> channels,
    List<ScheduleConfigView> schedules) {

  public AgentView {
    tools = (tools == null) ? List.of() : List.copyOf(tools);
    channels = (channels == null) ? List.of() : List.copyOf(channels);
    schedules = (schedules == null) ? List.of() : List.copyOf(schedules);
  }
}
