package com.sableos.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.core.session.SessionMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link SessionManager} 的 JPA 实现（18 节）：会话身份与跨重启持久化的落地。
 *
 * <p>{@code session_id} 拼接（{@code channel:user:profile}）只发生在 {@link SessionManager#sessionId}
 * 一处（不变量 H4④）—— 本类只调用它、绝不另行拼接。对话历史整体 JSON 序列化存 {@code messages_json} 一列，核心阶段不按条拆表（research D-05）。
 *
 * <p>领域 {@link Session} ↔ {@link SessionEntity} 的映射收口在 {@link #restore} 与 {@link #save}（research
 * D-03/D-04）。
 */
public class JpaSessionManager implements SessionManager {

  private final SessionRepository repository;
  private final ObjectMapper mapper;

  public JpaSessionManager(SessionRepository repository) {
    this.repository = repository;
    // 对未知字段宽容：SessionMessage 未来增字段时，旧行回读不因多/少字段炸（forward-compat）。
    this.mapper =
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  @Override
  public Session getOrCreate(String channel, String userId, String profileName) {
    String id = SessionManager.sessionId(channel, userId, profileName);
    Optional<SessionEntity> existing = repository.findById(id);
    if (existing.isPresent()) {
      return restore(existing.get());
    }
    SessionEntity entity = new SessionEntity();
    entity.setSessionId(id);
    entity.setProfileName(profileName);
    entity.setChannel(channel);
    entity.setUserId(userId);
    entity.setStatus("active");
    entity.setMessagesJson("[]");
    Instant now = Instant.now();
    entity.setCreatedAt(now);
    entity.setLastActiveAt(now);
    repository.save(entity);
    // 返回与刚落库行一致的重建态（同一 createdAt/lastActiveAt、空历史），避免"返回态"与"持久态"时间戳漂移。
    return restore(entity);
  }

  @Override
  public Optional<Session> get(String sessionId) {
    return repository.findById(sessionId).map(this::restore);
  }

  @Override
  public void save(Session session) {
    // sableos 的领域 Session 自带 channel/userId/status/createdAt（比 oryxos 的瘦 Session 富），
    // 故 save 做 upsert：命中既有行只更新易变列，未命中则从 Session 补全整行插入——
    // AgentService.process 保存路径无需强制先 getOrCreate（更健壮，且 L17 直接构造 Session 的调用方不破）。
    SessionEntity entity = repository.findById(session.id()).orElse(null);
    if (entity == null) {
      entity = new SessionEntity();
      entity.setSessionId(session.id());
      entity.setProfileName(session.profileName());
      entity.setChannel(orEmpty(session.channel()));
      entity.setUserId(orEmpty(session.userId()));
      entity.setCreatedAt(session.createdAt());
    }
    entity.setMessagesJson(writeMessages(session.messages()));
    entity.setStatus(session.status());
    entity.setLastActiveAt(session.lastActiveAt());
    repository.save(entity);
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private Session restore(SessionEntity entity) {
    return Session.rehydrate(
        entity.getSessionId(),
        entity.getProfileName(),
        entity.getChannel(),
        entity.getUserId(),
        entity.getStatus(),
        entity.getCreatedAt(),
        entity.getLastActiveAt(),
        readMessages(entity.getMessagesJson()));
  }

  private String writeMessages(List<SessionMessage> messages) {
    try {
      return mapper.writeValueAsString(messages == null ? List.of() : messages);
    } catch (JsonProcessingException e) {
      // 历史写不进去等于会话丢失，必须显式失败而不是静默存空。
      throw new IllegalStateException("对话历史序列化失败: " + e.getOriginalMessage(), e);
    }
  }

  private List<SessionMessage> readMessages(String messagesJson) {
    if (messagesJson == null || messagesJson.isBlank()) {
      return List.of();
    }
    try {
      return mapper.readValue(messagesJson, new TypeReference<List<SessionMessage>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("对话历史反序列化失败: " + e.getOriginalMessage(), e);
    }
  }
}
