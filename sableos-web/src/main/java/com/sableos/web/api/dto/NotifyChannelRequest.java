package com.sableos.web.api.dto;

import java.util.Map;

/**
 * 通知渠道新建/更新请求（第 28 节，契约见 {@code contracts/management-api.md}）。
 *
 * @param name 全局唯一注册名
 * @param type 渠道类型；核心阶段只接受 webhook
 * @param url 目标地址
 * @param description 可选描述
 * @param config 类型相关配置；恒非 null 且不可变
 */
public record NotifyChannelRequest(
    String name, String type, String url, String description, Map<String, String> config) {

  public NotifyChannelRequest {
    config = (config == null) ? Map.of() : Map.copyOf(config);
  }
}
