package com.sableos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * llm_calls 表的 JPA 映射（列语义见 specs/001-lesson16-provider/contracts/llm-calls-schema.md）。
 *
 * <p>表结构的唯一权威是 Flyway 双轨迁移脚本，本类只做映射，禁用 ddl-auto 改结构。
 */
@Entity
@Table(name = "llm_calls")
public class LlmCall {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "provider", nullable = false)
  private String provider;

  @Column(name = "model", nullable = false)
  private String model;

  @Column(name = "prompt_tokens")
  private Integer promptTokens;

  @Column(name = "completion_tokens")
  private Integer completionTokens;

  @Column(name = "total_tokens")
  private Integer totalTokens;

  @Column(name = "trace_id")
  private String traceId;

  @Column(name = "success", nullable = false)
  private boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "duration_ms", nullable = false)
  private long durationMs;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** JPA 要求的无参构造。 */
  protected LlmCall() {}

  @PrePersist
  void fillCreatedAt() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public Integer getPromptTokens() {
    return promptTokens;
  }

  public void setPromptTokens(Integer promptTokens) {
    this.promptTokens = promptTokens;
  }

  public Integer getCompletionTokens() {
    return completionTokens;
  }

  public void setCompletionTokens(Integer completionTokens) {
    this.completionTokens = completionTokens;
  }

  public Integer getTotalTokens() {
    return totalTokens;
  }

  public void setTotalTokens(Integer totalTokens) {
    this.totalTokens = totalTokens;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
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

  public Instant getCreatedAt() {
    return createdAt;
  }
}
