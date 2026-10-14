package com.sableos.core.session;

/**
 * 会话保存的前向契约（FR-015）：处理完成后保存会话。
 *
 * <p>本节由 storage 的 {@code InMemorySessionStore} 实现（进程内承载）；跨重启持久化（{@code sessions} 表 + 恢复）归 18 节。
 */
public interface SessionStore {

  /** 保存会话（处理完成后调用）。 */
  void save(Session session);
}
