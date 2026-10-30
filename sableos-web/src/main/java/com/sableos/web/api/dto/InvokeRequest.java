package com.sableos.web.api.dto;

/** 无状态调用请求（26 节）：与发消息共用 32KB 上限。 */
public record InvokeRequest(String message) {}
