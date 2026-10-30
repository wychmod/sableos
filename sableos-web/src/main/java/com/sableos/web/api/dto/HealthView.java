package com.sableos.web.api.dto;

/** 存活探针视图（26 节）：不依赖模型提供方或任何外部服务。 */
public record HealthView(String status) {}
