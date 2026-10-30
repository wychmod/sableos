package com.sableos.web.api.dto;

/** 工具视图（26 节 {@code GET /tools}）：入参约定以 JSON Schema 文本原样输出。 */
public record ToolView(String name, String description, String inputSchema) {}
