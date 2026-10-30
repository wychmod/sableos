package com.sableos.core.provider;

/**
 * 单个模型提供方的实时连通状态（26 节 {@code GET /api/v1/info} 的对外数据）。
 *
 * <p>{@code reachable} 只表达“HTTP 端点是否可达”，不表达“模型是否可推理”：探测不发起模型调用、不消耗 token（见 {@link
 * ProviderHealthProbe}）。{@code error} 由探测实现做 CRLF 净化与截断后填入，仅用于诊断。
 *
 * @param name 提供方名（{@code sableos.providers[].name}）
 * @param type 协议类型（{@code openai} / {@code dashscope}）
 * @param reachable 是否可达（任意 HTTP 响应即视为可达）
 * @param latencyMs 探测耗时毫秒；不可达时可为 null
 * @param error 失败原因；可达时为 null
 */
public record ProviderStatus(
    String name, String type, boolean reachable, Long latencyMs, String error) {}
