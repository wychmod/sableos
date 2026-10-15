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
 * <p>字段口径与 18 节 {@code sessions} 表一一对应（{@code session_id}/{@code profile_name}/{@code
 * channel}/{@code user_id}/{@code messages_json}/{@code status}/三个时间戳）。历史只增不减，时间戳单调不减。
 *
 * <p>17 节为进程内前身（调用方构造、{@code AgentService} 处理时就地累积）；18 节起由 {@code SessionManager} 负责跨重启持久化： 新建走
 * {@link #Session(String, String, String, String)}，从持久化态重建走 {@link #rehydrate}（追加式，既有构造与方法签名不变）。
 */
public final class Session {

  private static final String STATUS_ACTIVE = "active";

  private final String id;
  private final String profileName;
  private final String channel;
  private final String userId;
  private final String status;
  private final Instant createdAt;
  private final List<SessionMessage> messages;
  private Instant lastActiveAt;

  /**
   * 新建一个 {@code active} 空历史会话。
   *
   * @param id 会话标识（{@code llm_calls.session_id} 与 {@code tool_invocations.session_id} 的取值来源；由
   *     {@link SessionManager#sessionId} 拼接）
   * @param profileName 所属 Agent 运行配置名（{@link com.sableos.core.profile.ProfileRegistry} 索引键）
   * @param channel 触发渠道（cli / web / scheduler；可空，与 {@code TraceContext.CHANNEL_*} 同口径）
   * @param userId 用户标识（可空）
   */
  public Session(String id, String profileName, String channel, String userId) {
    this(id, profileName, channel, userId, STATUS_ACTIVE, Instant.now(), null, List.of());
  }

  /**
   * 从持久化态重建会话（18 节 {@code SessionManager.get/getOrCreate} 命中既有行时用）：还原历史与原始时间戳， 而非把 {@code
   * createdAt} 刷成 now、历史清空。
   *
   * @param id 会话标识
   * @param profileName 所属 Agent 运行配置名
   * @param channel 触发渠道
   * @param userId 用户标识
   * @param status 会话状态（核心阶段固定 {@code active}）
   * @param createdAt 原始创建时间
   * @param lastActiveAt 原始最后活跃时间（为空则回落 {@code createdAt}）
   * @param messages 已持久化的对话历史（按序；空历史传空表）
   * @return 与持久化态一致的会话聚合
   */
  public static Session rehydrate(
      String id,
      String profileName,
      String channel,
      String userId,
      String status,
      Instant createdAt,
      Instant lastActiveAt,
      List<SessionMessage> messages) {
    return new Session(id, profileName, channel, userId, status, createdAt, lastActiveAt, messages);
  }

  private Session(
      String id,
      String profileName,
      String channel,
      String userId,
      String status,
      Instant createdAt,
      Instant lastActiveAt,
      List<SessionMessage> messages) {
    this.id = Objects.requireNonNull(id, "id 不能为空");
    this.profileName = Objects.requireNonNull(profileName, "profileName 不能为空");
    this.channel = channel;
    this.userId = userId;
    this.status = (status == null) ? STATUS_ACTIVE : status;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
    this.lastActiveAt = (lastActiveAt == null) ? this.createdAt : lastActiveAt;
    this.messages = new ArrayList<>(messages == null ? List.of() : messages);
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
