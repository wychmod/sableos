package com.sableos.web.api.dto;

/** 创建 Agent 请求（第 30 节）：只有名字与描述，其余由系统按模板脚手架生成。 */
public record CreateAgentRequest(String name, String description) {}
