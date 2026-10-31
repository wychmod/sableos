package com.sableos.web.api.dto;

import java.util.Map;

/** 保存 Agent 一组文件（第 30 节）：{@code 相对路径 → 内容}，必须含 {@code AGENT.md}。 */
public record SaveFilesRequest(Map<String, String> files) {}
