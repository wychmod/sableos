package com.sableos.core.session;

import java.util.Optional;

/**
 * 会话身份与持久化的唯一收口（18 节）：三种触发源（CLI / Web / 定时）共用。
 *
 * <p>契约在 core、JPA 实现 {@code JpaSessionManager} 在 storage（依赖倒置，沿用 §28 {@code ScheduledTaskStore}
 * 先例）。 本接口 {@code extends} 17 节的 {@link SessionStore}（追加式，{@code SessionStore} 零改动）：{@code
 * AgentService} 仍按 {@code SessionStore} 注入， 运行期拿到的即本接口的实现。
 *
 * <p>{@code session_id} 的拼接只允许发生在 {@link #sessionId} 这一处（不变量 H4④）——所有入口（CLI 传 {@code "cli"}、Web 传
 * {@code "web"}、定时传 {@code "scheduler"}）只提供三元组、绝不各自拼串：两处各拼一遍、格式差一个分隔符，同一个人就会出现两条互不相认的历史。
 */
public interface SessionManager extends SessionStore {

  /**
   * 会话标识的唯一拼接公式：{@code channel:user:profile}（如 {@code cli:wang:default}）。
   *
   * <p>channel 取值与 {@link com.sableos.core.trace.TraceContext} 的 {@code CHANNEL_CLI/WEB/SCHEDULER}
   * 同口径。 全代码库只此一处按该公式拼接（H4④）。
   *
   * @param channel 触发渠道（cli / web / scheduler）
   * @param userId 用户标识
   * @param profileName Agent 运行配置名
   * @return 稳定、可读、跨入口一致的会话标识
   */
  static String sessionId(String channel, String userId, String profileName) {
    return channel + ":" + userId + ":" + profileName;
  }

  /**
   * 按三元组取回既有会话或新建（幂等：同三元组收敛到同一 {@code session_id}）。
   *
   * <p>命中既有行时返回带完整历史与原始时间戳的会话（跨重启连续）；未命中时新建 {@code active} 空历史会话。
   *
   * @param channel 触发渠道（cli / web / scheduler）
   * @param userId 用户标识
   * @param profileName Agent 运行配置名
   * @return 该三元组对应的会话（多轮对话靠它串接）
   */
  Session getOrCreate(String channel, String userId, String profileName);

  /**
   * 按会话标识取回。
   *
   * @param sessionId 会话标识
   * @return 命中则为带历史的会话，否则 {@link Optional#empty()}
   */
  Optional<Session> get(String sessionId);

  /**
   * 软归档：把会话状态置为 {@code archived}，首次归档记录 {@code archived_at}，物理行保留（26 节）。
   *
   * <p>重复归档幂等：已归档的会话保持原 {@code archived_at} 并同样返回 {@code true}。归档不阻止后续消息，也不改变历史可读性——
   * 它只是一个生命周期标记，供列表过滤与审计使用。
   *
   * @param sessionId 会话标识
   * @return 命中并（或已）归档返回 {@code true}；会话不存在返回 {@code false}
   */
  boolean archive(String sessionId);

  // void save(Session) 继承自 SessionStore：序列化历史后 upsert 到 sessions 表（处理完成后调用）。
}
