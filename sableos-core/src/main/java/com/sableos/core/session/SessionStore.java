package com.sableos.core.session;

/**
 * 会话保存的前向契约（FR-015）：处理完成后保存会话。
 *
 * <p>18 节起由 storage 的 {@code JpaSessionManager} 实现（{@code sessions} 表持久化、跨重启恢复）。
 */
public interface SessionStore {

  /** 保存会话（处理完成后调用）。 */
  void save(Session session);
}
