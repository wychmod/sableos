package com.sableos.web.api.dto;

import java.util.List;

/**
 * Agent 公开视图（26 节 {@code GET /profiles}）。
 *
 * <p>刻意不含 {@code identity.prompt}、{@code bootstrap}、{@code settings}：系统提示词与运行参数属内部指令，不是对外查询契约。
 */
public record ProfileView(
    String name,
    String description,
    String provider,
    String model,
    List<String> tools,
    List<String> channels) {

  public ProfileView {
    tools = (tools == null) ? List.of() : List.copyOf(tools);
    channels = (channels == null) ? List.of() : List.copyOf(channels);
  }
}
