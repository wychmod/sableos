package com.sableos.core.notify;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 全局通知渠道视图（技术方案 §6.8；第 28 节补齐注册表）。
 *
 * <p>渠道以全局唯一名称登记；Agent 在正文按名引用，运行声明里不含渠道字段。{@code config} 承载类型相关多字段， 核心阶段只实现 {@code webhook}，{@code
 * url} 是权威目标地址。
 *
 * @param name 全局唯一注册名
 * @param type 渠道类型；核心阶段只支持 {@code webhook}
 * @param url 目标地址
 * @param description 可选描述
 * @param config 类型相关配置；恒非 null 且不可变
 * @param createdAt 创建时刻；决定"第一个渠道"
 * @param updatedAt 更新时刻
 */
public record NotifyChannelView(
    String name,
    String type,
    String url,
    String description,
    Map<String, String> config,
    Instant createdAt,
    Instant updatedAt) {

  public NotifyChannelView {
    Objects.requireNonNull(name, "name 不能为空");
    Objects.requireNonNull(type, "type 不能为空");
    Objects.requireNonNull(url, "url 不能为空");
    Objects.requireNonNull(createdAt, "createdAt 不能为空");
    Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
    config = (config == null) ? Map.of() : Map.copyOf(config);
  }
}
