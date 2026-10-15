package com.sableos.tool.notify;

/**
 * 出站通知抽象："把一条内容送到某个通知目标"这一意图的唯一表达（接口先行，FR-001）——接口语汇零渠道特有词， 扩展阶段按渠道类型增挂实现类，不改接口、不改调用方。
 *
 * <p>失败（对端非成功响应 / 连接失败）必须异常上抛，绝不静默吞掉——"发出去没送到"与"没发出去" 对 Agent 是同一件事。
 */
public interface NotifyChannelAdapter {

  /**
   * 把一条内容送到指定通知目标。
   *
   * @param target 通知目标（渠道类型 + 配置映射）
   * @param content 推送内容
   * @throws RuntimeException 发送失败（对端非成功响应 / 连接失败 / 目标配置缺失）
   */
  void send(NotifyTarget target, String content);
}
