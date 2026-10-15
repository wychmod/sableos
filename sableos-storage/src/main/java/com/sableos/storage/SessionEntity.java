package com.sableos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * sessions 表的 JPA 映射（列语义见 specs/003-lesson18-cli/data-model.md §2、contracts/sessions-schema.md）。
 *
 * <p>命名取 {@code SessionEntity} 而非 {@code Session}，避免与 core 领域类 {@code
 * com.sableos.core.session.Session} 撞名（research D-03）； 二者由 {@code JpaSessionManager} 映射。表结构唯一权威是
 * Flyway 双轨迁移脚本，本类只做映射，禁用 ddl-auto 改结构。
 *
 * <p>{@code session_id} 是主键且**非自增**——由 {@code SessionManager.sessionId(channel,user,profile)}
 * 生成后写入。
 */
@Entity
@Table(name = "sessions")
public class SessionEntity {

  @Id
  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "profile_name", nullable = false)
  private String profileName;

  @Column(name = "channel", nullable = false)
  private String channel;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "messages_json", nullable = false)
  private String messagesJson;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_active_at", nullable = false)
  private Instant lastActiveAt;

  @Column(name = "archived_at")
  private Instant archivedAt;

  /** JPA 要求的无参构造。 */
  protected SessionEntity() {}

  @PrePersist
  void fillDefaults() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (lastActiveAt == null) {
      lastActiveAt = now;
    }
    if (status == null) {
      status = "active";
    }
    if (messagesJson == null) {
      messagesJson = "[]";
    }
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getProfileName() {
    return profileName;
  }

  public void setProfileName(String profileName) {
    this.profileName = profileName;
  }

  public String getChannel() {
    return channel;
  }

  public void setChannel(String channel) {
    this.channel = channel;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getMessagesJson() {
    return messagesJson;
  }

  public void setMessagesJson(String messagesJson) {
    this.messagesJson = messagesJson;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getLastActiveAt() {
    return lastActiveAt;
  }

  public void setLastActiveAt(Instant lastActiveAt) {
    this.lastActiveAt = lastActiveAt;
  }

  public Instant getArchivedAt() {
    return archivedAt;
  }

  public void setArchivedAt(Instant archivedAt) {
    this.archivedAt = archivedAt;
  }
}
