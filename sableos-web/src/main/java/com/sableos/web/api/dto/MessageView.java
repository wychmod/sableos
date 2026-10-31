package com.sableos.web.api.dto;

/**
 * 一条会话往来记录（26 节）。
 *
 * <p>{@code toolName}/{@code success} 仅 {@code TOOL} 角色携带；不输出模型提出的原始工具调用参数，避免把内部约定扩大为对外契约。
 */
public record MessageView(String role, String content, String toolName, Boolean success) {}
