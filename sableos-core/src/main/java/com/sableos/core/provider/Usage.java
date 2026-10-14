package com.sableos.core.provider;

/**
 * 一次模型调用的 token 用量；供应商未返回的字段为 null。
 *
 * @param promptTokens 输入 token
 * @param completionTokens 输出 token
 * @param totalTokens 合计 token
 */
public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}
