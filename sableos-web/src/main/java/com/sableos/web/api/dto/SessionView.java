package com.sableos.web.api.dto;

import java.util.List;

/** 会话视图（26 节）：时间以 ISO-8601 字符串输出，避免对外暴露内部时间类型。 */
public record SessionView(
    String id,
    String profileName,
    String channel,
    String userId,
    String status,
    String createdAt,
    String lastActiveAt,
    List<MessageView> messages) {

  public SessionView {
    messages = (messages == null) ? List.of() : List.copyOf(messages);
  }
}
