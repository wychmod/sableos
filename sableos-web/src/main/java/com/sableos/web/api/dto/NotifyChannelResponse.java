package com.sableos.web.api.dto;

import com.sableos.core.notify.NotifyChannelView;
import java.time.Instant;
import java.util.Map;

/**
 * 通知渠道响应视图（第 28 节）。
 *
 * @param name 全局唯一注册名
 * @param type 渠道类型
 * @param url 目标地址
 * @param description 描述
 * @param config 类型相关配置；恒非 null 且不可变
 * @param createdAt 创建时刻（ISO-8601）
 * @param updatedAt 更新时刻（ISO-8601）
 */
public record NotifyChannelResponse(
    String name,
    String type,
    String url,
    String description,
    Map<String, String> config,
    String createdAt,
    String updatedAt) {

  public NotifyChannelResponse {
    config = (config == null) ? Map.of() : Map.copyOf(config);
  }

  public static NotifyChannelResponse from(NotifyChannelView view) {
    return new NotifyChannelResponse(
        view.name(),
        view.type(),
        view.url(),
        view.description(),
        view.config(),
        text(view.createdAt()),
        text(view.updatedAt()));
  }

  private static String text(Instant instant) {
    return instant == null ? null : instant.toString();
  }
}
