package com.sableos.web.api.dto;

/**
 * 建会话请求（26 节）：{@code profile} 必填，{@code userId} 缺省 {@code anonymous}。
 *
 * <p>不接收 {@code channel}：Web 入口固定为 {@code web}，允许覆盖会让同一用户在不同入口间产生互不相认的会话。
 */
public record CreateSessionRequest(String profile, String userId) {}
