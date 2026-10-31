package com.sableos.core.notify;

import java.util.List;
import java.util.Optional;

/**
 * 全局通知渠道注册表契约（技术方案 §6.8；第 28 节补齐）。
 *
 * <p>契约在 core、JPA 实现 {@code JpaNotifyChannelStore} 在 storage（依赖倒置）。{@code NotifyTools} 每次执行时
 * 通过本接口解析渠道，不缓存启动快照——改渠道立即生效。
 */
public interface NotifyChannelStore {

  /**
   * @return 全部渠道，按 {@code createdAt} 升序、同名按名称升序；第一条即缺省渠道
   */
  List<NotifyChannelView> list();

  /**
   * @param name 渠道名
   * @return 渠道视图；不存在为空
   */
  Optional<NotifyChannelView> find(String name);

  /**
   * Upsert：按 {@code name} 新建或更新；更新时保留原 {@code createdAt}。
   *
   * @param channel 渠道视图
   */
  void save(NotifyChannelView channel);

  /**
   * @param name 渠道名
   * @return 删除成功 true；不存在 false
   */
  boolean delete(String name);
}
