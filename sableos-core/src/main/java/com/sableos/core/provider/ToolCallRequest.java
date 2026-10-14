package com.sableos.core.provider;

/**
 * 模型返回的一次工具调用请求（原样交回上层，由 ToolExecutor 执行）。
 *
 * @param id 调用标识（上层回填工具结果时引用）
 * @param name 模型要求调用的工具名
 * @param argumentsJson 模型给出的原始 JSON 参数文本，不预解析
 */
public record ToolCallRequest(String id, String name, String argumentsJson) {}
