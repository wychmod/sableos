package com.sableos.storage;

import com.sableos.core.session.Session;
import com.sableos.core.session.SessionStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话保存的进程内实现（FR-015）：按会话 id 承载本次进程内的会话。
 *
 * <p>跨重启持久化（{@code sessions} 表 + 恢复）归 18 节 {@code SessionManager}；本节只保证「处理完成后会话被保存」。
 */
public class InMemorySessionStore implements SessionStore {

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  @Override
  public void save(Session session) {
    sessions.put(session.id(), session);
  }
}
