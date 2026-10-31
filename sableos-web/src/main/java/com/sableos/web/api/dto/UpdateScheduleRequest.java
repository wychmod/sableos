package com.sableos.web.api.dto;

/**
 * 定时任务启用/停用请求（第 28 节）。
 *
 * <p>用包装类型 {@link Boolean} 而非 {@code boolean}：{@code null} 表示调用方漏传，映射 400，不能默认成 false。
 *
 * @param enabled 新的启用状态
 */
public record UpdateScheduleRequest(Boolean enabled) {}
