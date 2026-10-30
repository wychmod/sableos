package com.sableos.web.api.dto;

/** 单个提供方的连通状态（26 节 {@code GET /info}）。 */
public record ProviderStatusView(
    String name, String type, boolean reachable, Long latencyMs, String error) {}
