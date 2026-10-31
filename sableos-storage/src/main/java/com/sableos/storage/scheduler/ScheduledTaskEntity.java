package com.sableos.storage.scheduler;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * scheduled_tasks 表的 JPA 映射（技术方案 §9.2；第 28 节）。
 *
 * <p>表结构唯一权威是 Flyway 双轨脚本，本类只做映射，禁用 ddl-auto 改结构。{@code schedule_id} 由库自增， 对同一 {@code
 * (profile_name, schedule_key)} 跨重启稳定。
 */
@Entity
@Table(
    name = "scheduled_tasks",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_scheduled_tasks_profile_key",
            columnNames = {"profile_name", "schedule_key"}))
public class ScheduledTaskEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "schedule_id")
  private Long scheduleId;

  @Column(name = "profile_name", nullable = false)
  private String profileName;

  @Column(name = "schedule_key", nullable = false)
  private String scheduleKey;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Column(name = "cron", nullable = false)
  private String cron;

  @Column(name = "zone", nullable = false)
  private String zone;

  @Column(name = "message", nullable = false)
  private String message;

  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  @Column(name = "retired", nullable = false)
  private boolean retired;

  @Column(name = "next_run_at")
  private Instant nextRunAt;

  @Column(name = "last_run_at")
  private Instant lastRunAt;

  @Column(name = "last_status")
  private String lastStatus;

  @Column(name = "run_count", nullable = false)
  private long runCount;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** JPA 要求的无参构造。 */
  protected ScheduledTaskEntity() {}

  @PrePersist
  void fillUpdatedAt() {
    if (updatedAt == null) {
      updatedAt = Instant.now();
    }
  }

  public Long getScheduleId() {
    return scheduleId;
  }

  public String getProfileName() {
    return profileName;
  }

  public void setProfileName(String profileName) {
    this.profileName = profileName;
  }

  public String getScheduleKey() {
    return scheduleKey;
  }

  public void setScheduleKey(String scheduleKey) {
    this.scheduleKey = scheduleKey;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getCron() {
    return cron;
  }

  public void setCron(String cron) {
    this.cron = cron;
  }

  public String getZone() {
    return zone;
  }

  public void setZone(String zone) {
    this.zone = zone;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isRetired() {
    return retired;
  }

  public void setRetired(boolean retired) {
    this.retired = retired;
  }

  public Instant getNextRunAt() {
    return nextRunAt;
  }

  public void setNextRunAt(Instant nextRunAt) {
    this.nextRunAt = nextRunAt;
  }

  public Instant getLastRunAt() {
    return lastRunAt;
  }

  public void setLastRunAt(Instant lastRunAt) {
    this.lastRunAt = lastRunAt;
  }

  public String getLastStatus() {
    return lastStatus;
  }

  public void setLastStatus(String lastStatus) {
    this.lastStatus = lastStatus;
  }

  public long getRunCount() {
    return runCount;
  }

  public void setRunCount(long runCount) {
    this.runCount = runCount;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
