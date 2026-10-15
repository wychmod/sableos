package com.sableos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * sessions 表仓储（18 节）。主键为 {@code session_id}（{@code channel:user:profile}，非自增）。
 *
 * <p>由 {@code JpaSessionManager} 消费；{@code SessionManager.get/getOrCreate/save} 的存取都经此。
 */
public interface SessionRepository extends JpaRepository<SessionEntity, String> {}
