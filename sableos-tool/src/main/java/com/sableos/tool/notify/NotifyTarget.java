package com.sableos.tool.notify;

import java.util.Map;

/**
 * 通知目标：渠道类型 + 一份配置映射——配置如何解释由实现类决定（webhook 档取 {@code config} 的 {@code url} 键），接口不感知具体渠道细节（FR-001
 * 接口先行）。
 *
 * @param channelType 渠道类型（核心阶段：{@code webhook}）
 * @param config 实现相关的配置映射；恒非 null 且不可变
 */
public record NotifyTarget(String channelType, Map<String, String> config) {

  public NotifyTarget {
    config = (config == null) ? Map.of() : Map.copyOf(config);
  }
}
