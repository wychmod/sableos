package com.sableos.web.api.dto;

/** 工作区写文件请求（第 30 节）：相对工作区根的路径 + 文本内容。 */
public record WorkspaceWriteRequest(String path, String content) {}
