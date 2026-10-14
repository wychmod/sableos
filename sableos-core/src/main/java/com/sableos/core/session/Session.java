package com.sableos.core.session;

import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.tool.ToolResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 会话聚合：一次对话的状态载体（技术方案 §4）。
 *
 * <p>字段口径按 18 节 {@code sessions} 表预留（跨重启持久化与状态机归 18 节，本节固定 {@code active} 且只增历史）， 以免 18
 * 节落地时回改。历史只增不减，时间戳单调不减。
 *
 * <p>本节为 18 节 {@code SessionManager} 的内存前身：由调用方构造、{@code AgentService} 处理时就地累积、{@code
 * InMemorySessionStore} 保存。
 */
public final class Session {

  private static final String STATUS_ACTIVE = "active";

  private final String id;
  private final String profileName;
  private final String channel;
  private final String userId;
  private final String status;
  private final Instant createdAt;
  private final List<SessionMessage> messages = new ArrayList<>();
  private Instant lastActiveAt;

  /**
   * @param id 会话标识（{@code llm_calls.session_id} 与 {@code tool_invocations.session_id} 的取值来源）
   * @param profileName 所属 Agent 运行配置名（{@link com.sableos.core.profile.ProfileRegistry} 索引键）
   * @param channel 触发渠道（cli / web / scheduler；可空，与 {@code TraceContext.CHANNEL_*} 同口径）
   * @param userId 用户标识（可空）
   */
  public Session(String id, String profileName, String channel, String userId) {
    this.id = Objects.requireNonNull(id, "id 不能为空");
    this.profileName = Objects.requireNonNull(profileName, "profileName 不能为空");
    this.channel = channel;
    this.userId = userId;
    this.status = STATUS_ACTIVE;
    this.createdAt = Instant.now();
    this.lastActiveAt = this.createdAt;
  }

  public String id() {
    return id;
  }

  public String profileName() {
    return profileName;
  }

  public String channel() {
    return channel;
  }

  public String userId() {
    return userId;
  }

  public String status() {
    return status;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant lastActiveAt() {
    return lastActiveAt;
  }

  /** 对话历史不可变视图。 */
  public List<SessionMessage> messages() {
    return List.copyOf(messages);
  }

  /** 追加一条用户消息。 */
  public void appendUserMessage(String content) {
    messages.add(SessionMessage.user(content));
    touch();
  }

  /** 追加一条模型输出（含其提出的工具调用请求）。 */
  public void appendAssistantMessage(ProviderResponse response) {
    messages.add(SessionMessage.assistant(response));
    touch();
  }

  /** 追加一条工具结果。 */
  public void appendToolResult(String toolName, ToolResult result) {
    messages.add(SessionMessage.tool(toolName, result));
    touch();
  }

  private void touch() {
    lastActiveAt = Instant.now();
  }
}
