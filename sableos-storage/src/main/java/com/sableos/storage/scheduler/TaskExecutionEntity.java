package com.sableos.storage.scheduler;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * task_executions 表的 JPA 映射（技术方案 §9.2；第 28 节）。
 *
 * <p>每次真正执行一条，成功失败都记。{@code schedule_id} 可空，兼容迁移前无法可靠关联的历史。
 */
@Entity
@Table(name = "task_executions")
public class TaskExecutionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "schedule_id")
  private Long scheduleId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "success", nullable = false)
  private boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "duration_ms", nullable = false)
  private long durationMs;

  /** JPA 要求的无参构造。 */
  protected TaskExecutionEntity() {}

  @PrePersist
  void fillStartedAt() {
    if (startedAt == null) {
      startedAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public Long getScheduleId() {
    return scheduleId;
  }

  public void setScheduleId(Long scheduleId) {
    this.scheduleId = scheduleId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(long durationMs) {
    this.durationMs = durationMs;
  }
}
