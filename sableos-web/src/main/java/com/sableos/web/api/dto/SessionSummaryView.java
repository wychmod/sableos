package com.sableos.web.api.dto;

/**
 * 会话摘要视图（27 节）：列表页只带元数据与消息条数，不带完整对话正文。
 *
 * <p>与 {@link SessionView} 分开是刻意的：列表按 100 条返回，若复用具历史正文的视图，响应体会随对话长度线性膨胀。
 */
public record SessionSummaryView(
    String id,
    String profileName,
    String channel,
    String userId,
    String status,
    String createdAt,
    String lastActiveAt,
    int messageCount) {}
