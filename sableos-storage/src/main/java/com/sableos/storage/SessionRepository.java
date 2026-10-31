package com.sableos.storage;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * sessions 表仓储（18 节）。主键为 {@code session_id}（{@code channel:user:profile}，非自增）。
 *
 * <p>由 {@code JpaSessionManager} 消费；{@code SessionManager.get/getOrCreate/save} 的存取都经此。
 */
public interface SessionRepository extends JpaRepository<SessionEntity, String> {

  /** 按状态取一页（27 节会话列表用）：排序与条数由 {@link Pageable} 决定，过滤在 SQL 里做。 */
  List<SessionEntity> findByStatus(String status, Pageable pageable);
}
