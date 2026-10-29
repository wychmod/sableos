package com.sableos.web.sandbox.dto;

/**
 * 修改白名单条目的请求体：{@code type} 不可变，故不在此出现。
 *
 * @param value 新的原始输入值（必填，由服务层归一）
 * @param description 可选说明；显式传 null 表示清空
 */
public record WhitelistUpdateRequest(String value, String description) {}
