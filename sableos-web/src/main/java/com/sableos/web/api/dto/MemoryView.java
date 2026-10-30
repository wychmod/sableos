package com.sableos.web.api.dto;

/** 长期记忆视图（26 节 {@code GET /memory}）：未写入过记忆时 {@code content} 为空字符串。 */
public record MemoryView(String profile, String content) {}
