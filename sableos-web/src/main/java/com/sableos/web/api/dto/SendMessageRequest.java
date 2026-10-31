package com.sableos.web.api.dto;

/** 发消息请求（26 节）：单条内容上限 32KB，空白视为非法。 */
public record SendMessageRequest(String content) {}
