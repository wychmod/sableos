package com.sableos.storage.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * memory_entries 表的 JPA 映射（列语义见 specs/006-memory-service-backends/data-model.md）。
 *
 * <p>表结构的唯一权威是 Flyway 双轨迁移脚本，本类只做映射，禁用 ddl-auto 改结构。
 */
@Entity
@Table(name = "memory_entries")
public class MemoryEntryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "profile_name", nullable = false)
  private String profileName;

  @Column(name = "scope", nullable = false, length = 16)
  private String scope;

  @Column(name = "content", nullable = false)
  private String content;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** JPA 要求的无参构造。 */
  protected MemoryEntryEntity() {}

  public MemoryEntryEntity(String profileName, String scope, String content, Instant createdAt) {
    this.profileName = profileName;
    this.scope = scope;
    this.content = content;
    this.createdAt = createdAt;
  }

  @PrePersist
  void fillCreatedAt() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public String getProfileName() {
    return profileName;
  }

  public void setProfileName(String profileName) {
    this.profileName = profileName;
  }

  public String getScope() {
    return scope;
  }

  public void setScope(String scope) {
    this.scope = scope;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
