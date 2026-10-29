package com.sableos.storage.sandbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * sandbox_whitelist_entries 表的 JPA 映射（列语义见 specs/008-sandbox-whitelist-admin/data-model.md）。
 *
 * <p>表结构的唯一权威是 Flyway 双轨迁移脚本，本类只做映射，禁用 ddl-auto 改结构。
 *
 * <p>{@code type} 存枚举名而非 ordinal：枚举顺序调整不应让历史数据的语义漂移。
 */
@Entity
@Table(name = "sandbox_whitelist_entries")
public class SandboxWhitelistEntryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "type", nullable = false, length = 32)
  private String type;

  @Column(name = "value", nullable = false, length = 1024)
  private String value;

  @Column(name = "description", length = 255)
  private String description;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** JPA 要求的无参构造。 */
  protected SandboxWhitelistEntryEntity() {}

  public SandboxWhitelistEntryEntity(String type, String value, String description) {
    this.type = type;
    this.value = value;
    this.description = description;
  }

  @PrePersist
  void fillTimestamps() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void refreshUpdatedAt() {
    updatedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public String getType() {
    return type;
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
